#!/usr/bin/env python3
"""Download and atomically refresh the built-in MRS resources."""
import concurrent.futures
import datetime
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import urllib.request

root = Path(__file__).resolve().parents[1]
directory = root / 'app/src/main/assets/defaults/rules'
defaults = root / 'app/src/main/assets/defaults/overrides.yaml'
providers = json.loads(next(line.split(':', 1)[1] for line in defaults.read_text().splitlines() if line.startswith('rule-providers:')))
fallbacks = {'geosite-cn': 'https://github.com/DustinWin/ruleset_geodata/releases/download/mihomo-ruleset/cn.mrs'}
source = root.parent / 'Openwrt-nikki-rs/nikki-rs/files/rule'
old_index = json.loads((directory / 'index.json').read_text()) if (directory / 'index.json').exists() else {}

class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if not newurl.startswith('https://'):
            raise ValueError('Resource redirected away from HTTPS')
        return super().redirect_request(request, fp, code, msg, headers, newurl)

def fetch(item, staging):
    name, provider = item
    urls = list(dict.fromkeys([provider['url']] + ([fallbacks[name]] if name in fallbacks else [])))
    for url in urls:
        for attempt in range(2):
            try:
                opener = urllib.request.build_opener(HttpsRedirect())
                request = urllib.request.Request(url, headers={'User-Agent': 'YKClash-resource-updater'})
                with opener.open(request, timeout=45) as response:
                    data = response.read(64 * 1024 * 1024 + 1)
                if not data or len(data) > 64 * 1024 * 1024:
                    raise ValueError('Invalid MRS file size')
                file = Path(staging) / (name + '.mrs')
                file.write_bytes(data)
                decoded = subprocess.run(['zstd', '-dc', str(file)], check=True, capture_output=True).stdout
                behavior = 1 if provider['behavior'] == 'ipcidr' else 0
                if len(decoded) < 22 or decoded[:4] != b'MRS\x01' or decoded[4] != behavior:
                    raise ValueError('MRS format or behavior mismatch')
                count, extra = struct.unpack('>qq', decoded[5:21])
                if count < 0 or extra != 0:
                    raise ValueError('Invalid MRS header')
                previous = set(old_index.get(name, {}).get('previous', []))
                for old in (source / (name + '.mrs'), directory / (name + '.mrs')):
                    if old.exists(): previous.add(hashlib.sha256(old.read_bytes()).hexdigest())
                return name, file, {'url': url, 'sha256': hashlib.sha256(data).hexdigest(), 'count': count, 'previous': sorted(previous), 'updatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}
            except Exception:
                if attempt == 0: time.sleep(1)
    raise RuntimeError(f'Could not update {name}')

def atomic_write(target, data):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix='.publish-', delete=False) as output:
            temporary = Path(output.name)
            output.write(data); output.flush(); os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)

with tempfile.TemporaryDirectory(prefix='yk-rules-') as staging:
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        futures = [executor.submit(fetch, item, staging) for item in providers.items()]
        downloaded = [future.result() for future in futures]
    index = {}
    for name, file, metadata in downloaded:
        atomic_write(directory / (name + '.mrs'), file.read_bytes())
        index[name] = metadata
        print(f'{name}: {metadata["count"]} rules')
    atomic_write(directory / 'index.json', (json.dumps(index, ensure_ascii=False, indent=2) + '\n').encode())
