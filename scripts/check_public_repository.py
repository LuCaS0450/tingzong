"""Audit the actual Git index/commits without printing secret values."""
import argparse
import base64
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_FILES = {
    '.gitignore', '.gitattributes', '.ignore', 'AGENTS.md', 'README.md',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'gradlew', 'gradlew.bat', 'services.example.properties',
}
PUBLIC_DOCS = {'VERSION_CONTROL.md', 'PUBLIC_ASSETS.md', 'DISK_IO_FIX_20261029.md'}
PUBLIC_SCRIPTS = {
    'start-standalone-preview.ps1', 'cloud_credentials.py', 'cloud_http.py',
    'cloud_backup.py', 'cloud_sql_runtime.py', 'cloud_sql_check.py',
    'cloud_management.py', 'cloud_restore_verify.py', 'repair_disk_io.py',
    'test_cloud_sql_runtime.py', 'requirements-cloud.txt',
    'check_public_repository.py', 'sync_private_materials.py',
    'test_repository_controls.py',
}
MEDIA = {'.png', '.jpg', '.jpeg', '.webp', '.gif', '.svg', '.mp3', '.m4a',
         '.wav', '.flac', '.ogg', '.mp4', '.webm', '.pcmz', '.otf', '.ttf',
         '.woff', '.woff2', '.pdf'}
FONTS = {
    'app/src/main/res/font/instrument_sans.otf',
    'app/src/main/res/font/print_serif.otf',
    'app/src/main/res/font/fusion_pixel.otf',
    'app/src/main/assets/design/swiss-details/fonts/swiss-sans.woff2',
}
TEXT = {'.md', '.py', '.ps1', '.js', '.mjs', '.ts', '.kt', '.kts', '.txt',
        '.json', '.toml', '.pro', '.xml', '.sh', '.bat', '.cmd', '.properties',
        '.html', '.css', '.sql', '.h', '.hpp', '.c', '.cpp', '.yml', '.yaml'}
TOKEN = re.compile(r'gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}'
                   r'|sb_secret_[A-Za-z0-9_-]{12,}|AKIA[0-9A-Z]{16}'
                   r'|-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----')
JWT = re.compile(r'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{12,}')
LITERAL = re.compile(
    r'''\b(?:password|passwd|service_role_key|access_token|client_secret|api_key)["']?\s*[=:]\s*["']([^"'\r\n]{8,})["']''',
    re.IGNORECASE,
)


def git(root, *args, data=None):
    result = subprocess.run(['git', '-C', str(root), *args], input=data,
                            capture_output=True)
    if result.returncode:
        raise RuntimeError(f'git {args[0]} failed (exit {result.returncode})')
    return result.stdout


def sensitive_path(path):
    parts = PurePosixPath(path).parts
    low = [part.lower() for part in parts]
    if not parts or '..' in parts or PurePosixPath(path).is_absolute():
        return True
    if any(part in {'.git', '.private', '.agents', '.codex', '.aws', '.tooling',
                    '.gradle', '.kotlin', '.idea', '.cxx', 'node_modules',
                    '__pycache__', 'build', '.temp', '.branches'} for part in low):
        return True
    name = low[-1]
    return (name == 'local.properties' or '.local.' in name
            or name.startswith('.env') and name != '.env.example'
            or PurePosixPath(name).suffix in {
                '.jks', '.keystore', '.pem', '.p12', '.pfx', '.key',
                '.log', '.pyc', '.apk', '.aab', '.zip', '.hprof', '.iml',
            })


def path_problem(path):
    if sensitive_path(path):
        return 'private/config/generated path'
    parts = PurePosixPath(path).parts
    top = parts[0]
    if len(parts) == 1 and path not in PUBLIC_FILES:
        return 'unapproved root file'
    if len(parts) > 1:
        if top not in {'app', 'supabase', 'gradle', 'scripts', 'docs', '.githooks'}:
            return 'outside public scope'
        if top == 'docs' and path[5:] not in PUBLIC_DOCS:
            return 'historical document'
        if top == 'scripts' and path[8:] not in PUBLIC_SCRIPTS:
            return 'historical/unreviewed script'
        if top == '.githooks' and parts[-1] not in {'pre-commit', 'pre-push'}:
            return 'unreviewed hook'
    if path.startswith('app/src/debug/assets/'):
        return 'private preview asset'
    assets = 'app/src/main/assets/'
    if path.startswith(assets) and not path.startswith((
        assets + 'design/fonts/', assets + 'design/map-labels/',
        assets + 'design/swiss-details/', assets + 'design/swiss-world/', assets + 'performance/piano/',
    )):
        return 'unreviewed runtime asset'
    if path.startswith(assets + 'design/swiss-details/media/'):
        return 'private photograph'
    suffix = PurePosixPath(path).suffix.lower()
    if suffix in MEDIA and not (path in FONTS or
            path.startswith(assets + 'performance/piano/') and suffix == '.pcmz'):
        return 'media without approved distribution record'
    if suffix == '.jar' and path != 'gradle/wrapper/gradle-wrapper.jar':
        return 'unreviewed binary'
    return None


def scan_content(data):
    if b'\0' in data[:8192]:
        return []
    text = data.decode('utf-8-sig', errors='replace')
    hits = [(m.start(), 'secret token/private key') for m in TOKEN.finditer(text)]
    for match in JWT.finditer(text):
        try:
            part = match.group().split('.')[1]
            payload = json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))
            if isinstance(payload, dict) and payload.get('role') == 'service_role':
                hits.append((match.start(), 'privileged JWT'))
        except (ValueError, UnicodeError):
            pass
    for match in LITERAL.finditer(text):
        value = match.group(1).lower()
        if not (value.startswith(('<', '${', 'your_', 'your-', 'example'))
                or value in {'password', 'changeme', 'placeholder'}):
            hits.append((match.start(), 'credential literal; review required'))
    return sorted({(text.count('\n', 0, offset) + 1, kind) for offset, kind in hits})


def entries(root, revision=None):
    args = ('ls-tree', '-r', '-z', '--full-tree', revision) if revision else (
        'ls-files', '--stage', '-z')
    result = []
    for record in git(root, *args).split(b'\0'):
        if not record:
            continue
        meta, raw_path = record.split(b'\t', 1)
        fields = meta.split()
        mode, oid = fields[0], fields[2] if revision else fields[1]
        if not revision and fields[2] != b'0':
            raise RuntimeError('Resolve index conflicts before committing')
        result.append((raw_path.decode('utf-8'), oid, mode))
    return result


def audit(root, revision=None):
    files = entries(root, revision)
    problems = []
    text_files = []
    for path, oid, mode in files:
        reason = path_problem(path)
        if reason:
            problems.append(f'{path}: {reason}')
        if mode not in {b'100644', b'100755'}:
            problems.append(f'{path}: symlink/submodule is not approved')
        if PurePosixPath(path).suffix.lower() in TEXT or PurePosixPath(path).name in {
            'gradlew', '.gitignore', '.gitattributes', '.ignore', 'pre-commit', 'pre-push'}:
            text_files.append((path, oid))
    # Read staged objects, not working files: an unstaged edit cannot hide a staged secret.
    stream = git(root, 'cat-file', '--batch', data=b''.join(oid + b'\n' for _, oid in text_files))
    offset = 0
    for path, _ in text_files:
        end = stream.index(b'\n', offset)
        size = int(stream[offset:end].split()[-1])
        data = stream[end + 1:end + 1 + size]
        offset = end + size + 2
        problems.extend(f'{path}:{line}: {kind}' for line, kind in scan_content(data))
    if revision:
        emails = git(root, 'show', '-s', '--format=%ae%n%ce', revision).decode().splitlines()
    else:
        emails = [git(root, 'config', 'user.email').decode().strip()]
    if any(not email.endswith('@users.noreply.github.com') for email in emails):
        problems.append('commit identity: GitHub noreply email required')
    return files, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision')
    parser.add_argument('--push', action='store_true')
    args = parser.parse_args()
    revisions = [args.revision]
    if args.push:
        revisions = set()
        for line in sys.stdin:
            _, local, _, remote = line.split()
            if local == '0' * 40:
                continue
            options = [local, '--not', '--remotes']
            if remote != '0' * 40:
                exists = subprocess.run(['git', '-C', str(ROOT), 'cat-file', '-e', remote],
                                        capture_output=True).returncode == 0
                if exists:
                    options = [local, '^' + remote]
            revisions.update(git(ROOT, 'rev-list', *options).decode().splitlines())
    total = 0
    for revision in revisions:
        files, problems = audit(ROOT, revision)
        total += len(files)
        if problems:
            for problem in problems:
                print(problem, file=sys.stderr)
            return 1
    print(f'Public repository check passed: {len(revisions)} snapshot(s), {total} file entries.')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (RuntimeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
