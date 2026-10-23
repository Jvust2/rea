"""FlowSave downloader powered by the user's yt-dlp fork.

No remote proxy/service; all extraction and bytes transfer take place on the device.
A compatible Android QuickJS executable and yt-dlp-ejs package are optional
at runtime but bundled in the production CI build for better YouTube support.
"""

from __future__ import annotations

import copy
import json
import os
import re
import time
from pathlib import Path
from urllib.parse import urlparse

SUPPORTED = (
    (('youtube.com', 'youtu.be', 'youtube-nocookie.com'), 'YouTube'),
    (('twitter.com', 'x.com'), 'X / Twitter'),
    (('tiktok.com',), 'TikTok'),
    (('instagram.com',), 'Instagram'),
    (('douyin.com', 'iesdouyin.com'), '抖音'),
    (('bilibili.com', 'b23.tv'), 'Bilibili'),
)
URL_PATTERN = re.compile(r'https?://[^\s<>"\'\u201c\u201d\u300a\u300b\u3010\u3011]+', re.I)


def clean_url(text):
    """Accept URLs embedded in share messages, without dropping URL parameters."""
    match = URL_PATTERN.search((text or '').strip())
    if not match:
        raise ValueError('请粘贴有效的视频链接（http 或 https）')
    url = match.group(0).rstrip('.,;:!?)\uff0c\u3002\uff1b\uff1a\uff01\uff1f\uff09\u3011\u300b')
    parsed = urlparse(url)
    if parsed.scheme.lower() not in ('http', 'https') or not parsed.hostname:
        raise ValueError('视频链接无效')
    if parsed.username or parsed.password:
        raise ValueError('视频链接不能包含登录凭据')
    return url


def detect_site(url):
    try:
        hostname = (urlparse(clean_url(url)).hostname or '').lower().strip('.')
    except ValueError:
        return '其他站点'
    for domains, name in SUPPORTED:
        if any(hostname == domain or hostname.endswith('.' + domain) for domain in domains):
            return name
    return '其他站点'


def _report(observer, percent, message):
    if observer is None:
        return
    try:
        observer.onProgress(int(max(0, min(100, percent))), str(message))
    except Exception:
        pass


def _check_cancel(observer):
    if observer is not None and hasattr(observer, 'isCancelled') and observer.isCancelled():
        raise RuntimeError('已取消下载')


def _options(cookie_path=None, qjs_path=None):
    options = {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'ignoreerrors': False,
        'cachedir': False,
        'socket_timeout': 30,
        'retries': 5,
        'fragment_retries': 10,
        'concurrent_fragment_downloads': 3,
        'http_headers': {'Accept-Language': 'zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7'},
    }
    if cookie_path and Path(cookie_path).is_file():
        options['cookiefile'] = str(cookie_path)
    if qjs_path and Path(qjs_path).is_file() and os.access(qjs_path, os.X_OK):
        # API equivalent of --js-runtimes quickjs:/path/to/qjs.
        options['js_runtimes'] = {'quickjs': {'path': str(qjs_path)}}
    return options


def _has_video(fmt):
    return str(fmt.get('vcodec', 'none')) not in ('none', '', 'None')


def _has_audio(fmt):
    return str(fmt.get('acodec', 'none')) not in ('none', '', 'None')


def _available(fmt):
    return bool(fmt.get('url') or fmt.get('fragments')) and not fmt.get('has_drm')


def _quality_height(quality):
    return {'1080': 1080, '720': 720, '480': 480}.get(quality)


def _height(fmt):
    try:
        return int(fmt.get('height') or 0)
    except (ValueError, TypeError):
        return 0


def _rate(fmt):
    try:
        return float(fmt.get('tbr') or 0)
    except (ValueError, TypeError):
        return 0


def _video_score(fmt):
    ext = str(fmt.get('ext') or '')
    codec = str(fmt.get('vcodec') or '')
    # Format selection on mobile favors broadly hardware-decodable H.264 MP4.
    return (_height(fmt), 1 if ext == 'mp4' else 0,
            1 if codec.startswith(('avc1', 'h264')) else 0, _rate(fmt))


def _audio_score(fmt):
    ext = str(fmt.get('ext') or '')
    return (1 if ext in ('m4a', 'mp4') else 0, _rate(fmt))


def choose_formats(info, quality='best'):
    """Return a single format or MP4(video-only) + M4A audio pair.

    yt-dlp normally relies on FFmpeg for merging. On Android, our Java
    MediaMuxer handles compatible MP4/H.264/AAC tracks instead.
    """
    if quality not in ('best', '1080', '720', '480', 'audio'):
        raise ValueError('未知画质选项')
    formats = [f for f in (info.get('formats') or [info]) if isinstance(f, dict) and _available(f)]
    if not formats:
        raise ValueError('未找到可下载的媒体格式，可能需要登录或此视频已受保护')
    if quality == 'audio':
        candidates = [f for f in formats if _has_audio(f) and not _has_video(f)]
        if candidates:
            return {'kind': 'single', 'format': max(candidates, key=_audio_score), 'audio': True}
        # Sites such as TikTok may only expose one MP4 with an embedded AAC track.
        # Download this combined file, then let Android MediaMuxer extract its audio.
        combined = [f for f in formats if _has_audio(f) and _has_video(f) and f.get('ext') == 'mp4']
        if combined:
            return {'kind': 'single', 'format': max(combined, key=_video_score),
                    'audio': True, 'extract_audio': True}
        raise ValueError('此内容没有可单独导出的音轨')

    limit = _quality_height(quality)
    video = [f for f in formats if _has_video(f) and (not limit or not _height(f) or _height(f) <= limit)]
    # If no resolution <= limit exists, fall back to lowest available instead of failing.
    if not video and limit:
        video = [f for f in formats if _has_video(f)]
        if video:
            least_height = min((_height(f) for f in video if _height(f) > 0), default=0)
            if least_height:
                video = [f for f in video if _height(f) == least_height]
    single = [f for f in video if _has_audio(f)]
    mp4_video = [f for f in video if f.get('ext') == 'mp4' and not _has_audio(f)]
    m4a_audio = [f for f in formats if not _has_video(f) and _has_audio(f)
                 and f.get('ext') in ('m4a', 'mp4')]

    best_single = max(single, key=_video_score, default=None)
    best_split = max(mp4_video, key=_video_score, default=None)
    if best_split and m4a_audio and (not best_single or _video_score(best_split) > _video_score(best_single)):
        return {
            'kind': 'mux',
            'video': best_split,
            'audio_format': max(m4a_audio, key=_audio_score),
            'audio': False,
        }
    if best_single:
        return {'kind': 'single', 'format': best_single, 'audio': False}
    if best_split and m4a_audio:
        return {
            'kind': 'mux', 'video': best_split,
            'audio_format': max(m4a_audio, key=_audio_score), 'audio': False,
        }
    raise ValueError('此视频仅提供无法直接合并的音视频流，请尝试另一画质或更新下载引擎')


def _summarize(info):
    if not isinstance(info, dict):
        raise ValueError('视频信息为空')
    if info.get('_type') in ('playlist', 'multi_video') and info.get('entries'):
        info = next((x for x in info['entries'] if isinstance(x, dict)), None)
        if info is None:
            raise ValueError('没有可下载的视频')
    return info


def _hint(exc, site):
    message = str(exc)
    low = message.lower()
    if 'cancel' in low or '取消' in message:
        return '下载已取消'
    if any(word in low for word in ('log in', 'login', 'private', 'cookies', 'sign in', 'authentication')):
        return '内容可能需要登录，请在设置中导入当前平台的 Netscape Cookies 文件'
    if any(word in low for word in ('forbidden', 'http error 403', 'rate limit', '429', 'captcha')):
        return '站点限制了访问，请稍后重试；如需登录可导入 Cookies'
    if site == 'YouTube' and any(word in low for word in ('javascript', 'challenge', 'signature', 'n challenge')):
        return 'YouTube JS 验证失败，请检查 QuickJS 运行环境或更新 yt-dlp'
    return message[:350]


def probe(raw_url, cookie_path=None, qjs_path=None):
    """Extract metadata. No file is downloaded during probe."""
    url = None
    try:
        url = clean_url(raw_url)
        from yt_dlp import YoutubeDL
        with YoutubeDL({**_options(cookie_path, qjs_path), 'skip_download': True}) as ydl:
            info = _summarize(ydl.extract_info(url, download=False))
        all_formats = info.get('formats') or []
        return json.dumps({
            'site': detect_site(url),
            'title': str(info.get('title') or '未命名视频'),
            'uploader': str(info.get('uploader') or info.get('channel') or ''),
            'duration': int(info.get('duration') or 0),
            'thumbnail': str(info.get('thumbnail') or ''),
            'format_count': len(all_formats),
        }, ensure_ascii=False)
    except Exception as exc:
        return json.dumps({'error': _hint(exc, detect_site(url or raw_url))}, ensure_ascii=False)


def _filename_after(ydl, entry, progress_path):
    candidates = [entry.get('filepath'), entry.get('_filename'), progress_path[0], ydl.prepare_filename(entry)]
    for candidate in candidates:
        if candidate and os.path.isfile(candidate) and not str(candidate).endswith(('.part', '.ytdl')):
            return str(candidate)
    base = Path(ydl.params['paths']['home'])
    files = [p for p in base.iterdir() if p.is_file() and not p.name.endswith(('.part', '.ytdl'))]
    if files:
        return str(max(files, key=lambda p: p.stat().st_mtime))
    raise ValueError('未找到下载后的媒体文件')


def _download_entry(ydl, info, selected_format, observer, index, total):
    item = copy.deepcopy(info)
    item.pop('requested_formats', None)
    item.pop('requested_downloads', None)
    item.update(selected_format)
    # Avoid letting the output extension (e.g. mp4/m4a) be inferred from source metadata.
    item['format_id'] = selected_format.get('format_id') or item.get('format_id')
    item['ext'] = selected_format.get('ext') or item.get('ext')
    _check_cancel(observer)
    ydl.process_info(item)
    _check_cancel(observer)
    return item


def download(raw_url, quality, output_dir, observer=None, cookie_path=None, qjs_path=None):
    """Download formats to an isolated staging directory; return paths to Java.

    With split video/audio, Android MediaMuxer combines the files after Python
    returns. The bridge never transcodes or attempts to bypass DRM.
    """
    url = None
    try:
        url = clean_url(raw_url)
        stage = Path(output_dir)
        stage.mkdir(parents=True, exist_ok=True)
        from yt_dlp import YoutubeDL

        total_progress = {'track': 0, 'count': 1}
        last_report = {'ts': 0.0}
        last_file = [None]

        def progress(data):
            _check_cancel(observer)
            now = time.monotonic()
            if data.get('status') == 'finished':
                last_file[0] = data.get('filename')
                percent = 95 if total_progress['count'] == 1 else int(92 * (total_progress['track'] + 1) / total_progress['count'])
                _report(observer, percent, '媒体数据下载完成，正在整理文件…')
                return
            if data.get('status') != 'downloading' or now - last_report['ts'] < 0.13:
                return
            last_report['ts'] = now
            done = data.get('downloaded_bytes') or 0
            size = data.get('total_bytes') or data.get('total_bytes_estimate') or 0
            frac = min(1., done / size) if size else 0
            overall = int(92 * (total_progress['track'] + frac) / total_progress['count'])
            speed = data.get('speed') or 0
            extra = ' · %.1f MB/s' % (speed / 1048576) if speed else ''
            stage_name = '音轨' if total_progress['count'] == 2 and total_progress['track'] else '视频'
            if size:
                text = '%s下载 %.0f%%%s' % (stage_name, frac * 100, extra)
            else:
                text = '%s下载中%s' % (stage_name, extra)
            _report(observer, overall, text)

        opts = _options(cookie_path, qjs_path)
        opts.update({
            'fixup': 'never',
            'paths': {'home': str(stage)},
            'outtmpl': {'default': '%(title).90B [%(id)s]-%(format_id)s.%(ext)s'},
            'progress_hooks': [progress],
            'overwrites': True,
            'windowsfilenames': True,
            'keepvideo': True,
            'noplaylist': True,
        })
        _report(observer, 0, '正在连接 %s…' % detect_site(url))
        with YoutubeDL(opts) as ydl:
            info = _summarize(ydl.extract_info(url, download=False))
            selected = choose_formats(info, quality)
            _report(observer, 2, '已找到媒体，开始下载…')
            if selected['kind'] == 'single':
                total_progress.update(track=0, count=1)
                last_file[0] = None
                item = _download_entry(ydl, info, selected['format'], observer, 0, 1)
                result = _filename_after(ydl, item, last_file)
                return json.dumps({'kind': 'single', 'path': result, 'site': detect_site(url),
                                   'extract_audio': selected.get('extract_audio', False)}, ensure_ascii=False)
            total_progress.update(track=0, count=2)
            last_file[0] = None
            video_item = _download_entry(ydl, info, selected['video'], observer, 0, 2)
            video_path = _filename_after(ydl, video_item, last_file)
            total_progress['track'] = 1
            last_file[0] = None
            audio_item = _download_entry(ydl, info, selected['audio_format'], observer, 1, 2)
            audio_path = _filename_after(ydl, audio_item, last_file)
        if video_path == audio_path:
            raise ValueError('视频与音轨冲突，无法合并')
        return json.dumps({'kind': 'mux', 'video_path': video_path, 'audio_path': audio_path,
                           'title': str(info.get('title') or '下载视频')[:90],
                           'site': detect_site(url)}, ensure_ascii=False)
    except Exception as exc:
        return json.dumps({'error': _hint(exc, detect_site(url or raw_url))}, ensure_ascii=False)
