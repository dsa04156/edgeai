"""Commit recovered reference Remote results in a quarantined restored DB without starting producers."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import uuid

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
from recovery_remote_inventory import DATABASE_QUERY, canonical
from recovery_remote_retire import durable_json
from recovery_remote_storage import Storage, inputs, private_json, verify as verify_storage

# Hash all rows, including inserts/deletes and immutable contracts, before acquiring write locks.
# Composite-key tables use their whole JSON row for stable ordering. No raw work or nonce in intent.
TABLES = ('flyway_schema_history', 'remote_allocation', 'runtime_instance', 'runtime_command',
          'task_attempt', 'task_result', 'result_artifact', 'task', 'workflow_run', 'task_retry',
          'task_definition', 'task_dependency', 'workflow_version', 'profile_version',
          'task_offload', 'task_offload_member', 'data_route')
GUARD_QUERY = 'SELECT jsonb_build_object(' + ','.join(
    literal(name) + ", (SELECT encode(sha256(convert_to(coalesce(string_agg("
    "encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' ORDER BY to_jsonb(t)::text)"
    ",''),'UTF8')),'hex') FROM edgeai." + name + ' t)' for name in TABLES) + ')'
CONTEXT_QUERY = """
SELECT coalesce(jsonb_agg(jsonb_build_object(
 'allocationId',a.id,'runtimeId',r.id,'namespace',r.namespace,'desiredState',r.desired_state,'runtimeState',r.observed_state,
 'failureReason',r.failure_reason,'attempt',to_jsonb(p),'task',to_jsonb(t),'runState',w.state,
 'outputs',v.spec->'outputs','latestEpoch',(SELECT max(epoch) FROM edgeai.task_attempt WHERE task_id=t.id),
 'retryPending',EXISTS(SELECT FROM edgeai.task_retry WHERE task_id=t.id),
 'activeOffload',EXISTS(SELECT FROM edgeai.task_offload WHERE run_id=t.run_id AND state IN ('DRAINING','STARTING','CANCELLING')),
 'hasStream',(EXISTS(SELECT FROM edgeai.task_dependency WHERE workflow_version_id=w.workflow_version_id AND mode='STREAM')
   OR EXISTS(SELECT FROM edgeai.data_route WHERE run_id=w.id)),
 'pendingCommands',EXISTS(SELECT FROM edgeai.runtime_command c WHERE c.runtime_id=r.id
   AND (NOT completed OR lease_owner IS NOT NULL OR lease_until IS NOT NULL)),
 'result',(SELECT to_jsonb(s) || jsonb_build_object('outputs',(
    SELECT jsonb_agg(jsonb_build_object('port',f.port,'bucket',f.bucket,'key',f.object_key,
      'versionId',f.object_version,'bytes',f.bytes,'sha256',f.sha256,'mediaType',f.media_type) ORDER BY port)
    FROM edgeai.result_artifact f WHERE f.result_id=s.id)) FROM edgeai.task_result s WHERE s.task_id=t.id)
) ORDER BY a.id),'[]'::jsonb)
FROM edgeai.remote_allocation a JOIN edgeai.runtime_instance r ON r.id=a.runtime_id
JOIN edgeai.task_attempt p ON p.id=r.attempt_id JOIN edgeai.task t ON t.id=r.task_id
JOIN edgeai.workflow_run w ON w.id=t.run_id JOIN edgeai.task_definition d ON d.id=t.definition_id
JOIN edgeai.profile_version v ON v.id=d.service_profile_version_id
"""
CATALOG_SQL = ('BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
               "SELECT value::jsonb || jsonb_build_object('guard',(" + GUARD_QUERY +
               "),'contexts',(" + CONTEXT_QUERY + ')) FROM (' +
               DATABASE_QUERY.strip().removesuffix(';') + ') snapshot(value);\nCOMMIT;')


def digest(outputs):
    values = [{key: item[key] for key in ('port', 'bytes', 'sha256', 'mediaType', 'versionId')}
              for item in sorted(outputs, key=lambda item: item['port'])]
    return 'sha256:' + hashlib.sha256(b'edgeai-result-v1\n' + canonical(values)).hexdigest()


def evidence(storage, args):
    manifest, backup, bundle_sha, backup_sha = inputs(args)
    receipt, receipt_sha = private_json(args.receipt)
    verified = verify_storage(storage, args)
    if verified['objects'] != receipt['objects'] or inputs(args)[2:] != (bundle_sha, backup_sha):
        raise Blocked('Recovery inputs changed during storage verification')
    if private_json(args.receipt)[1] != receipt_sha:
        raise Blocked('Publication receipt changed during verification')
    return manifest, receipt, {'remoteBundleSha256': bundle_sha, 'storageBackupSha256': backup_sha,
                              'publicationSha256': receipt_sha}


def retired_snapshot(catalog, manifest, intent, args):
    """Validate the complete frozen Remote inventory against one current, quarantined DB snapshot."""
    if (manifest['targetDatabase'] != args.database or manifest['databaseOid'] != catalog['oid'] or
            manifest['restoreReportSha256'] != catalog['restoreReportSha256'] or intent['marker'] != catalog['marker']):
        raise Blocked('Output bundle belongs to another restored database')
    expected = {row['allocationId']: row for row in intent['inventory']['allocations']}
    actual = {row['id']: row for row in catalog['allocations']}
    contexts = {row['allocationId']: row for row in catalog['contexts']}
    if expected.keys() != actual.keys() or actual.keys() != contexts.keys():
        raise Blocked('Complete restored Remote inventory differs from the recovered bundle')
    frozen = ('id', 'provider_key', 'configuration_digest', 'source_mode', 'request_digest',
              'work_identity', 'expires_at', 'runtime_id', 'run_id', 'task_id', 'attempt_id', 'epoch')
    for key, row in actual.items():
        old, observed, context = expected[key]['database'], expected[key]['provider'], contexts[key]
        if (any(row[field] != old[field] for field in frozen) or
                row['observation'] != {k:v for k,v in observed.items() if k != 'executions'} or
                row['provider_revision'] != observed['revision'] or row['provider_state'] != observed['state'] or
                context['desiredState'] != 'STOPPED' or context['runtimeState'] != 'TERMINATED' or context['pendingCommands']):
            raise Blocked('All recovered Remote runtimes must retain their exact retired observation and commands')
    return actual, contexts


def prepare(pg, storage, args):
    manifest, publication, hashes = evidence(storage, args)
    catalog = database_inventory(pg, args.database, args.restore_report, CATALOG_SQL)
    intent, _ = private_json(args.bundle / 'intent.json')
    actual, contexts = retired_snapshot(catalog, manifest, intent, args)
    entries = []
    for allocation in manifest['allocations']:
        identity = allocation['identity']; context = contexts[identity['allocationId']]
        attempt, task = context['attempt'], context['task']
        # Provider success cannot supply a missing, timely target start authorization.
        # Block the whole Run so child readiness cannot bypass another active transfer.
        if context['activeOffload']:
            raise Blocked('Active offload requires recorded start authority before result recovery')
        outputs = sorted([{k: item[k] for k in ('port', 'bucket', 'key', 'versionId', 'bytes', 'sha256', 'mediaType')}
                          for item in publication['objects'] if item['allocationId'] == identity['allocationId']],
                         key=lambda item: item['port'])
        spec = context['outputs']
        if (context['hasStream'] or not isinstance(spec, dict) or set(spec) != {o['port'] for o in outputs} or
                any(o['bytes'] > spec[o['port']]['maxBytes'] or o['mediaType'] != spec[o['port']]['mediaType'] for o in outputs)):
            raise Blocked('Recovered outputs must satisfy the complete BATCH SERVICE contract')
        result = context['result']; manifest_digest = digest(outputs)
        if (context['latestEpoch'] != identity['epoch'] or attempt['mode'] != 'REMOTE' or
                any(attempt['remote_' + key] != actual[identity['allocationId']][key]
                    for key in ('provider_key', 'configuration_digest', 'source_mode'))):
            raise Blocked('Recovered allocation no longer owns the latest frozen Remote attempt')
        if result is None:
            if (attempt['state'] not in ('DISPATCHING', 'RUNNING') or task['state'] != 'RUNNING' or
                    context['runState'] != 'RUNNING' or context['latestEpoch'] != identity['epoch'] or
                    attempt['mode'] != 'REMOTE' or context['retryPending'] or context['failureReason'] is not None or
                    task['cancellation_reason'] is not None):
                raise Blocked('Success cannot override cancellation, failure, retry or a newer attempt')
        elif (not result['committed'] or result['producer_kind'] != 'REMOTE' or result['producer_pod_uid'] is not None or
                result['vd_runtime_id'] is not None or result['task_id'] != identity['taskId'] or
                result['attempt_id'] != identity['attemptId'] or result['runtime_id'] != context['runtimeId'] or
                result['epoch'] != identity['epoch'] or result['remote_allocation_id'] != identity['allocationId'] or
                result['manifest_digest'] != manifest_digest or result['outputs'] != outputs or
                attempt['state'] != 'SUCCEEDED' or task['state'] != 'SUCCEEDED' or context['retryPending']):
            raise Blocked('Existing immutable Result differs from recovered success')
        entries.append({**identity, 'runtimeId': context['runtimeId'], 'outputs': outputs, 'manifestDigest': manifest_digest,
                        'resultId': result['id'] if result else str(uuid.uuid4()), 'existing': result is not None})
    if not entries: raise Blocked('No recovered successful allocations to reconcile')
    return {'formatVersion': 1, 'scope': 'restored-reference-remote-result-commit', 'targetDatabase': args.database,
            'databaseOid': catalog['oid'], 'marker': catalog['marker'], 'restoreReportSha256': catalog['restoreReportSha256'],
            'providerId': manifest['providerId'], 'recoveryId': manifest['recoveryId'], **hashes,
            'beforeGuard': catalog['guard'], 'entries': entries, 'preparedAt': datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan, *, tables=TABLES, guard_query=GUARD_QUERY, kubernetes=False):
    # Both recovery paths use the same atomic BATCH readiness/Run aggregation.
    # Kubernetes alone preserves an already committed Result ID/time and Pod binding.
    binding = """
 IF (entry->>'restoreBinding')::boolean THEN
  UPDATE edgeai.runtime_instance SET producer_pod_uid=(entry->>'podUid')::uuid,
   node_uid=(entry->>'nodeUid')::uuid,node_name=entry->>'nodeName' WHERE id=(entry->>'runtimeId')::uuid;
  bindings:=bindings+1;
 END IF;
""" if kubernetes else ''
    publication = """
 IF NOT (entry->>'existing')::boolean OR (entry->>'publicationPending')::boolean THEN
  UPDATE edgeai.runtime_result_publication SET completed=true,lease_owner=NULL,lease_until=NULL,
   updated_at=transaction_timestamp() WHERE result_id=(entry->>'resultId')::uuid
   AND runtime_id=(entry->>'runtimeId')::uuid AND NOT completed AND lease_owner IS NULL AND lease_until IS NULL;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>1 THEN RAISE EXCEPTION 'Result publication history differs'; END IF;
  publications:=publications+changed;
 END IF;
""" if kubernetes else ''
    identity = """
IF current_database()<>{database} OR NOT EXISTS (SELECT FROM pg_database WHERE datname=current_database()
 AND oid::text={oid} AND shobj_description(oid,'pg_database')={marker}) THEN
 RAISE EXCEPTION 'Restored database identity changed';
END IF;
""".format(database=literal(plan['targetDatabase']), oid=literal(plan['databaseOid']), marker=literal(plan['marker']))
    body = """
DECLARE entry jsonb; artifact jsonb; child edgeai.task; recovered_run_id uuid; new_state text; changed integer;
 results integer:=0; children integer:=0; runs integer:=0; bindings integer:=0; publications integer:=0;
BEGIN
{identity}
IF ({guard}) IS DISTINCT FROM {before}::jsonb THEN
 RAISE EXCEPTION 'Restored database changed after fixed-version verification';
END IF;
FOR entry IN SELECT * FROM jsonb_array_elements({entries}::jsonb) LOOP
{binding}
 IF NOT (entry->>'existing')::boolean THEN
  INSERT INTO edgeai.task_result(id,task_id,attempt_id,runtime_id,epoch,{producer_column},manifest_digest,created_at)
  VALUES ((entry->>'resultId')::uuid,(entry->>'taskId')::uuid,(entry->>'attemptId')::uuid,
    (entry->>'runtimeId')::uuid,(entry->>'epoch')::bigint,(entry->>'{producer_field}')::uuid,
    entry->>'manifestDigest',{created_at});
  FOR artifact IN SELECT * FROM jsonb_array_elements(entry->'outputs') LOOP
   INSERT INTO edgeai.result_artifact(id,result_id,port,bucket,object_key,object_version,sha256,bytes,media_type)
   VALUES (gen_random_uuid(),(entry->>'resultId')::uuid,artifact->>'port',artifact->>'bucket',artifact->>'key',
    artifact->>'versionId',artifact->>'sha256',(artifact->>'bytes')::bigint,artifact->>'mediaType');
  END LOOP;
  UPDATE edgeai.task_result SET committed=true WHERE id=(entry->>'resultId')::uuid;
  UPDATE edgeai.task_attempt SET state='SUCCEEDED',updated_at=transaction_timestamp() WHERE id=(entry->>'attemptId')::uuid;
  UPDATE edgeai.task SET state='SUCCEEDED',updated_at=transaction_timestamp() WHERE id=(entry->>'taskId')::uuid;
  results:=results+1;
 END IF;
{publication}
END LOOP;
FOR recovered_run_id IN SELECT DISTINCT (value->>'runId')::uuid FROM jsonb_array_elements({entries}::jsonb) LOOP
 IF EXISTS(SELECT FROM edgeai.workflow_run WHERE id=recovered_run_id AND state='RUNNING') THEN
  FOR child IN SELECT t.* FROM edgeai.task t WHERE t.run_id=recovered_run_id AND t.state='WAITING' AND NOT EXISTS (
   SELECT FROM edgeai.task_dependency d JOIN edgeai.task parent ON parent.definition_id=d.from_task_id AND parent.run_id=t.run_id
   WHERE d.to_task_id=t.definition_id AND (parent.state<>'SUCCEEDED' OR NOT EXISTS (
    SELECT FROM edgeai.task_result result WHERE result.task_id=parent.id AND result.committed))) LOOP
   IF child.cancellation_reason IS NOT NULL OR EXISTS(SELECT FROM edgeai.task_attempt WHERE task_id=child.id) THEN
    RAISE EXCEPTION 'Waiting child has contradictory execution history';
   END IF;
   UPDATE edgeai.task SET state='READY',updated_at=transaction_timestamp() WHERE id=child.id;
   INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,created_at,updated_at,
      remote_provider_key,remote_configuration_digest,remote_source_mode,vd_id)
   VALUES (gen_random_uuid(),child.id,1,1,'QUEUED',child.initial_mode,child.initial_node_id,'INITIAL',
      transaction_timestamp(),transaction_timestamp(),child.initial_remote_provider_key,
      child.initial_remote_configuration_digest,child.initial_remote_source_mode,child.initial_vd_id);
   children:=children+1;
  END LOOP;
  SELECT CASE WHEN bool_or(state='FAILED') THEN 'FAILED'
     WHEN bool_or(state IN ('CANCELLED','SKIPPED')) THEN 'CANCELLED' ELSE 'SUCCEEDED' END INTO new_state
  FROM edgeai.task t WHERE t.run_id=recovered_run_id HAVING bool_and(state IN ('SUCCEEDED','FAILED','CANCELLED','SKIPPED'));
  IF new_state IS NOT NULL THEN
   UPDATE edgeai.workflow_run SET state=new_state,updated_at=transaction_timestamp() WHERE id=recovered_run_id AND state<>new_state;
   GET DIAGNOSTICS changed=ROW_COUNT; runs:=runs+changed;
  END IF;
 END IF;
END LOOP;
{identity}
INSERT INTO recovery_result VALUES (jsonb_build_object('resultsCreated',results,'childrenReadied',children,
 'runsReconciled',runs,'afterGuard',({guard}){counters}));
END
""".format(identity=identity, guard=guard_query, before=literal(canonical(plan['beforeGuard']).decode()),
           entries=literal(canonical(plan['entries']).decode()), binding=binding, publication=publication,
           producer_column='producer_pod_uid' if kubernetes else 'remote_allocation_id',
           producer_field='podUid' if kubernetes else 'allocationId',
           created_at="(entry->>'committedAt')::timestamptz" if kubernetes else 'transaction_timestamp()',
           counters=",'bindingsRestored',bindings,'publicationsCompleted',publications" if kubernetes else '')
    return ("BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s';\nLOCK TABLE " +
            ','.join('edgeai.' + table for table in tables) + ' IN SHARE ROW EXCLUSIVE MODE;\n'
            'CREATE TEMP TABLE recovery_result(value jsonb) ON COMMIT DROP; DO ' + literal(body) +
            '; SELECT value FROM recovery_result; COMMIT;\n')


def apply(pg, storage, args, plan):
    durable_json(args.output / 'intent.json', plan)
    # Check S3 again immediately before the DB transaction; S3 deletion and DB commit cannot be atomic.
    # A failing post-commit verification keeps recovery quarantined and never claims rollback.
    _, _, hashes = evidence(storage, args)
    if any(plan[key] != value for key, value in hashes.items()): raise Blocked('Recovery input changed before commit')
    path = args.output / 'transaction.sql'
    with private_file(path, 'w') as target:
        target.write(transaction_sql(plan)); target.flush(); os.fsync(target.fileno())
    with path.open('rb') as source:
        result = json.loads(pg.call('psql', ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                                   args.database, source=source, timeout=45, reject_stderr=True))
    after = prepare(pg, storage, args)
    if (after['beforeGuard'] != result['afterGuard'] or after['databaseOid'] != plan['databaseOid'] or
            after['marker'] != plan['marker'] or any(after[key] != plan[key] for key in hashes) or
            after['entries'] != [{**entry, 'existing': True} for entry in plan['entries']]):
        raise Blocked('Post-commit result state changed; retain quarantine')
    report = {**{k:plan[k] for k in ('formatVersion', 'scope', 'targetDatabase', 'databaseOid', 'providerId', 'recoveryId')},
              **hashes, **result, 'status': 'REMOTE_RESULTS_COMMITTED', 'activated': False,
              'databaseModified': any(result[k] for k in ('resultsCreated', 'childrenReadied', 'runsReconciled')),
              'resultsVerified': len(plan['entries']), 'intentSha256': hashlib.sha256((args.output / 'intent.json').read_bytes()).hexdigest(),
              'verifiedAt': datetime.now(timezone.utc).isoformat(),
              'excluded': ['failed-cancelled-attempt-reconciliation', 'stream-group-recovery', 'device-journals',
                           'other-producer-retirement', 'global-recovery-acceptance', 'service-activation']}
    durable_json(args.output / 'results.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('database', 'bucket', 'certificate-sha256'): parser.add_argument('--' + name, required=True)
    for name in ('bundle', 'storage-backup', 'receipt', 'restore-report', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--transport', choices=('native', 'compose'), default='native')
    parser.add_argument('--pg-bin', type=Path)
    parser.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args(); args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    submitted = False
    try:
        pg = Postgres(args.transport, args.pg_bin, diagnostics=args.output / 'postgres')
        storage = Storage(args); plan = prepare(pg, storage, args)
        submitted = True; report = apply(pg, storage, args, plan)
        print(report['status'] + ': ' + str(report['resultsVerified']) + ' results; database remains quarantined')
        return 0
    except Exception as error:
        blocked = isinstance(error, (Blocked, OSError, http.client.HTTPException))
        status = 'BLOCKED' if blocked else 'FAIL'
        durable_json(args.output / 'failure.json', {'status': status, 'failureType': type(error).__name__,
                     'activated': False, 'databaseModified': None if submitted else False,
                     'instruction': 'Keep quarantine and published versions; rerun identical inputs in a new output directory'})
        print(status + ': Remote result recovery; private evidence retained; keep database quarantined')
        return 2 if blocked else 1


if __name__ == '__main__': raise SystemExit(main())
