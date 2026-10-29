"""Read-only full cloud snapshot. Secrets and Auth data stay under .private.

Never restores to a remote server. Storage bytes are downloaded separately from
the database archive because PostgreSQL only stores their metadata.
"""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import argparse
import shutil
from urllib.parse import quote
from concurrent.futures import ThreadPoolExecutor
from cloud_http import request as http_request
from cloud_credentials import ROOT, read_config, pg_env
from cloud_sql_runtime import prepare_maintenance_sql

BIN = ROOT / '.tooling/postgres/bin'

def run(program, args, env, sql=None):
    # Native psql otherwise inherits the Windows console code page. Our JSON,
    # SQL input and archive diagnostics are UTF-8 in every calling script.
    env = {**env, 'PGCLIENTENCODING': 'UTF8'}
    noninteractive = ['--no-password'] if program in ('psql','pg_dump','pg_dumpall') else []
    try:
        sql_bytes = sql.encode('utf-8') if sql is not None else None
        res = subprocess.run([str(BIN / (program + '.exe')), *noninteractive, *args], input=sql_bytes,
                             stdin=subprocess.DEVNULL if sql is None else None,
                             capture_output=True, env=env,
                             timeout=900 if program in ('pg_dump', 'pg_dumpall') else 240)
        stdout_text = res.stdout.decode('utf-8', errors='replace').replace('\r\n','\n')
        stderr_text = res.stderr.decode('utf-8', errors='replace').replace('\r\n','\n')
    except subprocess.TimeoutExpired as error:
        err_out = error.stderr.decode('utf-8', errors='replace') if error.stderr else ''
        (ROOT / '.private/backup-error.txt').write_text(program + '\nTimed out; partial archive is not usable.\n' + err_out, encoding='utf-8')
        raise RuntimeError('Cloud snapshot timed out; no cloud changes were made') from None
    if res.returncode:
        # Details can contain row data. Keep them out of the terminal.
        (ROOT / '.private/backup-error.txt').write_text(program + '\n' + stderr_text, encoding='utf-8')
        raise RuntimeError(f'{program} failed (exit {res.returncode}); no cloud changes were made')
    return stdout_text

def query(sql, env):
    prepared, transformed = prepare_maintenance_sql(sql)
    output = run('psql', ['-X', '-At', '-v', 'ON_ERROR_STOP=1'], env, prepared)
    # The temporary runner returns the original DO command tag. Its creation
    # is the only extra output, and must not change callers' JSON parsing.
    return output.removeprefix('CREATE FUNCTION\n') if transformed else output

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def qi(value):
    return '"' + value.replace('"', '""') + '"'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reason', required=True,
                        help='Name the irreversible operation requiring this full snapshot; routine edits reuse existing recovery points')
    parser.add_argument('--direct',action='store_true',help='Use the verified TLS endpoint of this same project for native database export')
    parser.add_argument('--pointer-file',help='Write a task-scoped backup pointer under .private instead of the shared latest pointer')
    parser.add_argument('--application-schemas',action='store_true',help='Export all five Application/Auth/Storage/migration schemas used by the independent restore; omit platform-managed database-definition extras')
    parser.add_argument('--storage-cache',help='Reuse hash-verified immutable object bytes only when the fresh cloud object metadata exactly matches a completed private backup')
    args=parser.parse_args()
    if not args.reason.strip():
        parser.error('--reason must describe the operation that requires a backup')
    config = read_config()
    cache={};cache_dir=None
    if args.storage_cache:
        cache_dir=(ROOT/args.storage_cache).resolve()
        if not cache_dir.is_relative_to((ROOT/'.private/backups').resolve()):raise ValueError('Storage cache must be a private backup')
        cached=json.loads((cache_dir/'manifest.json').read_text(encoding='utf-8'))
        if not cached.get('media_complete') or cached['project_ref']!=config['project_ref']:raise ValueError('Complete same-project cache required')
        cache={(o['bucket'],o['name']):o for o in cached['objects']}
    env = pg_env(config)
    if args.direct:
        env.update(PGHOST='db.'+config['project_ref']+'.supabase.co',PGUSER='postgres')
    env['PGOPTIONS'] += ' -c timezone=UTC'
    destination = ROOT / '.private/backups' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    destination.mkdir(parents=True)
    snapshot_started_at = datetime.now(timezone.utc).isoformat()
    audit = {}
    statements = {
        'server': "select jsonb_build_object('version',version(),'database',current_database());",
        'extensions': "select coalesce(jsonb_agg(jsonb_build_object('name',e.extname,'schema',n.nspname,'version',e.extversion)),'[]') from pg_extension e join pg_namespace n on n.oid=e.extnamespace;",
        'tables': "select coalesce(jsonb_agg(jsonb_build_object('schema',schemaname,'table',tablename)),'[]') from pg_tables where schemaname in ('public','auth','storage','supabase_migrations','v2','detective_v4');",
        'policies': "select coalesce(jsonb_agg(to_jsonb(p)),'[]') from pg_policies p where schemaname in ('public','auth','storage','v2','detective_v4');",
        'triggers': "select coalesce(jsonb_agg(jsonb_build_object('schema',n.nspname,'table',c.relname,'name',t.tgname,'definition',pg_get_triggerdef(t.oid))),'[]') from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where not t.tgisinternal and n.nspname in ('public','auth','storage','v2','detective_v4');",
        'grants': "select coalesce(jsonb_agg(to_jsonb(p)),'[]') from information_schema.role_table_grants p where table_schema in ('public','auth','storage','v2','detective_v4');",
        'roles': "select coalesce(jsonb_agg(jsonb_build_object('name',rolname,'login',rolcanlogin,'bypassrls',rolbypassrls)),'[]') from pg_roles;",
        'objects': "select coalesce(jsonb_agg(jsonb_build_object('bucket',bucket_id,'name',name,'metadata',metadata)),'[]') from storage.objects;",
    }
    for key, sql in statements.items():
        print(f'Audit: {key}', flush=True)
        audit[key] = json.loads(query(sql, env))
    audit['table_counts'] = {}
    counts = []
    for table in audit['tables']:
        name = table['schema'] + '.' + table['table']
        counts.append(f"select jsonb_build_object('name','{name}','count',count(*)) from {qi(table['schema'])}.{qi(table['table'])}")
    count_rows = query('begin isolation level repeatable read read only;'+ ' union all '.join(counts) + ';commit;', env)
    for line in count_rows.splitlines():
        if not line.startswith('{'):continue
        row = json.loads(line)
        audit['table_counts'][row['name']] = row['count']
    (destination / 'audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Cloud structure audited; taking full database archive', flush=True)
    dump_args=['--lock-wait-timeout=30s','--format=custom','--file',str(destination/'database.dump')]
    schemas=['v2','public','auth','storage','supabase_migrations']
    if any(t['schema']=='detective_v4' for t in audit['tables']):schemas.append('detective_v4')
    if args.application_schemas:
        for schema in schemas:dump_args.extend(['--schema',schema])
    run('pg_dump',dump_args,env)
    # The archive already contains this exact schema. Extract it locally rather
    # than repeating every remote catalog round trip against a newer snapshot.
    run('pg_restore', ['--schema-only', '--file', str(destination / 'schema.sql'), str(destination / 'database.dump')], env)
    run('pg_dumpall', ['--roles-only', '--no-role-passwords', '--file', str(destination / 'roles.sql')], env)
    toc = run('pg_restore', ['--list', str(destination / 'database.dump')], env)
    (destination / 'archive-toc.txt').write_text(toc, encoding='utf-8')
    key = config.get('service_role_key')
    def download(item):
        entry = {**item, 'downloaded': False}
        if key:
            relative = Path('objects') / hashlib.sha256((item['bucket'] + '/' + item['name']).encode()).hexdigest()
            target = destination / relative
            target.parent.mkdir(exist_ok=True)
            old=cache.get((item['bucket'],item['name']))
            if old and old.get('metadata')==item.get('metadata'):
                source=(cache_dir/old['file']).resolve()
                if not source.is_relative_to(cache_dir)or sha(source)!=old['sha256']:raise ValueError('Cached immutable object hash differs')
                shutil.copyfile(source,target)
                entry.update(downloaded=True,file=str(relative),sha256=sha(target),size=target.stat().st_size,reusedVerifiedBackupBytes=True)
                return entry
            url = f"https://{config['project_ref']}.supabase.co/storage/v1/object/{quote(item['bucket'], safe='')}/{quote(item['name'], safe='/')}"
            response = http_request('GET',url,headers={'apikey':key,'Authorization':'Bearer '+key})
            if response.status_code!=200:raise RuntimeError('Storage backup read failed')
            expected=item.get('metadata',{}).get('size')
            if expected is not None and len(response.content)!=int(expected):raise RuntimeError('Storage byte count differs')
            target.write_bytes(response.content)
            entry.update(downloaded=True, file=str(relative), sha256=sha(target), size=target.stat().st_size)
        return entry
    print('SQL archive saved; downloading Storage bytes', flush=True)
    with ThreadPoolExecutor(max_workers=4) as executor:
        objects=list(executor.map(download,audit['objects']))
    manifest = {
        'format': 2, 'project_ref': config['project_ref'], 'created_at': snapshot_started_at,
        'reason': args.reason.strip(),
        'completed_at': datetime.now(timezone.utc).isoformat(),
        'database_complete': True, 'auth_included': True, 'media_complete': all(x['downloaded'] for x in objects),
        'restore_verified': False, 'objects': objects,
        'included_schemas': schemas if args.application_schemas else 'all',
        'files': {str(p.relative_to(destination)): {'sha256': sha(p), 'size': p.stat().st_size} for p in destination.rglob('*') if p.is_file()},
    }
    (destination / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    pointer = (ROOT / args.pointer_file).resolve() if args.pointer_file else ROOT / '.private/latest-backup.txt'
    if not pointer.is_relative_to((ROOT / '.private').resolve()):
        raise RuntimeError('Backup pointer must stay under the private directory')
    pointer.parent.mkdir(parents=True,exist_ok=True)
    pointer.write_text(str(destination), encoding='utf-8')
    print(f"Database/Auth backup saved; Storage objects={len(objects)}, media complete={manifest['media_complete']}", flush=True)
    print(f"Extensions: {[(e['name'], e['schema']) for e in audit['extensions']]}")
    print(f"Application/Auth/Storage table counts: {audit['table_counts']}")
    print(f"Private backup: {destination}")

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # No exception repr/traceback: credentials and private rows stay local.
        print(f'Backup incomplete: {type(error).__name__}. Check local configuration or connectivity; deployment remains blocked.', file=sys.stderr)
        sys.exit(1)
