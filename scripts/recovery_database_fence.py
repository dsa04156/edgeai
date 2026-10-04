"""Fence new connections to one identified source database and confirm existing backends exit."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
import time
import uuid

from postgres_backup import Blocked, Postgres, ROOT, identifier, literal, private_file


def state(pg, database):
    raw = pg.sql("SELECT json_build_object('oid',oid::text,'template',datistemplate,"
        "'ownerMatches',datdba=(SELECT oid FROM pg_roles WHERE rolname=current_user),"
        "'connectionsAllowed',datallowconn,'marker',shobj_description(oid,'pg_database')) "
        "FROM pg_database WHERE datname="+literal(database), 'postgres')
    if not raw:
        raise ValueError('Source database no longer exists')
    return json.loads(raw)


def validate(value, oid, marker):
    if value['oid'] != oid or value['template'] or not value['ownerMatches']:
        raise ValueError('Source database identity or ownership differs')
    if value['marker'] not in (None, marker):
        raise ValueError('Existing database comment or another recovery operation is preserved')
    if value['connectionsAllowed'] != (value['marker'] is None):
        raise ValueError('Database admission state differs from the recorded recovery operation')


def fence(pg, database, oid, operation, timeout, report):
    identifier(database)
    if not (database == 'edgeai' or database.startswith('edgeai_')) or database.startswith('edgeai_restore_'):
        raise ValueError('Only an explicitly identified original EdgeAI database can be fenced')
    if not re.fullmatch(r'[1-9][0-9]{0,9}', oid) or int(oid) > 4294967295:
        raise ValueError('A valid PostgreSQL database OID is required')
    if str(uuid.UUID(operation)) != operation or not 5 <= timeout <= 300:
        raise ValueError('Canonical recovery UUID and timeout between 5 and 300 seconds required')
    marker = 'edgeai-recovery-fence:'+operation
    before = state(pg, database)
    validate(before, oid, marker)
    if before['connectionsAllowed']:
        if pg.sql("SELECT to_regclass('edgeai.flyway_schema_history') IS NOT NULL", database) != 't':
            raise ValueError('Source has no EdgeAI migration history')
        if pg.sql('SELECT count(*)>0 AND bool_and(success) FROM edgeai.flyway_schema_history', database) != 't':
            raise ValueError('Source migration history is incomplete')
        # ALTER holds the database lock through this transaction. Recheck the identity and
        # marker after acquiring it; a renamed/replaced target or concurrent fence rolls back.
        # Admission and the recovery marker become visible together at commit.
        pg.sql("DO $fence$ DECLARE current_oid oid; current_marker text; valid_owner boolean; "
            "BEGIN ALTER DATABASE "+identifier(database)+" ALLOW_CONNECTIONS false; "
            "SELECT oid,shobj_description(oid,'pg_database'),"
            "NOT datistemplate AND datdba=(SELECT oid FROM pg_roles WHERE rolname=current_user) "
            "INTO current_oid,current_marker,valid_owner FROM pg_database WHERE datname="+literal(database)+"; "
            "IF current_oid IS DISTINCT FROM "+oid+"::oid OR valid_owner IS DISTINCT FROM true "
            "OR (current_marker IS NOT NULL AND current_marker<>"+literal(marker)+") THEN "
            "RAISE EXCEPTION 'Recovery source identity changed'; END IF; "
            "COMMENT ON DATABASE "+identifier(database)+" IS "+literal(marker)+"; END $fence$;", 'postgres')
    after = state(pg, database)
    validate(after, oid, marker)
    if after['connectionsAllowed']:
        raise Blocked('Source connection fence was not retained')
    report['fenceRetained'] = True
    deadline = time.monotonic()+timeout
    terminated = set()
    while time.monotonic() < deadline:
        current = state(pg, database)
        validate(current, oid, marker)
        if current['connectionsAllowed']:
            raise Blocked('An external writer reopened source database admission')
        prepared = int(pg.sql('SELECT count(*) FROM pg_prepared_xacts WHERE database='+literal(database), 'postgres'))
        report['preparedTransactions'] = prepared
        if prepared:
            raise Blocked('Prepared transactions need an explicit recovery decision; fence remains')
        rows = json.loads(pg.sql("SELECT coalesce(json_agg(json_build_object('pid',pid,"
            "'started',backend_start::text)), '[]') FROM pg_stat_activity WHERE datid="+oid+"::oid", 'postgres'))
        report['remainingBackends'] = len(rows)
        if not rows:
            # Verify actual rejection using the same reachable server and credentials. Only
            # the expected admission error counts; other connection failures are inconclusive.
            offset = pg.log.stat().st_size
            try:
                pg.sql('SELECT 1', database)
            except RuntimeError:
                with pg.log.open('rb') as log:
                    log.seek(offset)
                    denied = ('database "'+database+'" is not currently accepting connections').encode() in log.read()
                if not denied:
                    raise Blocked('Source connection rejection could not be attributed to the admission fence')
            else:
                raise Blocked('A new connection bypassed the source database fence')
            final = state(pg, database)
            validate(final, oid, marker)
            if final['connectionsAllowed'] or pg.sql('SELECT count(*) FROM pg_stat_activity WHERE datid='+oid+'::oid', 'postgres') != '0':
                raise Blocked('Source database changed during final verification')
            report.update(status='SOURCE_DATABASE_CONNECTIONS_FENCED', newConnectionRejected=True,
                terminatedBackends=len(terminated), finishedAt=datetime.now(timezone.utc).isoformat())
            return
        for row in rows:
            remaining = deadline-time.monotonic()
            if remaining <= 0:
                break
            result = pg.sql('SELECT pg_terminate_backend(pid,'+str(max(1,min(1000,int(remaining*1000))))+') '
                'FROM pg_stat_activity WHERE datid='+oid+'::oid AND pid='+str(int(row['pid']))+
                ' AND backend_start='+literal(row['started'])+'::timestamptz', 'postgres')
            if result == 't':
                terminated.add((row['pid'],row['started']))
        time.sleep(.05)
    raise Blocked('Existing source backends have not all exited; admission fence remains')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport', choices=['native','compose'], default='native')
    parser.add_argument('--pg-bin', type=Path)
    parser.add_argument('--database', required=True)
    parser.add_argument('--database-oid', required=True)
    parser.add_argument('--recovery-id', required=True)
    parser.add_argument('--timeout', type=int, default=60)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    report = {'scope':'postgresql-source-connection-fence','status':'RUNNING',
        'database':args.database,'databaseOid':args.database_oid,'recoveryId':args.recovery_id,
        'fenceRetained':False,'activated':False,'globalQuiescenceProven':False}
    pg = None
    code = 1
    try:
        pg = Postgres(args.transport,args.pg_bin,diagnostics=args.output/'diagnostics')
        pg.env['PGAPPNAME'] = 'edgeai-recovery-fence'
        pg.env['PGOPTIONS'] += ' -c statement_timeout=5000 -c lock_timeout=1000'
        fence(pg,args.database,args.database_oid,args.recovery_id,args.timeout,report)
        code = 0
    except Exception as error:
        report.update(status='BLOCKED' if isinstance(error,Blocked) else 'FAIL',failureType=type(error).__name__)
        code = 2 if isinstance(error,Blocked) else 1
    finally:
        if pg is not None:
            try:
                observed = state(pg,args.database)
                report['fenceRetained'] = observed['oid'] == args.database_oid and observed['marker'] == 'edgeai-recovery-fence:'+args.recovery_id and not observed['connectionsAllowed']
            except Exception:
                report['fenceStateUnconfirmed'] = True
            if code == 0 and (not report['fenceRetained'] or report.get('fenceStateUnconfirmed')):
                report.update(status='BLOCKED',failureType='FinalFenceStateUnconfirmed')
                code = 2
        with private_file(args.output/'fence-report.json','w') as out:
            json.dump(report,out,indent=2); out.write('\n')
    print(report['status']+': source database connections; private report retained')
    return code


if __name__ == '__main__':
    sys.exit(main())
