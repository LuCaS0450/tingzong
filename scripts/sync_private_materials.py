"""Snapshot excluded project materials locally; never upload or copy working files."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

from check_public_repository import ROOT, TEXT, git, scan_content, sensitive_path

DIRECTORIES = ('content', 'docs', 'scripts', 'app/src', 'app/schemas')
ROOT_SUFFIXES = {'.py', '.json', '.txt', '.cmd'}


def candidates(root):
    directories = [name for name in DIRECTORIES if (root / name).is_dir()]
    # Only these explicit material/source directories bypass VCS ignores.
    # .ignore still excludes caches, outputs and dependency directories.
    result = subprocess.run(
        ['rg', '--files', '--hidden', '--no-ignore-vcs', '-g', '!**/.git/**',
         *directories], cwd=root, capture_output=True,
    )
    if result.returncode not in (0, 1):
        raise RuntimeError('Could not enumerate material directories with rg')
    files = {name.replace('\\', '/') for name in result.stdout.decode('utf-8').splitlines()}
    files.update(path.name for path in root.iterdir() if path.is_file()
                 and path.suffix.lower() in ROOT_SUFFIXES
                 and not path.name.startswith('output_'))
    return files


def private_git(root, directory, *args, data=None):
    return git(root, '--git-dir=' + str(directory), '--work-tree=' + str(root),
               *args, data=data)


def initialize(root, directory):
    if not directory.exists():
        directory.parent.mkdir(parents=True, exist_ok=True)
        git(root, 'init', '--bare', '--initial-branch=main', str(directory))
        private_git(root, directory, 'config', 'tingzong.materialsOnly', 'true')
    marker = private_git(root, directory, 'config', '--get', 'tingzong.materialsOnly')
    if marker.strip() != b'true':
        raise RuntimeError('Existing material Git directory is not managed by this tool')
    if private_git(root, directory, 'remote').strip():
        raise RuntimeError('Local materials repository has a remote; refusing to continue')
    for key, value in {
        'core.bare': 'false', 'core.worktree': str(root),
        'core.autocrlf': 'false', 'core.safecrlf': 'false',
        'core.logallrefupdates': 'true', 'core.quotepath': 'false', 'core.longpaths': 'true',
        'status.showUntrackedFiles': 'no', 'push.default': 'nothing',
        'user.name': 'LuCaS0450',
        'user.email': '147964947+LuCaS0450@users.noreply.github.com',
        'core.hooksPath': str(directory / 'hooks'),
    }.items():
        private_git(root, directory, 'config', key, value)
    (directory / 'hooks').mkdir(exist_ok=True)
    hook = directory / 'hooks/pre-push'
    hook.write_text('#!/bin/sh\necho "Local materials must never be pushed." >&2\nexit 1\n',
                    encoding='utf-8', newline='\n')
    hook.chmod(0o755)
    (directory / 'info/exclude').write_text('*\n', encoding='utf-8')


def sync(root, message):
    directory = root / '.private/materials.git'
    public_commit = git(root, 'rev-parse', 'HEAD').decode().strip()
    public = {name.decode('utf-8') for name in git(root, 'ls-files', '-z').split(b'\0') if name}
    wanted = []
    excluded = []
    for name in sorted(candidates(root) - public):
        path = root / name
        if sensitive_path(name) or path.is_symlink():
            continue
        if path.suffix.lower() in TEXT:
            findings = scan_content(path.read_bytes())
            if findings:
                excluded.append({'path': name, 'reasons': findings})
                continue
        wanted.append(name)
    initialize(root, directory)
    previous = {name.decode('utf-8') for name in private_git(
        root, directory, 'ls-files', '-z').split(b'\0') if name}
    removed = previous - set(wanted)
    if removed:
        private_git(root, directory, 'update-index', '--force-remove', '-z', '--stdin',
                    data=b''.join(name.encode('utf-8') + b'\0' for name in sorted(removed)))
    if wanted:
        # Force is confined to this separate, offline index and this vetted file list.
        private_git(root, directory, '--literal-pathspecs', 'add', '--force',
                    '--pathspec-from-file=-', '--pathspec-file-nul',
                    data=b''.join(name.encode('utf-8') + b'\0' for name in wanted))
    changed = private_git(root, directory, 'diff', '--cached', '--name-only', '-z')
    if changed:
        private_git(root, directory, 'commit', '-m', message,
                    '-m', 'Public-Commit: ' + public_commit)
    commit = private_git(root, directory, 'rev-parse', 'HEAD').decode().strip()
    overlap = set(wanted) & public
    if overlap:
        raise RuntimeError('Public/private index overlap')
    return {
        'repository': str(directory), 'files': len(wanted),
        'commit': commit, 'public_commit': public_commit,
        'changed': bool(changed), 'remote_count': 0,
        'credential_files_excluded': excluded,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--message', required=True)
    args = parser.parse_args()
    print(json.dumps(sync(ROOT, args.message), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
