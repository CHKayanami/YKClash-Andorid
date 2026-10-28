#!/usr/bin/env python3
"""Prepare the pinned sibling kernel checkout for CI, without resetting local work."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
target = root.parent / 'clash-rs'
pin = json.loads((root / 'core-version.json').read_text())
revision = pin['revision']
if not re.fullmatch(r'[0-9a-f]{40}', revision):
    sys.exit('core-version.json must specify a full commit hash.')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check', action='store_true', help='Verify the checkout without changing it.')
args = parser.parse_args()

def git(*arguments, capture=False):
    result = subprocess.run(['git', '-C', str(target), *arguments], check=True,
                            text=True, capture_output=capture)
    return result.stdout.strip() if capture else None

if args.check:
    if not (target / 'clash-lib/Cargo.toml').is_file():
        sys.exit('Missing ../clash-rs. Run python3 scripts/checkout-core.py before the CI build.')
    if git('rev-parse', 'HEAD', capture=True) != revision:
        sys.exit('Kernel checkout differs from core-version.json.')
    if git('status', '--porcelain', capture=True):
        sys.exit('CI requires a clean kernel checkout.')
else:
    if target.exists():
        if not (target / '.git').exists():
            sys.exit('Refusing to modify a non-Git directory at ../clash-rs.')
        if git('status', '--porcelain', capture=True):
            sys.exit('Kernel has local changes; refusing to replace them.')
        if git('remote', 'get-url', 'origin', capture=True) != pin['repository']:
            sys.exit('Kernel origin differs from the pin; use this checkout helper only in a clean CI workspace.')
    else:
        subprocess.run(['git', 'init', str(target)], check=True)
        git('remote', 'add', 'origin', pin['repository'])
    git('fetch', '--depth=1', 'origin', revision)
    git('checkout', '--detach', revision)
    if git('rev-parse', 'HEAD', capture=True) != revision:
        sys.exit('Kernel checkout did not resolve to the pinned commit.')
print(f'Kernel pinned at {revision}')
