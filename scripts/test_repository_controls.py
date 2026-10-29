"""Focused checks for public disclosure and the separate offline material index."""
import base64
import json
from pathlib import Path
import tempfile
import unittest

from check_public_repository import audit, git, path_problem, scan_content
from sync_private_materials import private_git, sync


def fixture(root):
    git(root, 'init', '--initial-branch=main')
    git(root, 'config', 'user.name', 'LuCaS0450')
    git(root, 'config', 'user.email', '147964947+LuCaS0450@users.noreply.github.com')
    git(root, 'config', 'commit.gpgsign', 'false')
    git(root, 'config', 'core.autocrlf', 'false')
    (root / '.gitignore').write_text('.private/\ncontent/\n', encoding='utf-8')
    (root / 'README.md').write_text('public source\n', encoding='utf-8')
    git(root, 'add', '.gitignore', 'README.md')
    git(root, 'commit', '-m', 'test baseline')


class RepositoryControlsTest(unittest.TestCase):
    def test_secret_in_index_cannot_be_hidden_by_editing_working_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            source = root / 'scripts/cloud_http.py'
            source.parent.mkdir()
            token = 'ghp_' + 'x' * 36
            source.write_text('token = ' + repr(token), encoding='utf-8')
            git(root, 'add', 'scripts/cloud_http.py')
            source.write_text('# token removed only from working file\n', encoding='utf-8')
            _, problems = audit(root)
            self.assertTrue(any('secret token' in problem for problem in problems))
            self.assertNotIn(token, '\n'.join(problems))

    def test_json_credential_literals_are_detected_without_echoing_values(self):
        value = 'not' + '-a-real-' + 'password'
        findings = scan_content(json.dumps({'password': value}).encode())
        self.assertTrue(findings)
        self.assertNotIn(value, repr(findings))

    def test_privileged_jwt_is_rejected_but_public_anon_role_is_not(self):
        def token(role):
            encode = lambda data: base64.urlsafe_b64encode(data).decode().rstrip('=')
            return '.'.join((encode(b'{"alg":"HS256"}'),
                             encode(json.dumps({'role': role}).encode()), 'x' * 32)).encode()
        self.assertTrue(scan_content(token('service_role')))
        self.assertFalse(scan_content(token('anon')))
        self.assertFalse(scan_content(b'eyJhbGciOiJIUzI1NiJ9.W10.' + b'x' * 32))

    def test_public_paths_exclude_materials_and_unapproved_media(self):
        self.assertIsNotNone(path_problem('.private/materials.git/config'))
        self.assertIsNotNone(path_problem('content/design/reference.png'))
        self.assertIsNotNone(path_problem('app/src/main/assets/performance/secret/song.json'))
        self.assertIsNotNone(path_problem('app/src/main/assets/performance/piano/cover.jpg'))
        self.assertIsNone(path_problem('app/src/main/assets/performance/piano/21.pcmz'))
        self.assertIsNone(path_problem('app/src/main/res/font/instrument_sans.otf'))

    def test_private_snapshot_preserves_files_excludes_secrets_and_refuses_remote(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            content = root / 'content'
            content.mkdir()
            original = b'local-only image content'
            (content / 'reference.png').write_bytes(original)
            (content / 'credential.json').write_text(
                json.dumps({'password': 'not' + '-a-real-' + 'password'}), encoding='utf-8')
            first = sync(root, 'local material baseline')
            self.assertEqual(first['files'], 1)
            self.assertEqual(len(first['credential_files_excluded']), 1)
            self.assertEqual((content / 'reference.png').read_bytes(), original)
            self.assertFalse(sync(root, 'no changes')['changed'])
            repository = root / '.private/materials.git'
            self.assertFalse(private_git(root, repository, 'remote').strip())
            hook = (repository / 'hooks/pre-push').read_text(encoding='utf-8')
            self.assertIn('exit 1', hook)
            private_git(root, repository, 'remote', 'add', 'forbidden',
                        'https://github.com/example/never-push.git')
            with self.assertRaisesRegex(RuntimeError, 'has a remote'):
                sync(root, 'must refuse')


if __name__ == '__main__':
    unittest.main()
