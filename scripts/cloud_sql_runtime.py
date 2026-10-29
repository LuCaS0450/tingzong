"""Keep large maintenance DO payloads out of pg_stat_statements query text.

Postgres does not normalize literals inside top-level utility statements such
as DO. Calling the same block through an invoker-only temporary function makes
the top-level statement a normalizable SELECT. Transactions and exceptions
remain in the caller's session; no business function or global setting changes.
"""
import re


LARGE_DO_BYTES = 64 * 1024
_DOLLAR_QUOTE = re.compile(r"\$(?:[A-Za-z_][A-Za-z_0-9]*)?\$")
_WORD = re.compile(r"[A-Za-z_][A-Za-z_0-9]*")
_RUNNER = """create function pg_temp.musicpilgrimage_run_do(statement text)
returns text language plpgsql as $mp_runner$
begin
    execute statement;
    return 'DO';
end
$mp_runner$;
"""


def _statements(sql):
    """Yield (statement, first keyword), respecting PostgreSQL quoted bodies."""
    start = pos = 0
    first = None
    size = len(sql)
    while pos < size:
        char = sql[pos]
        if char.isspace():
            pos += 1
        elif sql.startswith('--', pos):
            end = sql.find('\n', pos + 2)
            pos = size if end < 0 else end + 1
        elif sql.startswith('/*', pos):
            depth = 1
            pos += 2
            while pos < size and depth:
                if sql.startswith('/*', pos):
                    depth += 1
                    pos += 2
                elif sql.startswith('*/', pos):
                    depth -= 1
                    pos += 2
                else:
                    pos += 1
            if depth:
                raise ValueError('Unclosed SQL comment; no query submitted')
        elif char in ("'", '"'):
            if first is None:
                first = ''
            quote = char
            escaped = (quote == "'" and pos > 0 and sql[pos - 1] in 'eE'
                       and (pos < 2 or not (sql[pos - 2].isalnum() or sql[pos - 2] in '_$')))
            pos += 1
            while pos < size:
                if escaped and sql[pos] == '\\':
                    pos += 2
                elif sql[pos] == quote:
                    if pos + 1 < size and sql[pos + 1] == quote:
                        pos += 2
                    else:
                        pos += 1
                        break
                else:
                    pos += 1
            else:
                raise ValueError('Unclosed SQL quote; no query submitted')
        elif (char == '$' and (pos == 0 or not (sql[pos - 1].isalnum() or sql[pos - 1] in '_$'))
              and (match := _DOLLAR_QUOTE.match(sql, pos))):
            if first is None:
                first = ''
            delimiter = match.group()
            end = sql.find(delimiter, match.end())
            if end < 0:
                raise ValueError('Unclosed SQL dollar quote; no query submitted')
            pos = end + len(delimiter)
        elif char == ';':
            yield sql[start:pos + 1], first
            start = pos = pos + 1
            first = None
        elif match := _WORD.match(sql, pos):
            if first is None:
                first = match.group().lower()
            pos = match.end()
        else:
            # A statement starting with a quoted expression or psql directive
            # must never be mistaken for a later DO keyword.
            if first is None:
                first = ''
            pos += 1
    if start < size:
        yield sql[start:], first


def prepare_maintenance_sql(sql):
    """Return (SQL, transformed), leaving ordinary queries byte-for-byte intact."""
    if len(sql.encode('utf-8')) <= LARGE_DO_BYTES:
        return sql, False
    parts = []
    changed = False
    for statement, first in _statements(sql):
        if first != 'do' or len(statement.encode('utf-8')) <= LARGE_DO_BYTES:
            parts.append(statement)
            continue
        delimiter = '$mp_payload$'
        while delimiter in statement:
            delimiter = delimiter[:-1] + '_$'
        parts.append('\nselect pg_temp.musicpilgrimage_run_do('
                     + delimiter + statement + delimiter + ');\n')
        changed = True
    return (_RUNNER + ''.join(parts), True) if changed else (sql, False)
