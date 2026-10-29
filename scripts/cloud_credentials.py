"""Read local cloud credentials without logging them; protect secrets with Windows DPAPI."""
from pathlib import Path
import ctypes
from ctypes import wintypes
import json
import os
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / '.private/cloud.local.json'

class Blob(ctypes.Structure):
    _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]

def crypt(value: bytes, decrypt: bool = False) -> bytes:
    if os.name != 'nt':
        raise RuntimeError('Credential protection requires Windows on this workstation')
    buffer = (ctypes.c_ubyte * len(value)).from_buffer_copy(value)
    source, target = Blob(len(value), buffer), Blob()
    method = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    if not method(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise RuntimeError('Windows credential protection failed')
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        ctypes.windll.kernel32.LocalFree(target.data)

def read_config():
    config = json.loads(CONFIG.read_text(encoding='utf-8-sig'))
    updated = dict(config)
    needs_protection = False
    for field in ('password', 'service_role_key', 'access_token'):
        if config.get(field):
            updated[field + '_protected'] = crypt(config[field].encode()).hex()
            updated[field] = ''
            needs_protection = True
        elif config.get(field + '_protected'):
            config[field] = crypt(bytes.fromhex(config[field + '_protected']), True).decode()
    # Decrypting in memory is a read. Do not rewrite the shared configuration on every HTTP call.
    if needs_protection:
        with tempfile.NamedTemporaryFile(mode='w',encoding='utf-8',dir=CONFIG.parent,
                                         prefix='cloud-protect-',suffix='.tmp',delete=False) as stream:
            temp=Path(stream.name)
            stream.write(json.dumps(updated, indent=2) + '\n')
            stream.flush();os.fsync(stream.fileno())
        try:temp.replace(CONFIG)
        finally:temp.unlink(missing_ok=True)
    if config.get('project_ref') != 'bbzpffucjicfnpfzeoqq':
        raise RuntimeError('Unexpected cloud project')
    if not config.get('password'):
        raise RuntimeError('Fill the password field in .private/cloud.local.json locally, never in chat')
    return config

def pg_env(config):
    certificate = (ROOT / config['sslrootcert']).resolve()
    if not certificate.is_file():
        raise RuntimeError('Download the project CA certificate before connecting')
    return {
        **os.environ, 'PGHOST': config['host'], 'PGPORT': str(config['port']),
        'PGDATABASE': config['database'], 'PGUSER': config['user'],
        'PGPASSWORD': config['password'], 'PGSSLMODE': 'verify-full',
        'PGSSLROOTCERT': str(certificate), 'PGCONNECT_TIMEOUT': '20',
        'PGOPTIONS': '-c statement_timeout=180000',
    }
