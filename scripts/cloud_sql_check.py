"""Validate V2 rules on real cloud PostGIS with transient rollback-only actors.

This is not an Auth/Storage HTTP integration substitute. The transaction always
ends with ROLLBACK and never signs up an actual testing account or sends emails.
"""
from cloud_credentials import read_config, pg_env
from cloud_backup import ROOT, BIN
import re
import subprocess
import sys

config=read_config()
env={**pg_env(config), 'PGCLIENTENCODING': 'UTF8'}
source=(ROOT / 'supabase/v2/tests/rules.sql').read_text(encoding='utf-8')
if not re.search(r'(?im)^rollback;\s*$',source):
    raise RuntimeError('Rules must end in rollback')
result=subprocess.run([str(BIN / 'psql.exe'),'-X','--no-password','-v','ON_ERROR_STOP=1'],input=source.encode('utf-8'),
                      env=env,capture_output=True,timeout=240)
stdout=result.stdout.decode('utf-8', errors='replace')
stderr=result.stderr.decode('utf-8', errors='replace')
# Test-only values and assertion labels; no production user rows are selected.
log=stdout+stderr
(ROOT / 'artifacts/v2-live-sql-rules.log').write_text(log,encoding='utf-8')
passed=log.count('PASS:')
print(f'Real cloud PostGIS: assertions passed={passed}; exit={result.returncode}')
sys.exit(result.returncode)
