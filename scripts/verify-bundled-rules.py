#!/usr/bin/env python3
"""Reject an incomplete bundled-rule publication before compiling or packaging."""
import hashlib
import json
import re
from pathlib import Path
import sys

base = Path(__file__).resolve().parents[1] / 'app/src/main/assets/defaults/rules'
root = Path(__file__).resolve().parents[1]
default = (root / 'app/src/main/assets/default.yaml').read_text()
# These shipped provider mappings use a fixed two/four-space layout.
# Catch duplicate fields before publication, without adding a YAML dependency.
inside = False
provider = None
fields = set()
for line in default.splitlines():
    if line and not line.startswith((' ', '#')):
        inside = line == 'rule-providers:'
    if not inside:
        continue
    if re.match(r'^  [^ ].*:$', line):
        provider = line.strip()[:-1]
        fields = set()
    elif provider and re.match(r'^    [^ ]', line):
        field = line.strip().split(':', 1)[0]
        if field in fields:
            sys.exit(f'Duplicate field in default.yaml: rule-providers.{provider}.{field}')
        fields.add(field)
index = json.loads((base / 'index.json').read_text())
for name, metadata in index.items():
    path = base / (name + '.mrs')
    if path.parent != base or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != metadata['sha256']:
        sys.exit(f'Bundled rule {name} does not match its index. Re-run scripts/update-bundled-rules.py before building.')
