import json
import os
from pathlib import Path
import resource
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'android-app/app/src/main/python'))
from guard import install
import engine_v3

root = Path(sys.argv[1]).resolve()
payload = json.loads((root / 'request.json').read_text())
resource.setrlimit(resource.RLIMIT_CPU, (900, 900))
resource.setrlimit(resource.RLIMIT_AS, (3 * 1024**3, 3 * 1024**3))
resource.setrlimit(resource.RLIMIT_FSIZE, (8 * 1024**3, 8 * 1024**3))
resource.setrlimit(resource.RLIMIT_NOFILE, (256, 256))
install()

class Observer:
    last = 0
    def isCancelled(self): return False
    def onProgress(self, percent, message):
        if time.monotonic() - self.last < .5: return
        self.last = time.monotonic()
        temp = root / 'progress.tmp'
        temp.write_text(json.dumps({'progress': int(percent)}))
        temp.replace(root / 'progress.json')

# No user-supplied runtime config or Cookies are accepted by the server API.
result = engine_v3.execute(payload['action'], payload['url'], payload['media'],
                           payload['quality'], str(root / 'output'),
                           json.dumps({'network': 'direct', 'runtime_node': '/usr/bin/node'}),
                           '', '', '/usr/bin/ffmpeg', Observer())
(root / 'result.json').write_text(result)
