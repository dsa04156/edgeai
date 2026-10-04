"""Retire proven-terminal Remote runtimes and their outbox commands in a quarantined restored DB."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
from recovery_remote_inventory import DATABASE_QUERY, canonical, compare

# SHARE ROW EXCLUSIVE serializes recovery writers and blocks DML/DDL while allowing inspection.
# Hash complete rows, including leases, observation timestamps and the immutable work, without
# copying nonces or work inputs into the intent. Include all rows to detect insert/delete races.
TABLES = {'flyway_schema_history': 'installed_rank', 'remote_allocation': 'id',
          'runtime_instance': 'id', 'runtime_command': 'id', 'task_attempt': 'id', 'task_result': 'id'}
GUARD_QUERY = 'SELECT jsonb_build_object(' + ','.join(
    literal(name) + ", (SELECT encode(sha256(convert_to(coalesce(string_agg("
    "encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' ORDER BY " + key +
    "),''),'UTF8')),'hex') FROM edgeai." + name + ' t)' for name, key in TABLES.items()) + ')'
CATALOG_SQL = ('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
               "SELECT value::jsonb || jsonb_build_object('retirementGuard',(" + GUARD_QUERY + ')) FROM (' +
               DATABASE_QUERY.strip().removesuffix(';') + ') snapshot(value);\nCOMMIT;')


def durable_json(path, value):
    with private_file(path, 'w') as target:
        json.dump(value, target, indent=2); target.write('\n')
        target.flush(); os.fsync(target.fileno())
    for directory in (path.parent, path.parent.parent):
        descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)


def prepare(pg, args):
    catalog = database_inventory(pg, args.database, args.restore_report, CATALOG_SQL)
    inventory = compare(catalog, args)  # Always actual TLS; no option accepts a cached provider report.
    if inventory['status'] != 'OBSERVED_REMOTE_INVENTORY':
        durable_json(args.output / 'inventory.json', inventory)
        raise Blocked('All Remote allocations and bindings must match before retirement')
    return {'formatVersion': 1, 'scope': 'restored-database-reference-remote-retirement',
            'targetDatabase': args.database, 'databaseOid': catalog['oid'], 'marker': catalog['marker'],
            'beforeGuard': catalog['retirementGuard'], 'inventory': inventory,
            'preparedAt': datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan):
    rows = [{'allocationId': row['allocationId'], 'runtimeId': row['database']['runtime_id'],
             'observation': {k:v for k,v in row['provider'].items() if k != 'executions'}}
            for row in plan['inventory']['allocations']]
    identity_check = """
IF current_database() <> {database} OR NOT EXISTS (
 SELECT FROM pg_database WHERE datname=current_database() AND oid::text={oid}
 AND shobj_description(oid,'pg_database')={marker}) THEN
 RAISE EXCEPTION 'Restored database identity changed';
END IF;
""".format(database=literal(plan['targetDatabase']), oid=literal(plan['databaseOid']), marker=literal(plan['marker']))
    body = """
DECLARE entry jsonb; observed jsonb; changed integer;
 observations integer := 0; runtimes integer := 0; commands integer := 0;
BEGIN
{identity_check}
IF ({guard_query}) IS DISTINCT FROM {before}::jsonb THEN
 RAISE EXCEPTION 'Restored database changed after live inventory';
END IF;
FOR entry IN SELECT * FROM jsonb_array_elements({rows}::jsonb) LOOP
 observed := entry->'observation';
 UPDATE edgeai.remote_allocation
 SET provider_revision=(observed->>'revision')::bigint,
     provider_state=observed->>'state', observation=entry->'observation', observed_at=transaction_timestamp()
 WHERE id=(entry->>'allocationId')::uuid AND provider_revision < (observed->>'revision')::bigint;
 GET DIAGNOSTICS changed = ROW_COUNT; observations := observations + changed;
 UPDATE edgeai.runtime_instance SET desired_state='STOPPED', observed_state='TERMINATED', updated_at=transaction_timestamp()
 WHERE id=(entry->>'runtimeId')::uuid AND (desired_state<>'STOPPED' OR observed_state<>'TERMINATED');
 GET DIAGNOSTICS changed = ROW_COUNT; runtimes := runtimes + changed;
 UPDATE edgeai.runtime_command SET completed=true, lease_owner=NULL, lease_until=NULL, updated_at=transaction_timestamp()
 WHERE runtime_id=(entry->>'runtimeId')::uuid AND (NOT completed OR lease_owner IS NOT NULL OR lease_until IS NOT NULL);
 GET DIAGNOSTICS changed = ROW_COUNT; commands := commands + changed;
 IF NOT EXISTS (SELECT FROM edgeai.remote_allocation a JOIN edgeai.runtime_instance r ON r.id=a.runtime_id
  WHERE a.id=(entry->>'allocationId')::uuid AND a.observation=entry->'observation'
  AND a.provider_revision=(observed->>'revision')::bigint AND a.provider_state=observed->>'state'
  AND r.desired_state='STOPPED' AND r.observed_state='TERMINATED'
  AND NOT EXISTS (SELECT FROM edgeai.runtime_command c WHERE c.runtime_id=r.id
   AND (NOT c.completed OR c.lease_owner IS NOT NULL OR c.lease_until IS NOT NULL))) THEN
  RAISE EXCEPTION 'Remote retirement postcondition differs';
 END IF;
END LOOP;
{identity_check}
INSERT INTO retirement_result VALUES (jsonb_build_object('observationsUpdated',observations,
 'runtimesRetired',runtimes,'commandsCompleted',commands,'afterGuard',({guard_query})));
END
""".format(identity_check=identity_check, guard_query=GUARD_QUERY, before=literal(canonical(plan['beforeGuard']).decode()),
           rows=literal(canonical(rows).decode()))
    return ('BEGIN;\nSET LOCAL lock_timeout=\'5s\';\nSET LOCAL statement_timeout=\'30s\';\n'
            'LOCK TABLE ' + ','.join('edgeai.' + table for table in TABLES) + ' IN SHARE ROW EXCLUSIVE MODE;\n'
            'CREATE TEMP TABLE retirement_result(value jsonb) ON COMMIT DROP;\nDO ' + literal(body) + ';\n'
            'SELECT value FROM retirement_result;\nCOMMIT;\n')


def apply(pg, args, plan):
    # Write and fsync the intent before any mutation. A lost COMMIT reply is never called rollback.
    durable_json(args.output / 'intent.json', plan)
    path = args.output / 'transaction.sql'
    with private_file(path, 'w') as source:
        source.write(transaction_sql(plan)); source.flush(); os.fsync(source.fileno())
    with path.open('rb') as source:
        result = json.loads(pg.call('psql', ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                                   args.database, source=source, timeout=45, reject_stderr=True))
    # Observe the committed state in a separate real connection, and recheck frozen provider history.
    after = prepare(pg, args)
    if (after['beforeGuard'] != result['afterGuard'] or after['databaseOid'] != plan['databaseOid'] or
            after['marker'] != plan['marker'] or after['inventory']['providerInventorySha256'] !=
            plan['inventory']['providerInventorySha256']):
        raise Blocked('Post-commit state changed; keep the restored database quarantined')
    report = {**{key: plan[key] for key in ('formatVersion', 'scope', 'targetDatabase', 'databaseOid')},
              'status': 'REMOTE_RUNTIMES_RETIRED', 'activated': False, 'globalQuiescenceProven': False,
              'providerId': args.provider_id, 'recoveryId': args.recovery_id,
              'databaseModified': any(result[k] for k in ('observationsUpdated', 'runtimesRetired', 'commandsCompleted')),
              'allocationsVerified': len(plan['inventory']['allocations']), **result,
              'intentSha256': hashlib.sha256((args.output / 'intent.json').read_bytes()).hexdigest(),
              'verifiedAt': datetime.now(timezone.utc).isoformat(),
              'preserved': ['task-attempt-run-states', 'committed-results', 'artifacts', 'runtime-identities',
                            'command-identities-attempt-counts', 'database-quarantine', 'provider-fence'],
              'excluded': ['workflow-outcome-reconciliation', 'retry', 'provider-output-recovery',
                           'device-journals', 'other-producer-retirement', 'external-provider-contract', 'service-activation']}
    durable_json(args.output / 'retirement.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('database', 'endpoint', 'certificate-sha256', 'provider-id', 'recovery-id', 'provider-key'):
        parser.add_argument('--' + name, required=True)
    for name in ('restore-report', 'ca-file', 'recovery-token-file', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--transport', choices=['native', 'compose'], default='native')
    parser.add_argument('--pg-bin', type=Path)
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--page-size', type=int, default=100)
    args = parser.parse_args()
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    submitted = False
    try:
        pg = Postgres(args.transport, args.pg_bin, diagnostics=args.output / 'postgres')
        plan = prepare(pg, args)
        submitted = True
        report = apply(pg, args, plan)
        print(report['status'] + ': ' + str(report['allocationsVerified']) + ' allocations; database remains quarantined')
        return 0
    except Exception as error:
        blocked = isinstance(error, (Blocked, OSError, http.client.HTTPException))
        durable_json(args.output / 'failure.json', {
            'status': 'BLOCKED' if blocked else 'FAIL', 'failureType': type(error).__name__, 'activated': False,
            'databaseModified': None if submitted else False,
            'instruction': 'Keep database quarantined; rerun with the same binding and recovery IDs in a new output directory'})
        print(('BLOCKED' if blocked else 'FAIL') + ': Remote retirement; private evidence retained; keep database quarantined')
        return 2 if blocked else 1


if __name__ == '__main__':
    raise SystemExit(main())
