#!/usr/bin/env python3
"""Generate tag-derived Android versions and filtered release notes from Git history."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

TAG = re.compile(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)[.-]?([1-9]\d{0,2}))?')
IGNORED_TYPES = {'ci', 'doc', 'docs', 'build', 'chore', 'test', 'style', 'refactor'}
IGNORED_SCOPES = {'ci', 'doc', 'docs', 'readme', 'workflow', 'workflows', 'actions'}
CONVENTIONAL = re.compile(r'(?P<type>[a-zA-Z-]+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<message>.+)')
GROUPS = [('feat', '新增功能'), ('fix', '问题修复'), ('perf', '性能优化'), ('other', '其他更新')]


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def version(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError('Tag must be vMAJOR.MINOR.PATCH, optionally suffixed with -alpha.N, -beta.N or -rc.N.')
    major, minor, patch = map(int, match.group(1, 2, 3))
    stage, serial = match.group(4, 5)
    serial = int(serial or 0)
    if major > 209 or minor > 99 or patch > 99 or serial > 299:
        raise ValueError('Tag exceeds Android version ranges: major <= 209, minor/patch <= 99, prerelease number <= 299.')
    # Reserve three ordered prerelease ranges, followed by the stable release.
    rank = {None: 3, 'alpha': 0, 'beta': 1, 'rc': 2}[stage]
    slot = 999 if stage is None else rank * 300 + serial
    code = ((major * 100 + minor) * 100 + patch) * 1000 + slot
    return (major, minor, patch, rank, serial), code, stage is not None


def documentation_only(commit):
    files = git('diff-tree', '--root', '--no-commit-id', '--name-only', '-r', commit).splitlines()
    return bool(files) and all(
        file.startswith(('.github/', 'docs/', 'artwork/'))
        or Path(file).name.upper().startswith(('README', 'CHANGELOG', 'LICENSE', 'CONTRIBUTING'))
        or file in {'.gitignore', '.gitattributes', 'Brewfile'}
        for file in files
    )


def escape(text):
    return re.sub(r'([\\`*_{}\[\]<>])', r'\\\1', text.replace('\t', ' '))


def generate(tag, repository, output):
    current_key, code, prerelease = version(tag)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Expected repository in owner/name form.')
    commit = git('rev-parse', '--verify', f'refs/tags/{tag}^{{commit}}')
    if git('rev-parse', 'HEAD') != commit:
        raise ValueError('Checkout HEAD must match the release tag.')
    candidates = []
    for candidate in git('tag', '--merged', commit).splitlines():
        try:
            key, _, is_prerelease = version(candidate)
        except ValueError:
            continue
        if key < current_key and (prerelease or not is_prerelease):
            candidates.append((key, candidate))
    previous = max(candidates)[1] if candidates else None
    revision_range = f'{previous}..{commit}' if previous else commit
    history = git('log', '--no-merges', '--reverse', '--format=%H%x09%s', revision_range)
    groups = {key: [] for key, _ in GROUPS}
    included = []
    seen = set()
    for line in history.splitlines():
        sha, subject = line.split('\t', 1)
        conventional = CONVENTIONAL.fullmatch(subject)
        category = 'other'
        message = subject
        if conventional:
            kind = conventional['type'].lower()
            scope = (conventional['scope'] or '').lower()
            if kind in IGNORED_TYPES or scope in IGNORED_SCOPES:
                continue
            category = kind if kind in groups else 'other'
            message = conventional['message']
            if conventional['scope']:
                message = f"{conventional['scope']}：{message}"
            if conventional['breaking']:
                message = '破坏性变更：' + message
        elif re.match(r'^(?:ci|docs?|chore|build|test|style|refactor)(?:\b|\()', subject, re.I):
            continue
        if documentation_only(sha) or subject.casefold() in seen:
            continue
        seen.add(subject.casefold())
        groups[category].append(f'- {escape(message)} ([{sha[:7]}](https://github.com/{repository}/commit/{sha}))')
        included.append(sha)
    notes = []
    for category, label in GROUPS:
        if groups[category]:
            notes.extend([f'## {label}', '', *groups[category], ''])
    if not included:
        notes.extend(['本次没有面向用户的功能或修复变更。', ''])
    notes.extend(['## 下载', '', '- `arm64-v8a`：大多数 Android 手机。',
                  '- `x86_64`：x86_64 设备及模拟器。', '- 系统要求：Android 13 及以上。', ''])
    if previous:
        notes.extend([f'[完整变更](https://github.com/{repository}/compare/{previous}...{tag})', ''])
    info = {'tag': tag, 'commit': commit, 'version_name': tag[1:], 'version_code': code,
            'prerelease': prerelease, 'previous_tag': previous, 'commits': included}
    output.mkdir(parents=True, exist_ok=True)
    (output / 'release-notes.md').write_text('\n'.join(notes))
    (output / 'release-info.json').write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n')
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--repository', default=os.environ.get('GITHUB_REPOSITORY'))
    parser.add_argument('--output', type=Path, default=Path('dist/release-metadata'))
    args = parser.parse_args()
    if not args.repository:
        parser.error('--repository or GITHUB_REPOSITORY is required')
    try:
        info = generate(args.tag, args.repository, args.output)
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'{error}\n')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            for key in ['version_name', 'version_code', 'prerelease']:
                value = str(info[key]).lower() if isinstance(info[key], bool) else info[key]
                stream.write(f'{key}={value}\n')
    print(f"Release {info['tag']}: versionCode={info['version_code']}, {len(info['commits'])} included commits")


if __name__ == '__main__':
    main()
