"""Regression checks for maintenance SQL parsing, atomicity and statistics size."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

from cloud_sql_runtime import LARGE_DO_BYTES, prepare_maintenance_sql


def block(body):
    padding = '/* ; do $$ nested-looking SQL $mp_payload$ 中文 */\n' * 2300
    return 'do $io_test$ begin\n' + padding + body + '\nend $io_test$;'


class SqlPreparationTests(unittest.TestCase):
    def test_small_and_non_do_statements_are_unchanged(self):
        for sql in ('select 1;', 'do $$ begin null; end $$;',
                    "select '" + 'x' * LARGE_DO_BYTES + "';",
                    'create function example() returns void language plpgsql as $outer$ begin\n'
                    + block('null;') + '\nend $outer$;'):
            self.assertEqual(prepare_maintenance_sql(sql), (sql, False))

    def test_quotes_comments_and_multiple_blocks_preserve_boundaries(self):
        before = "begin; select E'escaped\\\'; semicolon'; /* outer /* inner; */ comment */\n"
        sql = before + block("perform ';';") + '\n' + block('null;') + '\nrollback;'
        prepared, changed = prepare_maintenance_sql(sql)
        self.assertTrue(changed)
        self.assertTrue(before.split('/*')[0].rstrip() in prepared, 'preceding statements changed')
        self.assertEqual(prepared.count('select pg_temp.musicpilgrimage_run_do('), 2)
        self.assertTrue('$mp_payload_$' in prepared, 'payload delimiter was not escaped')
        self.assertTrue(prepared.endswith('rollback;'))

    def test_malformed_large_input_fails_before_submission(self):
        for suffix in ("'unfinished", '/* unfinished', '$tag$unfinished'):
            with self.assertRaises(ValueError):
                prepare_maintenance_sql(' ' * LARGE_DO_BYTES + suffix)

    def test_full_backup_requires_an_explicit_reason(self):
        script = Path(__file__).with_name('cloud_backup.py')
        for arguments in ([], ['--reason', '   ']):
            result = subprocess.run([sys.executable, str(script), *arguments],
                                    capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertTrue(b'--reason' in result.stderr)


@unittest.skipUnless(os.environ.get('MP_IO_TEST_PORT'), 'isolated local Postgres required')
class SqlDatabaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from cloud_backup import BIN
        cls.psql = str(BIN / 'psql.exe')
        cls.env = {**os.environ, 'PGHOST': '127.0.0.1', 'PGPORT': os.environ['MP_IO_TEST_PORT'],
                   'PGUSER': 'postgres', 'PGDATABASE': 'postgres', 'PGPASSWORD': '',
                   'PGSSLMODE': 'disable', 'PGCLIENTENCODING': 'UTF8',
                   'PGOPTIONS': '-c statement_timeout=15000', 'PGCONNECT_TIMEOUT': '5'}
        cls.raw('create extension if not exists pg_stat_statements;')

    @classmethod
    def raw(cls, sql, succeeds=True):
        result = subprocess.run([cls.psql, '-X', '-At', '--no-password', '-v', 'ON_ERROR_STOP=1'],
                                input=sql.encode(), env=cls.env, capture_output=True, timeout=20)
        if succeeds and result.returncode:
            raise AssertionError(result.stderr.decode(errors='replace')[-1400:])
        if not succeeds:
            assert result.returncode != 0, 'SQL unexpectedly succeeded'
        return result.stdout.decode().replace('\r\n', '\n')

    def test_success_output_and_outer_rollback_match_original(self):
        from cloud_backup import query
        sql = ('begin; create temporary table io_values(value text);'
               + block("insert into io_values values ('first;中文');")
               + 'savepoint step;'
               + block("insert into io_values values ('second');")
               + 'rollback to step; select json_agg(value order by value) from io_values;'
               + "rollback; select to_regclass('pg_temp.io_values') is null;")
        self.assertEqual(query(sql, self.env), self.raw(sql))

    def test_exception_keeps_entire_transaction_atomic(self):
        self.raw('drop table if exists public.io_atomic_test; create table public.io_atomic_test(id int);')
        sql = ('begin;' + block('insert into public.io_atomic_test values (1);')
               + "do $$ begin raise exception 'expected test failure'; end $$; commit;")
        prepared, _ = prepare_maintenance_sql(sql)
        self.raw(prepared, succeeds=False)
        self.assertEqual(self.raw('select count(*) from public.io_atomic_test;').strip(), '0')

    def test_large_do_is_not_retained_in_query_statistics(self):
        from cloud_backup import query
        self.raw('select pg_stat_statements_reset();')
        query('begin;' + block('perform 1;') + 'rollback;', self.env)
        data = json.loads(self.raw("select json_build_object('total',sum(octet_length(query)),"
                                   "'largest',max(octet_length(query))) from pg_stat_statements;"))
        self.assertLess(data['total'], 16384)
        self.assertLess(data['largest'], 4096)


if __name__ == '__main__':
    unittest.main(verbosity=2)
