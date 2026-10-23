"""Client for the opt-in FlowSave personal-server protocol (HTTPS only)."""
import hashlib
import re
import time
from pathlib import Path
import bridge


def run_remote(action, url, media, quality, directory, cfg, observer=None):
    import requests
    base = cfg['server'].strip().rstrip('/')
    session = requests.Session()
    session.trust_env = False
    session.headers.update({'Authorization': 'Bearer ' + cfg['token'].strip()})
    job = None
    def request(method, path, **kw):
        response = session.request(method, base + path, allow_redirects=False,
                                   timeout=(15, 30), **kw)
        if 300 <= response.status_code < 400:
            response.close()
            raise ValueError('服务器发生重定向；为保护令牌，已停止请求')
        if response.status_code == 401:
            response.close()
            raise ValueError('服务器访问令牌无效')
        response.raise_for_status()
        return response
    def json_request(method, path, **kw):
        with request(method, path, stream=True, **kw) as response:
            data = bytearray()
            for chunk in response.iter_content(16384):
                data.extend(chunk)
                if len(data) > 1024 * 1024:
                    raise ValueError('服务器响应过大')
            import json
            return json.loads(data)
    try:
        # Only four task fields leave the phone: no Cookies, proxies or local paths.
        created = json_request('POST', '/v1/jobs', json={
            'action': action, 'url': url, 'media': media, 'quality': quality})
        job = created.get('id', '')
        if not re.fullmatch('[a-f0-9]{32}', job): raise ValueError('服务器任务编号无效')
        start = time.monotonic()
        while True:
            bridge._check_cancel(observer)
            if time.monotonic() - start > 960: raise TimeoutError('服务器任务超时')
            state = json_request('GET', '/v1/jobs/' + job)
            if state.get('state') == 'error': raise RuntimeError(state.get('error', '服务器任务失败'))
            if state.get('state') == 'done': break
            bridge._report(observer, min(85, int(state.get('progress', 0))), '自建服务器处理中…')
            time.sleep(.7)
        result = state.get('result') or {}
        result['paths'] = []
        files = state.get('files') or []
        if len(files) > 100: raise ValueError('服务器返回的文件数量过多')
        if action == 'download':
            if not files: raise ValueError('服务器没有生成文件')
            root = Path(directory)
            root.mkdir(parents=True, exist_ok=True)
            for index, entry in enumerate(files):
                bridge._check_cancel(observer)
                name = entry.get('name', '')
                if not name or name != Path(name).name or '/' in name or '\\' in name or name.startswith('.'):
                    raise ValueError('服务器返回了不安全的文件名')
                size = int(entry.get('size') or 0)
                digest = entry.get('sha256', '')
                if not 0 < size <= 8 * 1024**3 or not re.fullmatch('[a-f0-9]{64}', digest):
                    raise ValueError('服务器文件清单无效')
                dest = root / f'{index+1:03d}-{name}'
                tmp = Path(str(dest) + '.part')
                try:
                    count, hasher = 0, hashlib.sha256()
                    with request('GET', f'/v1/jobs/{job}/files/{index}', stream=True) as response, tmp.open('wb') as out:
                        for chunk in response.iter_content(65536):
                            bridge._check_cancel(observer)
                            count += len(chunk)
                            if count > size: raise ValueError('服务器文件超过声明大小')
                            out.write(chunk)
                            hasher.update(chunk)
                            bridge._report(observer, 86 + int(10 * (index + count / size) / len(files)), f'接收文件 {index+1}/{len(files)}')
                    if count != size or hasher.hexdigest() != digest:
                        raise ValueError('文件完整性校验失败')
                    tmp.replace(dest)
                    result['paths'].append(str(dest))
                finally:
                    tmp.unlink(missing_ok=True)
        return result
    finally:
        if job and re.fullmatch('[a-f0-9]{32}', job):
            try:
                session.delete(base + '/v1/jobs/' + job, allow_redirects=False, timeout=(5, 5))
            except Exception:
                pass
        session.close()
