#!/usr/bin/env python3
"""Verify packaged JNI libraries match source inputs and the declared JNI API."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
core = root.parent / 'clash-rs'
if not (core / 'clash-lib/Cargo.toml').is_file():
    sys.exit('Kernel checkout is missing at ../clash-rs. For CI, run python3 scripts/checkout-core.py.')

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def inputs():
    files = []
    for base in (root / 'native', core):
        for directory, names, filenames in os.walk(base):
            names[:] = sorted(name for name in names if name not in {'.git', 'target', 'node_modules', 'build', 'dist', '.gradle', '.android-sdk'})
            for name in sorted(filenames):
                if name.endswith(('.rs', '.proto')) or name in {'Cargo.toml', 'Cargo.lock', 'rust-toolchain.toml'}:
                    files.append(Path(directory) / name)
    files += [root / 'app/src/main/java/com/yk/clash/NativeCore.kt', root / 'mise.toml', root / 'core-version.json', root / '.cargo/config.toml', core / '.cargo/config.toml', root / 'scripts/checkout-core.py', root / 'scripts/build-core.sh', Path(__file__).resolve()]
    h = hashlib.sha256()
    for path in sorted(set(files)):
        if path.is_file():
            label = 'clash-rs/' + str(path.relative_to(core)) if path.is_relative_to(core) else 'app/' + str(path.relative_to(root))
            h.update(label.encode()); h.update(b'\0'); h.update(digest(path).encode())
    return h.hexdigest()

mode, *args = sys.argv[1:]
if mode == '--inputs':
    print(inputs())
    sys.exit(0)
current = inputs()
if mode == '--record':
    expected, *args = args
    if current != expected:
        sys.exit('Native inputs changed during compilation; rebuild the core.')
for abi in args:
    if abi not in {'arm64-v8a', 'x86_64'}:
        sys.exit('Unsupported ABI')
    directory = root / 'app/src/main/jniLibs' / abi
    stamp = root / 'native/target/android-jni' / abi / 'build.json'
    try:
        record = {'inputs': current, 'libraries': {name: digest(directory / name) for name in ('libykcore.so', 'libc++_shared.so')}}
        if mode == '--verify':
            if json.loads(stamp.read_text()) != record:
                raise ValueError('source or library changed')
        elif mode == '--record':
            stamp.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(mode='w', dir=stamp.parent, delete=False) as out:
                json.dump(record, out); out.flush(); os.fsync(out.fileno()); temporary = out.name
            os.replace(temporary, stamp)
        else:
            sys.exit('Unknown mode')
    except (OSError, ValueError) as error:
        sys.exit(f'JNI libraries for {abi} are missing or stale. Run mise exec -- bash scripts/build-core.sh {abi}. ({error})')
