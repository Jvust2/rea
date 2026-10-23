"""Personal, authenticated FlowSave job server. Run only behind HTTPS.
One shared secret = one trusted user. Not intended as a public download service.
"""
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from urllib.parse import urlparse
import uuid

ROOT = Path(os.environ.get('FLOWSAVE_DATA', '/data'))
TOKEN = os.environ.get('FLOWSAVE_TOKEN', '')
JOBS = {}
LOCK = threading.RLock()
SLOTS = threading.Semaphore(1)
DOMAINS = ('youtube.com', 'youtu.be', 'x.com', 'twitter.com', 'tiktok.com',
           'instagram.com', 'douyin.com', 'iesdouyin.com', 'bilibili.com', 'b23.tv')


def validate(body):
    if set(body) != {'action', 'url', 'media', 'quality'}:
        raise ValueError('Unexpected task fields; Cookies and runtime options are not accepted')
    if body['action'] not in ('probe', 'download') or body['media'] not in ('video', 'images', 'cover'):
        raise ValueError('Invalid action or media')
    if body['quality'] not in ('best', '2160', '1080', '720', 'compat', 'audio'):
        raise ValueError('Invalid quality')
    if not isinstance(body['url'], str) or len(body['url']) > 4096:
        raise ValueError('Invalid URL')
    p = urlparse(body['url'])
    host = (p.hostname or '').lower()
    if p.scheme not in ('http', 'https') or p.username or p.password or p.port not in (None, 80, 443):
        raise ValueError('Invalid destination')
    if not any(host == h or host.endswith('.' + h) for h in DOMAINS):
        raise ValueError('Only the six configured media platforms are allowed')
    return body


def terminate(job):
    process = job.get('process')
    if process is not None and process.poll() is None:
        try: os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError: pass
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: pass


def work(identity):
    with SLOTS:
        with LOCK:
            job = JOBS.get(identity)
            if not job or job.get('cancelled'): return
            job['state'] = 'running'
        folder = job['folder']
        try:
            process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('worker.py')), str(folder)],
                                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, start_new_session=True)
            with LOCK:
                job['process'] = process
                if job.get('cancelled'): terminate(job)
            process.wait(timeout=900)
            if job.get('cancelled'): return
            if process.returncode or not (folder / 'result.json').exists():
                raise ValueError('Worker failed or resource limit reached')
            result = json.loads((folder / 'result.json').read_text())
            if result.get('error'): raise ValueError(result['error'])
            files = []
            for source in result.pop('paths', []):
                path = Path(source).resolve()
                if not path.is_relative_to(folder.resolve()) or not path.is_file():
                    raise ValueError('Invalid worker output path')
                if not 0 < path.stat().st_size <= 8 * 1024**3:
                    raise ValueError('Invalid output size')
                digest = hashlib.sha256()
                with path.open('rb') as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b''): digest.update(chunk)
                files.append({'name': path.name, 'size': path.stat().st_size,
                              'sha256': digest.hexdigest(), 'path': path})
            with LOCK:
                job.update(state='done', result=result, files=files)
        except Exception as exc:
            terminate(job)
            with LOCK: job.update(state='error', error=str(exc)[:300])
        finally:
            if job.get('cancelled'): shutil.rmtree(folder, ignore_errors=True)


def remove(identity):
    with LOCK:
        job = JOBS.pop(identity, None)
        if job: job['cancelled'] = True
    if job:
        terminate(job)
        shutil.rmtree(job['folder'], ignore_errors=True)


def sweep():
    while True:
        time.sleep(60)
        with LOCK:
            old = [key for key, job in JOBS.items() if time.time() - job['created'] > 3600]
        for key in old: remove(key)


class Handler(BaseHTTPRequestHandler):
    server_version = 'FlowSave/0.3'
    def setup(self):
        super().setup()
        self.connection.settimeout(30)
    def log_message(self, *args): pass
    def send_json(self, code, data):
        raw = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(raw)
    def authorized(self):
        supplied = self.headers.get('Authorization', '')
        if not hmac.compare_digest(supplied.encode(), ('Bearer ' + TOKEN).encode()):
            self.send_json(401, {'error': 'Unauthorized'})
            return False
        return True
    def do_POST(self):
        if not self.authorized(): return
        if self.path != '/v1/jobs': return self.send_json(404, {'error': 'Not found'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 16384: raise ValueError('Invalid request size')
            task = validate(json.loads(self.rfile.read(length)))
            with LOCK:
                if len(JOBS) >= 20 or sum(j['state'] in ('queued', 'running') for j in JOBS.values()) >= 4:
                    return self.send_json(429, {'error': 'Queue full; retry later'})
                identity = uuid.uuid4().hex
                folder = ROOT / identity
                folder.mkdir(mode=0o700, parents=True)
                (folder / 'request.json').write_text(json.dumps(task))
                JOBS[identity] = {'state': 'queued', 'folder': folder, 'created': time.time(), 'files': []}
            threading.Thread(target=work, args=(identity,), daemon=True).start()
            self.send_json(202, {'id': identity})
        except (ValueError, TypeError, KeyError) as exc:
            self.send_json(400, {'error': str(exc)[:200]})
    def do_GET(self):
        if self.path == '/health': return self.send_json(200, {'service': 'FlowSave', 'version': '0.3'})
        if not self.authorized(): return
        match = re.fullmatch(r'/v1/jobs/([a-f0-9]{32})(?:/files/(\d+))?', self.path)
        if not match: return self.send_json(404, {'error': 'Not found'})
        with LOCK: job = JOBS.get(match[1])
        if not job: return self.send_json(404, {'error': 'Expired job'})
        if match[2] is not None:
            index = int(match[2])
            if job['state'] != 'done' or index >= len(job['files']):
                return self.send_json(404, {'error': 'File unavailable'})
            entry = job['files'][index]
            try:
                with entry['path'].open('rb') as stream:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/octet-stream')
                    self.send_header('Content-Length', str(entry['size']))
                    self.send_header('Cache-Control', 'no-store')
                    self.end_headers()
                    shutil.copyfileobj(stream, self.wfile, 65536)
            except (OSError, BrokenPipeError): pass
            return
        data = {k: job[k] for k in ('state', 'result', 'error') if k in job}
        data['files'] = [{k: f[k] for k in ('name', 'size', 'sha256')} for f in job['files']]
        try: data.update(json.loads((job['folder'] / 'progress.json').read_text()))
        except (OSError, ValueError): pass
        self.send_json(200, data)
    def do_DELETE(self):
        if not self.authorized(): return
        match = re.fullmatch(r'/v1/jobs/([a-f0-9]{32})', self.path)
        if not match: return self.send_json(404, {'error': 'Not found'})
        remove(match[1])
        self.send_json(200, {'deleted': True})


if __name__ == '__main__':
    if len(TOKEN) < 32:
        raise SystemExit('Set FLOWSAVE_TOKEN to a random secret of at least 32 characters')
    ROOT.mkdir(parents=True, exist_ok=True)
    # Stale files from a previous container are removed after their retention period.
    for child in ROOT.iterdir():
        if child.is_dir() and re.fullmatch('[a-f0-9]{32}', child.name) and time.time()-child.stat().st_mtime > 3600:
            shutil.rmtree(child, ignore_errors=True)
    threading.Thread(target=sweep, daemon=True).start()
    ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
