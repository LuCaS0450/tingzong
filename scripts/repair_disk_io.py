"""Archive bloated query diagnostics before resetting them; sample real IO.

No application rows, database-wide cumulative counters or global settings are
changed. Full query text stays in .private. Ordinary samples omit query text.
"""
import argparse
import csv
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess

import requests

from cloud_backup import BIN, query
from cloud_credentials import ROOT, pg_env, read_config


REPORT = ROOT / 'artifacts/disk-io-fix-20261029.json'
SNAPSHOT_SQL = """select jsonb_build_object(
    'at',clock_timestamp(),
    'database',(select jsonb_build_object('stats_reset',stats_reset,'temp_bytes',temp_bytes,
        'temp_files',temp_files,'blks_read',blks_read,'blks_hit',blks_hit)
        from pg_stat_database where datname=current_database()),
    'query_statistics',(select jsonb_build_object('entries',count(*),
        'temp_write_bytes',sum(temp_blks_written)*8192)
        from extensions.pg_stat_statements(false)),
    'catalog_counts',jsonb_build_object('places',(select count(*) from v2.locations),
        'scenes',(select count(*) from v2.scenes),'works',(select count(*) from v2.works)),
    'active_others',(select count(*) from pg_stat_activity where datname=current_database()
        and pid<>pg_backend_pid() and backend_type='client backend' and state='active')
);"""


def snapshot(env, config):
    data = json.loads(query(SNAPSHOT_SQL, env))
    wanted = {'node_time_seconds', 'node_vmstat_pswpin', 'node_vmstat_pswpout',
              'node_memory_MemTotal_bytes', 'node_memory_MemAvailable_bytes',
              'node_memory_SwapFree_bytes', 'node_memory_SwapTotal_bytes',
              'node_disk_read_bytes_total', 'node_disk_written_bytes_total',
              'node_disk_io_time_seconds_total'}
    try:
        response = requests.get(
            'https://' + config['project_ref'] + '.supabase.co/customer/v1/privileged/metrics',
            auth=('service_role', config['service_role_key']), timeout=(5, 20))
        data['metrics_status'] = response.status_code
        if response.ok:
            values = {}
            for line in response.text.splitlines():
                if not line or line.startswith('#'):
                    continue
                name = re.split(r'[\s{]', line, 1)[0]
                if name in wanted:
                    device = re.search(r'device="([^"]+)"', line)
                    values[name + (':' + device.group(1) if device else '')] = float(line.rsplit(' ', 1)[1])
            data['metrics'] = values
    except requests.RequestException as error:
        data['metrics_error'] = type(error).__name__
    return data


def archive_statistics(env, destination):
    destination.mkdir(parents=True, exist_ok=False)
    path = destination / 'query-statistics.csv'
    sql = 'copy (select row_to_json(s) from extensions.pg_stat_statements(true) s) to stdout with (format csv);'
    with path.open('wb') as output:
        result = subprocess.run([str(BIN / 'psql.exe'), '--no-password', '-X', '-qAt',
                                 '-v', 'ON_ERROR_STOP=1'], input=sql.encode(), env=env,
                                stdout=output, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        (destination / 'archive-error.txt').write_bytes(result.stderr)
        raise RuntimeError('Diagnostic archive failed; statistics have not been reset')
    csv.field_size_limit(128 * 1024 * 1024)
    count = total = oversized = largest = 0
    with path.open(encoding='utf-8', newline='') as source:
        for row in csv.reader(source):
            entry = json.loads(row[0])
            size = len((entry.get('query') or '').encode())
            count += 1
            total += size
            largest = max(largest, size)
            oversized += size > 65536
    if not count:
        raise RuntimeError('Empty diagnostic archive; statistics have not been reset')
    return dict(path=str(path), rows=count, query_text_bytes=total,
                oversized_entries=oversized, largest_query_bytes=largest, file_bytes=path.stat().st_size)


def save(report):
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument('--execute', action='store_true', help='Archive then clear the bloated query-performance statistics once')
    actions.add_argument('--sample', action='store_true', help='Append a read-only observation to the existing repair record')
    args = parser.parse_args()
    config = read_config()
    env = pg_env(config)
    env.update(PGAPPNAME='musicpilgrimage_disk_io_repair', PGCONNECT_TIMEOUT='12',
               PGCLIENTENCODING='UTF8', PGOPTIONS='-c statement_timeout=30000 -c lock_timeout=2000')
    report = json.loads(REPORT.read_text()) if REPORT.exists() else {'project_ref': config['project_ref'], 'samples': []}
    if report['project_ref'] != config['project_ref']:
        raise RuntimeError('Unexpected project in repair record')
    if args.execute:
        if report.get('reset_at'):
            raise RuntimeError('This repair already ran; use --sample instead of resetting statistics again')
        report['samples'].append(snapshot(env, config))
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        report['diagnostic_archive'] = archive_statistics(env, ROOT / '.private/diagnostics' / ('disk-io-' + stamp))
        save(report)
        if report['diagnostic_archive']['query_text_bytes'] <= 2 * 1024 * 1024:
            print('Statistics are already small; no reset needed')
            return
        # Only this diagnostic extension is reset. pg_stat_database and all
        # business tables remain intact, so before/after counters stay useful.
        report['reset_at'] = query('select extensions.pg_stat_statements_reset();', env).strip()
        save(report)
        report['post_reset_text'] = json.loads(query("select jsonb_build_object('entries',count(*),"
            "'total_bytes',sum(octet_length(query)),'largest_bytes',max(octet_length(query))) "
            "from extensions.pg_stat_statements(true);", env))
        after = snapshot(env, config)
        if after['catalog_counts'] != report['samples'][0]['catalog_counts']:
            raise RuntimeError('Catalog changed during repair; inspect concurrent activity')
        report['samples'].append(after)
        report['catalog_rpc_ok'] = query("select jsonb_typeof(v2.catalog())='object';", env).strip() == 't'
        save(report)
        print(json.dumps({k: report[k] for k in ('diagnostic_archive', 'reset_at', 'post_reset_text', 'catalog_rpc_ok')}, ensure_ascii=False))
    else:
        sample = snapshot(env, config)
        if args.sample:
            report['samples'].append(sample)
            save(report)
        print(json.dumps(sample, ensure_ascii=False))


if __name__ == '__main__':
    main()
