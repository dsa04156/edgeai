"""Reconcile failed/cancelled reference Remote attempts and bounded retry queues without activating recovery."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
from recovery_remote_inventory import DATABASE_QUERY, canonical
from recovery_remote_outputs import verify as verify_bundle
from recovery_remote_retire import durable_json
from recovery_remote_results import TABLES as RESULT_TABLES, CONTEXT_QUERY, retired_snapshot
from recovery_remote_storage import private_json
from recovery_workflow_failures import transaction_sql as workflow_transaction

TABLES = RESULT_TABLES
GUARD_QUERY = 'SELECT jsonb_build_object(' + ','.join(
    literal(name) + ", (SELECT encode(sha256(convert_to(coalesce(string_agg("
    "encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' ORDER BY to_jsonb(t)::text)"
    ",''),'UTF8')),'hex') FROM edgeai." + name + ' t)' for name in TABLES) + ')'
CATALOG_SQL = ('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
               "SELECT value::jsonb || jsonb_build_object('guard',(" + GUARD_QUERY +
               "),'contexts',(" + CONTEXT_QUERY + "),'activeOffloadRuns',(SELECT coalesce(jsonb_agg(DISTINCT run_id),'[]'::jsonb)"
               " FROM edgeai.task_offload WHERE state IN ('DRAINING','STARTING','CANCELLING'))) FROM (" +
               DATABASE_QUERY.strip().removesuffix(';') + ') snapshot(value);\nCOMMIT;')


def bundle(args):
    verified = verify_bundle(args.bundle)
    manifest, digest = private_json(args.bundle / 'manifest.json')
    intent, _ = private_json(args.bundle / 'intent.json')
    if digest != verified['manifestSha256']: raise Blocked('Private recovery bundle changed')
    return manifest, intent, digest


def prepare(pg, args):
    manifest, intent, digest = bundle(args)
    catalog = database_inventory(pg, args.database, args.restore_report, CATALOG_SQL)
    actual, contexts = retired_snapshot(catalog, manifest, intent, args)
    if not contexts: raise Blocked('No recovered Remote inventory to reconcile')
    entries, retained, successes = [], 0, 0
    for key, context in contexts.items():
        row = actual[key]; attempt, task = context['attempt'], context['task']
        if attempt['epoch'] != context['latestEpoch']:
            retained += 1; continue  # Historical failures never overwrite their successor.
        if (context['hasStream'] or task['run_id'] in catalog['activeOffloadRuns'] or attempt['mode'] != 'REMOTE' or
                any(attempt['remote_' + field] != row[field] for field in ('provider_key', 'configuration_digest', 'source_mode'))):
            raise Blocked('STREAM/active transfers or changed Remote bindings require separate reconciliation')
        if context['result'] is not None:
            result = context['result']
            if not result['committed'] or task['state'] != 'SUCCEEDED' or attempt['state'] != 'SUCCEEDED' or context['retryPending']:
                raise Blocked('Existing Result conflicts with Task/Attempt state')
            retained += 1; continue
        action, reason = None, context['failureReason']
        if task['state'] == 'CANCELLING':
            if not task['cancellation_reason'] or attempt['state'] not in ('CANCELLING', 'CANCELLED', 'FAILED', 'OFFLOADED'):
                raise Blocked('Cancellation must be recorded before recovery')
            action = 'CANCEL'
        elif task['state'] == 'RETRY_WAIT':
            if attempt['state'] != 'FAILED' or not context['retryPending'] or reason is None:
                raise Blocked('Retry queue differs from failed attempt')
            action = 'CHECK_RETRY'
        elif task['state'] == 'FAILED':
            if attempt['state'] != 'FAILED' or context['retryPending'] or reason is None:
                raise Blocked('Failed Task has inconsistent attempt history')
            action = 'FINAL_FAILURE'
        elif task['state'] in ('CANCELLED', 'SKIPPED'):
            if attempt['state'] not in ('CANCELLED', 'FAILED', 'OFFLOADED') or context['retryPending']:
                raise Blocked('Cancelled Task has inconsistent attempt history')
            retained += 1; continue
        elif task['state'] == 'RUNNING' and attempt['state'] in ('DISPATCHING', 'RUNNING'):
            if context['runState'] != 'RUNNING' or task['cancellation_reason'] is not None or context['retryPending'] or reason is not None:
                raise Blocked('Active Task has contradictory cancellation or failure history')
            if row['provider_state'] == 'SUCCEEDED':
                successes += 1; continue
            reason = ('RUNTIME_LOST' if row['provider_state'] == 'CANCELLED' else
                      {'LEASE_EXPIRED': 'RUNTIME_TIMEOUT', 'PROVIDER_RESTART': 'RUNTIME_LOST'}.get(
                          row['observation']['failureReason'], row['observation']['failureReason']))
            if reason not in ('RUNTIME_LOST', 'RUNTIME_TIMEOUT', 'WORKLOAD_FAILED', 'INPUT_INVALID', 'OUTPUT_INVALID'):
                raise Blocked('Unsupported terminal provider failure')
            action = 'FAIL'
        else:
            raise Blocked('Unsupported current Remote Task/Attempt state')
        entries.append({'allocationId': key, 'runtimeId': context['runtimeId'], 'attemptId': attempt['id'],
                        'taskId': task['id'], 'runId': task['run_id'], 'action': action, 'reason': reason})
    return {'formatVersion': 1, 'scope': 'restored-reference-remote-failure-reconciliation',
            'targetDatabase': args.database, 'databaseOid': catalog['oid'], 'marker': catalog['marker'],
            'restoreReportSha256': catalog['restoreReportSha256'], 'remoteBundleSha256': digest,
            'providerId': manifest['providerId'], 'recoveryId': manifest['recoveryId'], 'beforeGuard': catalog['guard'],
            'entries': entries, 'historiesRetained': retained, 'successesPending': successes,
            'preparedAt': datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan):
    return workflow_transaction(plan, TABLES, GUARD_QUERY)


def apply(pg, args, plan):
    durable_json(args.output / 'intent.json', plan)
    if bundle(args)[2] != plan['remoteBundleSha256']: raise Blocked('Recovery bundle changed before commit')
    sql = args.output / 'transaction.sql'
    with private_file(sql, 'w') as target:
        target.write(transaction_sql(plan)); target.flush(); os.fsync(target.fileno())
    with sql.open('rb') as source:
        result = json.loads(pg.call('psql', ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                                   args.database, source=source, timeout=45, reject_stderr=True))
    after = prepare(pg, args)
    if (after['beforeGuard'] != result['afterGuard'] or any(after[key] != plan[key] for key in
            ('databaseOid', 'marker', 'remoteBundleSha256', 'restoreReportSha256'))):
        raise Blocked('Post-commit recovery state changed; keep quarantine')
    report = {**{key:plan[key] for key in ('formatVersion', 'scope', 'targetDatabase', 'databaseOid', 'providerId', 'recoveryId', 'remoteBundleSha256')},
              **result, 'status': 'REMOTE_FAILURES_RECONCILED', 'activated': False,
              'databaseModified': any(value for key,value in result.items() if key != 'afterGuard'),
              'pendingRetries': sum(entry['action']=='CHECK_RETRY' for entry in after['entries']),
              'successesPending': after['successesPending'], 'historiesRetained': after['historiesRetained'],
              'intentSha256': hashlib.sha256((args.output / 'intent.json').read_bytes()).hexdigest(),
              'verifiedAt': datetime.now(timezone.utc).isoformat(),
              'excluded': ['retry-dispatch', 'success-result-commit', 'stream-and-active-offload-recovery',
                           'device-journals', 'global-recovery-acceptance', 'service-activation']}
    durable_json(args.output / 'failures.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    for name in ('bundle', 'restore-report', 'output'): parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--transport', choices=('native', 'compose'), default='native')
    parser.add_argument('--pg-bin', type=Path)
    args = parser.parse_args(); args.output.mkdir(mode=0o700, parents=True, exist_ok=False); submitted = False
    try:
        pg = Postgres(args.transport, args.pg_bin, diagnostics=args.output / 'postgres')
        plan = prepare(pg, args); submitted = True; report = apply(pg, args, plan)
        print(report['status'] + ': ' + str(report['pendingRetries']) + ' retries remain queued; database quarantined')
        return 0
    except Exception as error:
        blocked = isinstance(error, (Blocked, OSError)); status = 'BLOCKED' if blocked else 'FAIL'
        durable_json(args.output / 'failure.json', {'status': status, 'failureType': type(error).__name__,
            'databaseModified': None if submitted else False, 'activated': False,
            'instruction': 'Retain quarantine and intent; rerun the same bundle in a new output directory'})
        print(status + ': Remote failure recovery; private evidence retained; keep quarantine')
        return 2 if blocked else 1


if __name__ == '__main__': raise SystemExit(main())
