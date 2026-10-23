"""FlowSave 0.3: stream-copy video, image galleries, explicit network routing.

Platform authentication and access restrictions are not bypassed. No credentials
are ever sent to the optional download server. Gallery extractors remain an
unmodified separately licensed gallery-dl dependency.
"""
from __future__ import annotations
import copy
import html
import http.cookiejar
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import unquote, urlparse, parse_qs
import bridge

IMAGE_EXTS = {'jpg', 'jpeg', 'png', 'webp', 'gif', 'avif', 'heic', 'heif'}
MAX_IMAGES = 100
MAX_IMAGE_BYTES = 64 * 1024 * 1024
QUALITIES = {'best', '2160', '1080', '720', 'compat', 'audio'}


def config_value(raw):
    cfg = json.loads(raw) if isinstance(raw, str) and raw else dict(raw or {})
    mode = cfg.get('network', 'direct')
    if mode not in ('direct', 'proxy', 'server'):
        raise ValueError('未知网络模式')
    if mode == 'proxy':
        p = urlparse(cfg.get('proxy', '').strip())
        if p.scheme not in ('http', 'https', 'socks5', 'socks5h') or not p.hostname or not p.port:
            raise ValueError('代理地址应包含协议、主机和端口，例如 socks5://127.0.0.1:1080')
        if p.path not in ('', '/') or p.query or p.fragment:
            raise ValueError('代理地址不能包含路径、查询参数或片段')
    if mode == 'server':
        p = urlparse(cfg.get('server', '').strip())
        if p.scheme != 'https' or not p.hostname or p.username or p.password or p.query or p.fragment:
            raise ValueError('服务器必须是有效的 HTTPS 地址，不可在地址内包含密码')
        if len(str(cfg.get('token', '')).strip()) < 32:
            raise ValueError('请填写至少 32 个字符的服务器访问令牌')
    return cfg


def options(cfg, cookies='', qjs=''):
    opts = bridge._options(cookies, qjs)
    opts['proxy'] = cfg.get('proxy', '').strip() if cfg.get('network') == 'proxy' else ''
    opts.update({'fixup': 'never', 'noplaylist': True, 'playlistend': 1,
                 'postprocessors': [], 'quiet': True, 'no_warnings': True})
    if cfg.get('runtime_node'):
        opts['js_runtimes'] = {'node': {'path': cfg['runtime_node']}}
    return opts


def resolution(fmt):
    w, h = int(fmt.get('width') or 0), int(fmt.get('height') or 0)
    return min(w, h) if w and h else max(w, h)


def number(value):
    try:
        return float(value or 0)
    except (ValueError, TypeError):
        return 0.0


def select_media(info, quality='best'):
    """Do not trade higher resolution for MP4 unless explicitly requested."""
    if quality not in QUALITIES:
        raise ValueError('未知画质档位')
    fmts = [f for f in info.get('formats', [info]) if isinstance(f, dict) and bridge._available(f)]
    video = [f for f in fmts if bridge._has_video(f)]
    audio = [f for f in fmts if bridge._has_audio(f) and not bridge._has_video(f)]
    vscore = lambda f: (resolution(f), number(f.get('fps')), number(f.get('tbr')))
    ascore = lambda f: (number(f.get('quality')), number(f.get('abr') or f.get('tbr')),
                        number(f.get('asr')))
    if quality == 'audio':
        if audio:
            return {'tracks': [max(audio, key=ascore)], 'extract_audio': False}
        combined = [f for f in video if bridge._has_audio(f)]
        if combined:
            return {'tracks': [max(combined, key=vscore)], 'extract_audio': True}
        raise ValueError('没有可用的音轨')
    if quality.isdigit():
        # Unknown dimensions are not silently treated as meeting a resolution cap.
        video = [f for f in video if 0 < resolution(f) <= int(quality)]
    if quality == 'compat':
        video = [f for f in video if f.get('ext') == 'mp4'
                 and str(f.get('vcodec', '')).startswith(('avc1', 'h264'))
                 and (not bridge._has_audio(f) or str(f.get('acodec', '')).startswith(('mp4a', 'aac')))]
        audio = [f for f in audio if str(f.get('acodec', '')).startswith(('mp4a', 'aac'))]
    usable = [f for f in video if bridge._has_audio(f) or audio]
    if not usable:
        # Truly silent media is valid, but don't silently omit an existing audio track.
        usable = video if not any(bridge._has_audio(f) for f in fmts) else []
    if not usable:
        raise ValueError('站点没有提供该档位的可用格式，请选择“最高可用”或其他画质')
    selected = max(usable, key=vscore)
    tracks = [selected]
    if not bridge._has_audio(selected) and audio:
        tracks.append(max(audio, key=ascore))
    return {'tracks': tracks, 'extract_audio': False}


def describe(selected):
    f = selected['tracks'][0]
    w, h = f.get('width'), f.get('height')
    res = f'{w} × {h}' if w and h else (str(h) + 'p' if h else '尺寸未知')
    codecs = [str(f.get('vcodec') or '')]
    codecs += [str(t.get('acodec') or '') for t in selected['tracks']]
    codecs = [s for s in codecs if s and s != 'none']
    fps = f" · {number(f.get('fps')):g} fps" if f.get('fps') else ''
    return res + fps + ' · ' + ' / '.join(codecs) + ' · 不重新编码'


def output_extension(tracks, audio_only=False):
    if audio_only:
        ac = str(tracks[0].get('acodec') or '')
        return 'm4a' if ac.startswith(('mp4a', 'aac', 'alac')) else 'mka'
    vc = str(tracks[0].get('vcodec') or '')
    ac = str(tracks[-1].get('acodec') or '')
    mp4 = vc.startswith(('avc1', 'h264', 'hev1', 'hvc1', 'hevc', 'av01', 'av1'))
    return 'mp4' if mp4 and (ac.startswith(('mp4a', 'aac')) or ac in ('none', '')) else 'mkv'


def safe_name(value):
    value = re.sub(r'[\\/:*?"<>|\x00-\x1f]', '_', str(value or 'FlowSave')).strip('. ')
    return (value[:72] or 'FlowSave')


def stream_copy(ffmpeg, inputs, dest, observer=None, audio_only=False):
    if not ffmpeg or not os.access(ffmpeg, os.X_OK):
        raise ValueError('缺少可执行的 FFmpeg 合并引擎，不能无损合并所选格式')
    command = [str(ffmpeg), '-hide_banner', '-nostdin', '-loglevel', 'error', '-y']
    for src in inputs:
        command += ['-protocol_whitelist', 'file,pipe', '-i', str(Path(src).resolve())]
    if audio_only:
        command += ['-map', '0:a:0', '-vn', '-c:a', 'copy']
    elif len(inputs) == 2:
        command += ['-map', '0:v:0', '-map', '1:a:0', '-c', 'copy']
    else:
        command += ['-map', '0', '-c', 'copy']
    command += [str(Path(dest).resolve())]
    log = Path(dest).with_suffix('.fflog')
    with log.open('wb') as err:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=err)
        started = time.monotonic()
        try:
            while process.poll() is None:
                bridge._check_cancel(observer)
                if time.monotonic() - started > 600:
                    raise TimeoutError('音视频合并超时')
                time.sleep(.15)
            if process.returncode or not Path(dest).is_file() or not Path(dest).stat().st_size:
                raise RuntimeError('合并失败：媒体流不完整或封装格式不受支持；可尝试“兼容 MP4”')
        except BaseException:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            Path(dest).unlink(missing_ok=True)
            raise
        finally:
            log.unlink(missing_ok=True)
    return str(dest)


def download_video(url, quality, stage, cfg, cookies, qjs, ffmpeg, observer):
    from yt_dlp import YoutubeDL
    state = {'track': 0, 'count': 1, 'last': None, 'ts': 0.0}
    def progress(data):
        bridge._check_cancel(observer)
        if data.get('status') == 'finished':
            state['last'] = data.get('filename')
        if time.monotonic() - state['ts'] < .2:
            return
        state['ts'] = time.monotonic()
        size = data.get('total_bytes') or data.get('total_bytes_estimate') or 0
        frac = min(1, (data.get('downloaded_bytes') or 0) / size) if size else 0
        pct = int(90 * (state['track'] + frac) / state['count'])
        speed = number(data.get('speed')) / 1048576
        bridge._report(observer, pct, f"下载轨道 {state['track']+1}/{state['count']} · {speed:.1f} MB/s")
    opts = options(cfg, cookies, qjs)
    opts.update({'paths': {'home': str(stage)}, 'progress_hooks': [progress],
                 'outtmpl': {'default': '%(id)s-%(format_id)s.%(ext)s'},
                 'overwrites': True, 'windowsfilenames': True})
    with YoutubeDL(opts) as ydl:
        info = bridge._summarize(ydl.extract_info(url, download=False))
        chosen = select_media(info, quality)
        state['count'] = len(chosen['tracks'])
        paths = []
        for index, track in enumerate(chosen['tracks']):
            state['track'], state['last'] = index, None
            bridge._check_cancel(observer)
            item = copy.deepcopy(info)
            for key in ('requested_formats', 'requested_downloads', 'entries'):
                item.pop(key, None)
            item.update(track)
            ydl.process_info(item)
            candidates = [item.get('filepath'), item.get('_filename'), state['last'], ydl.prepare_filename(item)]
            path = next((p for p in candidates if p and Path(p).is_file() and not str(p).endswith('.part')), None)
            if not path:
                raise ValueError('未找到当前轨道的完整下载文件')
            paths.append(str(path))
        if len(set(paths)) != len(paths):
            raise ValueError('轨道文件名冲突')
        bridge._check_cancel(observer)
        if len(paths) > 1 or chosen['extract_audio']:
            bridge._report(observer, 94, '正在无损整理音视频；不重新编码…')
            ext = output_extension(chosen['tracks'], chosen['extract_audio'])
            final = stage / (safe_name(info.get('title')) + '.' + ext)
            stream_copy(ffmpeg, paths, final, observer, chosen['extract_audio'])
        else:
            ext = Path(paths[0]).suffix
            final = stage / (safe_name(info.get('title')) + ext)
            if Path(paths[0]) != final:
                os.replace(paths[0], final)
    return {'paths': [str(final)], 'title': str(info.get('title') or final.name),
            'summary': describe(chosen), 'warnings': []}


def new_session(cfg, cookies=''):
    import requests
    from requests.adapters import HTTPAdapter
    from urllib3.util.retry import Retry
    session = requests.Session()
    session.trust_env = False
    if cfg.get('network') == 'proxy':
        proxy = cfg['proxy'].strip()
        if proxy.startswith('socks5://'):
            proxy = 'socks5h://' + proxy[len('socks5://'):]
        session.proxies.update(http=proxy, https=proxy)
    if cookies and Path(cookies).is_file():
        jar = http.cookiejar.MozillaCookieJar(cookies)
        jar.load(ignore_discard=True, ignore_expires=False)
        session.cookies.update(jar)
    session.headers.update({'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36',
                            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8'})
    adapter = HTTPAdapter(max_retries=Retry(total=2, backoff_factor=.5, status_forcelist=(502, 503, 504)))
    session.mount('https://', adapter)
    session.mount('http://', adapter)
    return session


def image_extension(head):
    if head.startswith(b'\xff\xd8\xff'): return 'jpg'
    if head.startswith(b'\x89PNG\r\n\x1a\n'): return 'png'
    if head[:6] in (b'GIF87a', b'GIF89a'): return 'gif'
    if head[:4] == b'RIFF' and head[8:12] == b'WEBP': return 'webp'
    if head[4:8] == b'ftyp':
        if b'avif' in head[8:40] or b'avis' in head[8:40]: return 'avif'
        if any(x in head[8:40] for x in (b'heic', b'heix', b'mif1')): return 'heic'
    raise ValueError('服务器没有返回有效图片，可能返回了登录或验证页面')


def save_image(url, session, stage, index, observer=None):
    parsed = urlparse(url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username:
        raise ValueError('图片 URL 无效')
    tmp = stage / f'image-{index:03d}.part'
    try:
        with session.get(url, stream=True, timeout=(15, 30)) as response:
            response.raise_for_status()
            if int(response.headers.get('Content-Length') or 0) > MAX_IMAGE_BYTES:
                raise ValueError('单张图片超过 64 MB 限制')
            chunks = response.iter_content(65536)
            first = next((chunk for chunk in chunks if chunk), b'')
            ext = image_extension(first)
            count = len(first)
            with tmp.open('wb') as out:
                out.write(first)
                for chunk in chunks:
                    bridge._check_cancel(observer)
                    count += len(chunk)
                    if count > MAX_IMAGE_BYTES:
                        raise ValueError('单张图片超过 64 MB 限制')
                    out.write(chunk)
            if not count:
                raise ValueError('图片为空')
            final = stage / f'FlowSave-{index:03d}.{ext}'
            os.replace(tmp, final)
            return str(final)
    finally:
        tmp.unlink(missing_ok=True)


def is_single_post(url):
    p = urlparse(url)
    host, path = (p.hostname or '').lower(), p.path
    domain = lambda d: host == d or host.endswith('.' + d)
    if domain('instagram.com'): return bool(re.match(r'^/(p|reel|tv)/[^/]+', path))
    if host in ('x.com', 'www.x.com', 'twitter.com', 'www.twitter.com', 'mobile.twitter.com'):
        return bool(re.search(r'/status/\d+', path))
    if domain('tiktok.com'): return bool(re.search(r'/@[^/]+/(photo|video)/\d+', path))
    if domain('douyin.com') or domain('iesdouyin.com'):
        return bool(re.search(r'/(note|video|share/slides)/\d+', path) or parse_qs(p.query).get('modal_id'))
    if domain('bilibili.com'): return bool(re.match(r'^/read/cv\d+', path))
    return False


def douyin_images(document, wanted_id):
    """Read public page state only. Never substitute cover/recommendation images."""
    roots = []
    for value in re.findall(r'<script[^>]+id=[\"\']RENDER_DATA[\"\'][^>]*>(.*?)</script>', document, re.S):
        try: roots.append(json.loads(unquote(html.unescape(value))))
        except (ValueError, TypeError): pass
    for match in re.finditer(r'(?:window\.)?_ROUTER_DATA\s*=\s*', document):
        try: roots.append(json.JSONDecoder().raw_decode(document[match.end():])[0])
        except ValueError: pass
    pending, visited = list(roots), 0
    while pending and visited < 50000:
        node = pending.pop()
        visited += 1
        if isinstance(node, dict):
            identity = str(node.get('aweme_id') or node.get('awemeId') or '')
            if identity == wanted_id:
                images = node.get('images') or (node.get('image_post_info') or {}).get('images') or []
                result = []
                for image in images:
                    if not isinstance(image, dict): continue
                    value = image.get('display_image') or image
                    urls = value.get('url_list') or value.get('urlList') or []
                    if urls and isinstance(urls[0], str): result.append(urls[0])
                if result: return list(dict.fromkeys(result))
            pending.extend(node.values())
        elif isinstance(node, list): pending.extend(node)
    raise ValueError('抖音图集实验性解析未得到正文图片；可导入本人 Cookies 后重试。不会改存封面冒充图集')


def gallery_items(url, cfg, cookies, observer):
    session = new_session(cfg, cookies)
    p = urlparse(url)
    if p.path.rsplit('.', 1)[-1].lower() in IMAGE_EXTS:
        yield url, session, {}
        return
    if p.hostname in ('v.douyin.com', 'vm.tiktok.com', 'vt.tiktok.com', 'b23.tv'):
        with session.get(url, timeout=(15, 30), stream=True) as response:
            response.raise_for_status()
            url = response.url
    if not is_single_post(url):
        raise ValueError('图集模式需要单条图文链接；不下载个人主页或合集。视频封面请选“封面”')
    session.headers['Referer'] = url
    if bridge.detect_site(url) == '抖音':
        match = re.search(r'/(?:note|video|share/slides)/(\d+)', url)
        wanted = match.group(1) if match else parse_qs(urlparse(url).query).get('modal_id', [''])[0]
        with session.get(url, stream=True, timeout=(15, 30)) as response:
            response.raise_for_status()
            data = bytearray()
            for chunk in response.iter_content(65536):
                bridge._check_cancel(observer)
                data.extend(chunk)
                if len(data) > 8 * 1024 * 1024: raise ValueError('页面数据过大')
        for image in douyin_images(data.decode('utf-8', 'replace'), wanted):
            yield image, session, {}
        return
    from gallery_dl import extractor
    from gallery_dl.extractor.message import Message
    queue = [(url, 0)]
    seen = set()
    while queue:
        current, depth = queue.pop(0)
        if current in seen or depth > 3: continue
        seen.add(current)
        ex = extractor.find(current)
        if ex is None: raise ValueError('图库引擎尚不支持该链接')
        base_config = ex.config
        settings = {'videos': False, 'audio': False, 'covers': False, 'photos': True,
                    'retries': 2, 'timeout': 30, 'sleep-request': 0,
                    'proxy': cfg.get('proxy', '') if cfg.get('network') == 'proxy' else None}
        ex.config = lambda key, default=None, base=base_config: settings.get(key, base(key, default))
        ex.session = session
        for message in ex:
            bridge._check_cancel(observer)
            if len(message) < 3: continue
            typ, media_url, meta = message
            if typ == Message.Queue:
                if is_single_post(media_url) and bridge.detect_site(media_url) == bridge.detect_site(url):
                    queue.append((media_url, depth + 1))
            elif typ == Message.Url:
                ext = str(meta.get('extension') or '').lower()
                if ext in IMAGE_EXTS or meta.get('type') == 'image':
                    yield media_url, ex.session, dict(meta)


def images(url, action, stage, cfg, cookies, qjs, observer, cover=False):
    paths, failures, seen = [], [], set()
    count, truncated = 0, False
    if cover:
        from yt_dlp import YoutubeDL
        with YoutubeDL(options(cfg, cookies, qjs)) as ydl:
            info = bridge._summarize(ydl.extract_info(url, download=False))
        thumbs = [t for t in info.get('thumbnails', []) if t.get('url')]
        if thumbs:
            image = max(thumbs, key=lambda t: number(t.get('width')) * number(t.get('height')))['url']
        else: image = info.get('thumbnail')
        if not image: raise ValueError('没有可用的视频封面')
        session = new_session(cfg, cookies)
        session.headers.update(info.get('http_headers') or {})
        items = iter([(image, session, {})])
    else:
        items = gallery_items(url, cfg, cookies, observer)
    for media_url, session, meta in items:
        if media_url in seen: continue
        seen.add(media_url)
        if count >= MAX_IMAGES:
            truncated = True
            break
        count += 1
        bridge._check_cancel(observer)
        bridge._report(observer, min(88, count), f'已发现 {count} 张图片' if action == 'probe' else f'正在保存第 {count} 张图片…')
        if action == 'download':
            try: paths.append(save_image(media_url, session, stage, count, observer))
            except Exception as exc:
                bridge._check_cancel(observer)
                failures.append(f'第 {count} 张：{safe_error(exc)}')
    if not count: raise ValueError('没有提取到正文图片；登录或平台验证可能阻止了访问')
    if action == 'download' and not paths: raise ValueError('图片保存失败：' + '; '.join(failures[:2]))
    warning = failures[:8]
    if truncated: warning.append('为控制空间，本次最多处理 100 张图片')
    return {'title': '视频封面' if cover else '图片 / 图集', 'paths': paths,
            'summary': f'发现 {count} 张' + (f' · 保存 {len(paths)} 张' if action == 'download' else '') + ' · 保留下载到的图片字节，不重新压缩',
            'warnings': warning, 'image_count': count}


def safe_error(exc):
    text = str(exc)
    text = re.sub(r'https?://[^\s\"<>]+|socks5h?://[^\s\"<>]+', '[地址已隐藏]', text)
    text = re.sub(r'(?i)(bearer\s+|token[=: ]+)[^\s,;]+', '[凭据已隐藏]', text)
    return text[:300]


def execute(action, raw_url, media, quality, output_dir, config_json='{}', cookies='', qjs='', ffmpeg='', observer=None):
    try:
        if action not in ('probe', 'download') or media not in ('video', 'images', 'cover'):
            raise ValueError('无效的任务类型')
        cfg = config_value(config_json)
        url = bridge.clean_url(raw_url)
        if cfg.get('network') == 'server':
            from remote_client import run_remote
            # Cookies and proxy credentials deliberately are NOT arguments.
            return json.dumps(run_remote(action, url, media, quality, output_dir, cfg, observer), ensure_ascii=False)
        stage = Path(output_dir) if output_dir else None
        if action == 'download': stage.mkdir(parents=True, exist_ok=True)
        if media != 'video':
            result = images(url, action, stage, cfg, cookies, qjs, observer, media == 'cover')
        elif action == 'probe':
            from yt_dlp import YoutubeDL
            with YoutubeDL(options(cfg, cookies, qjs)) as ydl:
                info = bridge._summarize(ydl.extract_info(url, download=False))
            chosen = select_media(info, quality)
            result = {'title': str(info.get('title') or '视频'), 'summary': describe(chosen), 'warnings': [], 'paths': []}
        else:
            result = download_video(url, quality, stage, cfg, cookies, qjs, ffmpeg, observer)
        result['site'] = bridge.detect_site(url)
        return json.dumps(result, ensure_ascii=False)
    except Exception as exc:
        return json.dumps({'error': safe_error(exc)}, ensure_ascii=False)


def self_test(ffmpeg, qjs, directory):
    """Offline device test. It does not claim platform/network download coverage."""
    result = {}
    for name, args, expected in (
        ('ffmpeg', [ffmpeg, '-version'], b'ffmpeg version'),
        ('quickjs', [qjs, '-e', 'console.log(1+1)'], b'2'),
    ):
        try:
            done = subprocess.run(args, capture_output=True, timeout=20)
            result[name] = done.returncode == 0 and expected in done.stdout
        except Exception as exc:
            result[name] = safe_error(exc)
    folder = Path(directory)
    if (folder / 'video.webm').is_file() and (folder / 'audio.webm').is_file():
        try:
            output = folder / 'merged.mkv'
            stream_copy(ffmpeg, [folder / 'video.webm', folder / 'audio.webm'], output)
            result['vp9_opus_merge'] = output.is_file() and output.stat().st_size > 0
        except Exception as exc:
            result['vp9_opus_merge'] = safe_error(exc)
    else:
        result['vp9_opus_merge'] = 'test fixture unavailable'
    try:
        from gallery_dl import extractor
        result['gallery_extractors'] = all(extractor.find(url) is not None for url in (
            'https://www.instagram.com/p/CabcDEF1234/',
            'https://www.tiktok.com/@test/photo/7345678901234567890',
            'https://x.com/test/status/1234567890123456789'))
    except Exception as exc:
        result['gallery_extractors'] = safe_error(exc)
    return json.dumps(result, ensure_ascii=False)
