"""Rehearse a cloud archive only in a new loopback PostgreSQL database.

Managed Supabase control-plane schemas are retained in the archive but are not
started here. This validates SQL/Auth rows/RLS/Storage metadata, not GoTrue or
the Storage HTTP service. No connection parameters from cloud.local.json are used.
"""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import argparse
from cloud_backup import ROOT, BIN, qi, sha

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--port',type=int,choices=(55439,55447),default=55439)
    parser.add_argument('--backup',help='Exact task-scoped backup directory; do not use the shared latest pointer')
    args=parser.parse_args()
    backup = Path(args.backup).resolve() if args.backup else Path((ROOT / '.private/latest-backup.txt').read_text(encoding='utf-8')).resolve()
    if not backup.is_relative_to((ROOT / '.private/backups').resolve()):
        raise RuntimeError('Backup must be inside the private backup directory')
    manifest = json.loads((backup / 'manifest.json').read_text(encoding='utf-8'))
    audit = json.loads((backup / 'audit.json').read_text(encoding='utf-8'))
    for name, info in manifest['files'].items():
        if sha(backup / name) != info['sha256']:
            raise RuntimeError('Backup hash mismatch')
    database = 'pilgrimage_restore_' + datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
    # Explicit fixed loopback connection; inherited cloud PG variables cannot redirect this.
    env = {k:v for k,v in os.environ.items() if not k.startswith('PG')}
    env.update(PGHOST='127.0.0.1', PGPORT=str(args.port), PGUSER='postgres', PGDATABASE='postgres', PGCLIENTENCODING='UTF8',
               PGSSLMODE='disable', PGCONNECT_TIMEOUT='10', PGOPTIONS='-c timezone=UTC')
    def run(program, args, sql=None):
        result = subprocess.run([str(BIN / (program + '.exe')), *args], input=sql, env=env,
                                capture_output=True, text=True, encoding='utf-8')
        with (backup / 'restore-private.log').open('a', encoding='utf-8') as stream:
            stream.write(program + '\n' + result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('Local restore command failed; inspect the private restore log')
        return result.stdout.strip()
    def query(sql):
        return run('psql', ['-X','-At','-v','ON_ERROR_STOP=1'], sql)
    query(f'create database {qi(database)};')
    env['PGDATABASE'] = database
    query('create schema if not exists extensions;')
    roles = json.loads(query('select jsonb_agg(rolname) from pg_roles;'))
    for role in audit['roles']:
        if role['name'] not in roles and not role['name'].startswith('pg_'):
            # Do not restore remote LOGIN credentials or change existing cluster roles.
            query(f'create role {qi(role["name"])} nologin;')
    for extension in audit['extensions']:
        if extension['name'] in ('uuid-ossp','pgcrypto','postgis','pg_trgm','fuzzystrmatch'):
            query(f'create schema if not exists {qi(extension["schema"])};create extension {qi(extension["name"])} with schema {qi(extension["schema"])};')
    schemas = ['public','auth','storage','supabase_migrations','v2']
    if any(t['schema']=='detective_v4' for t in audit['tables']):schemas.append('detective_v4')
    # pg_restore schema filters select contained objects, not SCHEMA TOC entries.
    for schema in schemas:
        query(f'create schema if not exists {qi(schema)};')
    args = ['--dbname',database,'--no-owner','--exit-on-error','--clean','--if-exists']
    for schema in schemas:
        args += ['--schema',schema]
    args += [str(backup / 'database.dump')]
    run('pg_restore',args)
    restored = {}
    for name, count in audit['table_counts'].items():
        schema, table = name.split('.',1)
        restored[name] = int(query(f'select count(*) from {qi(schema)}.{qi(table)};'))
        if restored[name] != count:
            raise RuntimeError('Restored row count does not match the audited source')
    trigger_count = int(query("select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where not t.tgisinternal and n.nspname in ('public','auth','storage','v2','detective_v4');"))
    policy_count = int(query("select count(*) from pg_policies where schemaname in ('public','auth','storage','v2','detective_v4');"))
    if trigger_count != len(audit['triggers']) or policy_count != len(audit['policies']):
        raise RuntimeError('Restored trigger/policy count mismatch')
    report = {'database':database,'loopback_only':True,'table_counts':restored,
              'triggers':trigger_count,'policies':policy_count,'managed_services_tested':False,
              'archive_sha256':sha(backup / 'database.dump'),'passed':True}
    (backup / 'restore-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    manifest['restore_verified'] = True
    manifest['restore_report_sha256'] = sha(backup / 'restore-report.json')
    (backup / 'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'PASS: {len(restored)} tables, {trigger_count} triggers and {policy_count} policies restored in {database}')
    print('Auth and Storage SQL metadata restored; live HTTP integration is a separate acceptance step')

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'Restore rehearsal incomplete: {type(error).__name__}; deployment remains blocked',file=sys.stderr)
        sys.exit(1)
