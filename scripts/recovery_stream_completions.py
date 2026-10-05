"""Restore original STREAM checkpoint history and group grants into a quarantined DB.

Original Run/routes/Attempts/runtimes must already exist. Independent fixed-version
receipts and current producer/broker retirement proof authorize historical records
only. This command never starts a producer, opens a generation or activates a DB.
"""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import traceback
from types import SimpleNamespace

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
import recovery_kubernetes_retire as retirement
import recovery_kubernetes_results as results
import recovery_runtime_starts as starts
import recovery_stream_results as streams
from recovery_stream_completion_history import read_history, same_receipt, instant
from recovery_stream_workflows import components
from recovery_remote_fence import unique
from recovery_remote_inventory import uid
from recovery_remote_retire import durable_json
from recovery_remote_storage import Storage, private_json, header, version
from storage_backup import validate_manifest

FIELDS = {'apiVersion','id','runId','namespace','brokerDigest','routeDigest','taskIds','attemptIds',
          'grantedAt','taskCompletions','deviceCompletions','checkpoints','generations','routes','producers'}
GENERATION_FIELDS = set('id route_id run_id generation source_task_id source_device_id consumer_task_id '
    'producer_attempt_id producer_session_id producer_epoch consumer_attempt_id consumer_epoch broker_digest '
    'policy_digest request_digest created_at updated_at lease_until activated_at fenced_at fence_reason closed_at'.split())
GENERATION_MUTABLE = {'state','updated_at','lease_until','fenced_at','fence_reason','closed_at'}
TABLES = (*results.TABLES, 'stream_completion_publication')
GUARD = results.completion_guard(True)


def same_row(a, b, times=()):
    return (isinstance(a, dict) and isinstance(b, dict) and set(a) == set(b)
            and all(instant(a[k]) == instant(b[k]) if k in times and a[k] is not None and b[k] is not None
                    else a[k] == b[k] for k in a))


def catalog_sql(vd):
    original = streams.catalog_query()
    extended = original[:-1] + ", 'deviceBindings',(SELECT coalesce(jsonb_agg(to_jsonb(b) ORDER BY route_id),'[]'::jsonb) FROM edgeai.stream_device_binding b)," \
        "'publications',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY id),'[]'::jsonb) FROM edgeai.stream_completion_publication p))"
    return results.catalog_sql(vd, True, True).replace(original, extended)


def read_completion(store, manifest, bucket, identity):
    uid(identity)
    key = 'authority/stream-completion/' + identity + '.json'
    matches = [v for v in manifest['versions'] if v['bucket'] == bucket and v['key'] == key]
    if len(matches) != 1 or not 2 <= matches[0]['bytes'] <= 16777216:
        raise Blocked('Original completion authority is absent or ambiguous')
    item = matches[0]
    code, heads, _ = store.request('HEAD', '/' + bucket + '/' + key)
    if code != 200 or header(heads, 'x-amz-version-id') != version(item['versionId']):
        raise Blocked('Completion authority head differs from its independent backup')
    code, heads, wire = store.request('GET', '/' + bucket + '/' + key, {'versionId': item['versionId']}, max_bytes=16777216)
    if (code != 200 or header(heads, 'x-amz-version-id') != item['versionId'] or
            header(heads, 'content-type') != 'application/vnd.edgeai.stream-completion+json' or
            header(heads, 'content-length') != str(item['bytes']) or len(wire) != item['bytes'] or
            hashlib.sha256(wire).hexdigest() != item['sha256'] or
            any(k.lower() in ('content-encoding', 'transfer-encoding') for k, _ in heads)):
        raise Blocked('Original completion authority bytes differ from the fixed backup')
    try:
        value = json.loads(wire, object_pairs_hook=unique)
    except (ValueError, UnicodeError) as error:
        raise Blocked('Invalid original completion authority') from error
    if not isinstance(value, dict) or set(value) != FIELDS or value['apiVersion'] != 'edgeai.stream.completion/v1' or value['id'] != identity:
        raise Blocked('Unsupported original completion authority')
    return value, item


def prepare(pg, args):
    uid(args.completion_id)
    retired = retirement.prepare(pg, args)
    catalogs = {vd: database_inventory(pg, args.database, args.restore_report, catalog_sql(vd)) for vd in (False, True)}
    catalog = catalogs[False]
    if {m['version'] for m in catalog['migrations']} != {str(v) for v in range(1, 41)}:
        raise Blocked('Historical STREAM insertion requires the reviewed V40 schema')
    if catalogs[True]['guard'] != catalog['guard'] or any(retired['beforeGuard'][t] != catalog['guard'][t] for t in retirement.TABLES):
        raise Blocked('Restored database changed during completion observation')
    if any(retired[k] != catalog[v] for k, v in [('databaseOid','oid'),('marker','marker'),('restoreReportSha256','restoreReportSha256')]):
        raise Blocked('Completion and retirement database identities differ')
    manifest, manifest_hash = private_json(args.runtime_start_backup / 'manifest.json')
    validate_manifest(manifest)
    store = Storage(SimpleNamespace(certificate_sha256=args.runtime_start_certificate_sha256, timeout=args.timeout))
    if store.identity() != manifest['targetDeploymentId'] or manifest['sourceDeploymentId'] == manifest['targetDeploymentId'] or args.runtime_start_bucket not in manifest['buckets']:
        raise Blocked('Original completion requires its distinct independent backup installation')
    store.versioned(args.runtime_start_bucket)
    document, item = read_completion(store, manifest, args.runtime_start_bucket, args.completion_id)
    if document['namespace'] != args.namespace or document['brokerDigest'] != args.broker_digest:
        raise Blocked('Completion belongs to another namespace or broker')
    run_id = document['runId']; uid(run_id)
    for name in ('taskIds', 'attemptIds'):
        values = document[name]
        if not isinstance(values, list) or not 1 <= len(values) <= 128 or values != sorted(set(values)):
            raise Blocked('Completion member identities must be distinct and ordered')
        for value in values: uid(value)
    if document['id'] != document['attemptIds'][0] or len(document['taskIds']) != len(document['attemptIds']):
        raise Blocked('Completion identity does not cover its original members')
    for name in ('taskCompletions','deviceCompletions','checkpoints','generations','routes','producers'):
        if not isinstance(document[name], list) or len(document[name]) > 4096:
            raise Blocked('Invalid original completion collection')
    state = deepcopy(catalog['stream'])
    task_ids = set(document['taskIds'])
    routes = [r for r in state['routes'] if r['run_id'] == run_id]
    if document['taskIds'] not in components(routes):
        raise Blocked('Completion must cover exactly one entire connected STREAM group')
    connected = sorted((r for r in routes if r['consumer_task_id'] in task_ids), key=lambda r: r['id'])
    if len(document['routes']) != len(connected) or not all(same_row(a, b, ('created_at',)) for a, b in zip(sorted(document['routes'], key=lambda r:r['id']), connected)):
        raise Blocked('Completion routes differ from original frozen definitions')
    bindings = [b for b in state['bindings'] if b['run_id'] == run_id]
    if len(bindings) != 1 or bindings[0]['route_digest'] != document['routeDigest']:
        raise Blocked('Original frozen membership is absent or changed')
    grant_time = instant(document['grantedAt'])
    if grant_time > datetime.now(timezone.utc) or instant(bindings[0]['created_at']) > grant_time:
        raise Blocked('Completion grant predates membership or lies in the future')
    attempts = {a['id']:a for a in state['attempts']}
    runtimes = {r['id']:r for r in state['runtimes']}
    tasks = {t['id']:t for t in state['tasks']}
    if (any(a not in attempts for a in document['attemptIds']) or
            {attempts[a]['task_id'] for a in document['attemptIds']} != task_ids or
            any(t not in tasks or tasks[t]['run_id'] != run_id or tasks[t]['cancellation_reason'] is not None for t in task_ids)):
        raise Blocked('Original completion Attempt/Task history is absent or cancelled')
    for observed in retired['evidence']['identities']:
        identity = observed.get('identity', {})
        if identity.get('run-id') == run_id and identity.get('attempt-id') not in attempts:
            raise Blocked('Observed post-snapshot execution must be restored before its completion')
    start_evidence = {vd: starts.observe(catalogs[vd], args, retired, vd) for vd in (False, True)}
    if any(e is None or e['storageBackupSha256'] != manifest_hash for e in start_evidence.values()):
        raise Blocked('Completion and original start authorities use different backups')
    starts_by_runtime = {k:v for evidence in start_evidence.values() for k,v in evidence['records'].items()}
    producers = {p.get('runtimeId'):p for p in document['producers'] if isinstance(p,dict)}
    if len(producers) != len(document['producers']) or len(producers) != len(task_ids):
        raise Blocked('Every completion member needs one original producer')
    restore_bindings = []
    for rid, producer in producers.items():
        runtime = runtimes.get(rid); record = starts_by_runtime.get(rid)
        if runtime is None or record is None or runtime['attempt_id'] not in document['attemptIds']:
            raise Blocked('Original completion producer or its start authority is missing')
        start = record['authority']
        expected = {k:runtime[v] for k,v in {'runtimeId':'id','taskId':'task_id','attemptId':'attempt_id','epoch':'epoch','kind':'runtime_kind',
                    'jobName':'job_name','jobUid':'job_uid','vdId':'vd_id'}.items()}
        expected.update({k:start[k] for k in ('podUid','nodeUid','nodeName')})
        expected['startKey'] = 'authority/' + ('vd-task-start/' if runtime['runtime_kind'] == 'VD' else 'runtime-start/') + rid + '.json'
        if producer != expected or starts.instant_ns(start['admittedAt']) > starts.instant_ns(grant_time.astimezone(timezone.utc).isoformat()):
            raise Blocked('Completion producer differs from its verified original admission')
        if runtime['producer_pod_uid'] is None:
            restore_bindings.append({k:start[k] for k in ('runtimeId','podUid','nodeUid','nodeName')})
            runtime.update(producer_pod_uid=start['podUid'],node_uid=start['nodeUid'],node_name=start['nodeName'])
    if {p['attemptId'] for p in producers.values()} != set(document['attemptIds']):
        raise Blocked('Completion producers omit or duplicate an original Attempt')
    history = read_history(store, manifest, document, args.runtime_start_bucket)
    cp_by_id = {c['id']:c for c in state['checkpoints']}
    contracts = {c['taskId']:c for c in state['contracts']}
    new_checkpoints = []
    for cp in history['receipts']:
        if cp['task_id'] not in task_ids or cp['runtime_id'] not in runtimes or starts_by_runtime.get(cp['runtime_id']) is None:
            raise Blocked('Checkpoint ancestry lacks its original retired producer and admission')
        runtime = runtimes[cp['runtime_id']]; admission = starts_by_runtime[cp['runtime_id']]['authority']
        if (cp['producer_pod_uid'] != admission['podUid'] or cp['attempt_id'] != runtime['attempt_id'] or cp['epoch'] != runtime['epoch'] or
                cp['task_id'] != runtime['task_id'] or
                starts.instant_ns(instant(cp['created_at']).astimezone(timezone.utc).isoformat()) < starts.instant_ns(admission['admittedAt']) or instant(cp['created_at']) > grant_time):
            raise Blocked('Checkpoint receipt differs from its original admitted producer')
        if runtime['producer_pod_uid'] is None:
            restore_bindings.append({k:admission[k] for k in ('runtimeId','podUid','nodeUid','nodeName')})
            runtime.update(producer_pod_uid=admission['podUid'],node_uid=admission['nodeUid'],node_name=admission['nodeName'])
        streams.verify_execution(cp, contracts[cp['task_id']], connected)
        current = cp_by_id.get(cp['id'])
        if current is not None and not same_receipt(cp, current):
            raise Blocked('Existing checkpoint conflicts with the original independent receipt')
        if current is None:
            cp_by_id[cp['id']] = cp; state['checkpoints'].append(cp); new_checkpoints.append(cp)
    generations = {g['id']:g for g in state['generations']}
    pins = {b['route_id']:b for b in state['deviceBindings']}
    new_generations = []
    for g in document['generations']:
        if (not isinstance(g,dict) or set(g) != GENERATION_FIELDS or g['run_id'] != run_id or
                g['broker_digest'] != args.broker_digest or g['route_id'] not in {r['id'] for r in connected} or
                g['activated_at'] is None or g['fenced_at'] is not None or g['closed_at'] is not None or g['fence_reason'] is not None or
                instant(g['activated_at']) > grant_time or instant(g['lease_until']) <= grant_time):
            raise Blocked('Completion generation lacks original live grant authority')
        if g['source_device_id'] is not None:
            pin = pins.get(g['route_id'])
            if pin is None or any(pin[k] != g[v] for k,v in [('run_id','run_id'),('device_id','source_device_id'),('session_id','producer_session_id'),('epoch','producer_epoch')]):
                raise Blocked('Completion generation changes the original pinned Device Session')
        current = generations.get(g['id'])
        if current is not None:
            if (not same_row({k:v for k,v in g.items() if k not in GENERATION_MUTABLE},
                             {k:v for k,v in current.items() if k not in GENERATION_MUTABLE},('created_at','activated_at')) or
                    current['closed_at'] is None or current['fenced_at'] is None or instant(current['lease_until']) < instant(g['lease_until'])):
                raise Blocked('Existing generation conflicts with its original completion authority or retirement')
        else:
            current = {**g,'state':'CLOSED','fence_reason':'REPLACED','fenced_at':datetime.now(timezone.utc).isoformat(),
                       'closed_at':datetime.now(timezone.utc).isoformat()}
            generations[g['id']] = current; state['generations'].append(current); new_generations.append(g)
    if len({g['id'] for g in document['generations']}) != len(document['generations']):
        raise Blocked('Completion generation identities are duplicated')
    if {g['id'] for g in document['generations']} != {gid for cp in document['checkpoints'] for gid in cp['generation_ids']}:
        raise Blocked('Completion must contain exactly its terminal checkpoint generations')
    for route in connected:
        numbers = sorted(g['generation'] for g in generations.values() if g['route_id'] == route['id'])
        if numbers != list(range(1,len(numbers)+1)):
            raise Blocked('Original generation history has a gap; no predecessor may be inferred')
    if any(gid not in generations for cp in history['receipts'] for gid in cp['generation_ids']):
        raise Blocked('Original predecessor generation authority is absent; never infer it from checkpoint bytes')
    changes = {}
    for key, field, columns in [('taskCompletions','completions',{'attempt_id','checkpoint_id','created_at','granted_at'}),
                                ('deviceCompletions','deviceCompletions',{'generation_id','sequence','created_at','granted_at'})]:
        identity_key = 'attempt_id' if key == 'taskCompletions' else 'generation_id'
        values = document[key]; existing = {r[identity_key]:r for r in state[field]}; pending = []
        if len({r.get(identity_key) for r in values if isinstance(r,dict)}) != len(values):
            raise Blocked('Original completion reports have duplicate identities')
        for row in values:
            if not isinstance(row,dict) or set(row) != columns or row['granted_at'] is None or instant(row['granted_at']) != grant_time or instant(row['created_at']) > grant_time:
                raise Blocked('Original completion reports do not share one grant instant')
            old = existing.get(row[identity_key])
            if old is not None and not same_row({**row,'granted_at':old['granted_at']},old,('created_at','granted_at')):
                raise Blocked('Existing completion report conflicts with independent authority')
            if old is not None and old['granted_at'] is not None and instant(old['granted_at']) != grant_time:
                raise Blocked('Existing completion has another grant')
            if old is None or old['granted_at'] is None:
                pending.append({'row':row,'insert':old is None})
            if old is not None: state[field].remove(old)
            # The SQL-captured V37 document retains its original UTC offset.
            # Existing observation contracts consume UTC; normalize only the
            # candidate view, never the immutable source document or timestamps.
            state[field].append({**row, **{k:instant(row[k]).astimezone(timezone.utc).isoformat()
                                         for k in ('created_at','granted_at')}})
        changes[key] = pending
    if {c['attempt_id'] for c in document['taskCompletions']} != set(document['attemptIds']):
        raise Blocked('Completion reports omit an original member')
    if ({c['checkpoint_id'] for c in document['taskCompletions']} != {c['id'] for c in document['checkpoints']} or
            {c['generation_id'] for c in document['deviceCompletions']} != {g['id'] for g in document['generations'] if g['source_device_id'] is not None}):
        raise Blocked('Completion reports add or omit terminal checkpoint or Device authority')
    current = [p for p in state['publications'] if p['id'] == document['id']]
    if current and (len(current) != 1 or current[0]['document'] != document or current[0]['lease_owner'] is not None or current[0]['lease_until'] is not None):
        raise Blocked('Existing completion publication conflicts or is leased')
    candidates = {**catalog,'stream':state}
    entries = [{'runId':run_id,'taskId':attempts[a]['task_id'],'attemptId':a,
                'committedAt':grant_time.astimezone(timezone.utc).isoformat()} for a in document['attemptIds']]
    barrier = streams.observe(pg, args, candidates, retired, {'startEvidence':{'storageBackupSha256':manifest_hash}}, entries)
    if (database_inventory(pg,args.database,args.restore_report,catalog_sql(False))['guard'] != catalog['guard'] or
            private_json(args.runtime_start_backup/'manifest.json')[1] != manifest_hash or
            read_completion(store,manifest,args.runtime_start_bucket,args.completion_id) != (document,item)):
        raise Blocked('Completion recovery inputs changed during verification')
    changes.update(generations=sorted(new_generations,key=lambda g:(g['route_id'],g['generation'])),
                   checkpoints=new_checkpoints,bindings=sorted(restore_bindings,key=lambda b:b['runtimeId']),
                   publication=not current,publicationPending=bool(current and (not current[0]['completed'] or not current[0]['checkpoint_history_completed'])))
    return {'formatVersion':1,'scope':'restored-stream-completion-history','targetDatabase':args.database,
            'databaseOid':catalog['oid'],'marker':catalog['marker'],'restoreReportSha256':catalog['restoreReportSha256'],
            'namespace':args.namespace,'namespaceUid':args.namespace_uid,'recoveryId':args.recovery_id,
            'beforeGuard':catalog['guard'],'physicalEvidence':retired['evidence'],'storageBackupSha256':manifest_hash,
            'document':document,'object':item,'checkpointHistory':history,'startEvidence':{str(k):v for k,v in start_evidence.items()},
            'streamEvidence':barrier,'changes':changes,'preparedAt':datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan):
    changes = plan['changes']
    scope = {k:plan[k] for k in ('targetDatabase','databaseOid','marker','recoveryId')}
    scope.update(generations=changes['generations'],checkpoints=changes['checkpoints'])
    value = lambda x: literal(json.dumps(x,separators=(',',':'))) + '::jsonb'
    body = """
DECLARE item jsonb; row_data jsonb; generation edgeai.route_generation; checkpoint edgeai.stream_checkpoint;
 generations_count integer:=0; checkpoints_count integer:=0; bindings_count integer:=0; reports_count integer:=0; grants_count integer:=0;
 publications_count integer:=0; changed integer;
BEGIN
 IF current_database()<>{database} OR NOT EXISTS(SELECT FROM pg_database WHERE datname=current_database()
  AND oid::text={oid} AND shobj_description(oid,'pg_database')={marker}) THEN
  RAISE EXCEPTION 'Restored database identity changed'; END IF;
 IF ({guard}) IS DISTINCT FROM {before} THEN RAISE EXCEPTION 'Completion recovery database changed after verification'; END IF;
 INSERT INTO edgeai_stream_recovery_scope VALUES ({scope}||jsonb_build_object('transactionId',txid_current()::text));
 PERFORM set_config('edgeai.stream_history_recovery','on',true);
 FOR item IN SELECT * FROM jsonb_array_elements({bindings}) LOOP
  UPDATE edgeai.runtime_instance SET producer_pod_uid=(item->>'podUid')::uuid,
   node_uid=(item->>'nodeUid')::uuid,node_name=item->>'nodeName' WHERE id=(item->>'runtimeId')::uuid
   AND producer_pod_uid IS NULL AND desired_state='STOPPED' AND observed_state='TERMINATED';
  GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>1 THEN RAISE EXCEPTION 'Original producer claim changed'; END IF;
  bindings_count:=bindings_count+1;
 END LOOP;
 FOR item IN SELECT * FROM jsonb_array_elements({generations}) LOOP
  SELECT * INTO generation FROM jsonb_populate_record(NULL::edgeai.route_generation,item);
  generation.fenced_at:=transaction_timestamp();generation.closed_at:=transaction_timestamp();
  generation.updated_at:=transaction_timestamp();generation.fence_reason:='REPLACED';
  INSERT INTO edgeai.route_generation({generation_columns}) SELECT {generation_values};
  generations_count:=generations_count+1;
 END LOOP;
 FOR item IN SELECT * FROM jsonb_array_elements({checkpoints}) LOOP
  SELECT * INTO checkpoint FROM jsonb_populate_record(NULL::edgeai.stream_checkpoint,item);
  INSERT INTO edgeai.stream_checkpoint SELECT checkpoint.*;
  checkpoints_count:=checkpoints_count+1;
 END LOOP;
 IF {insert_publication} THEN
  INSERT INTO edgeai.stream_completion_publication(id,run_id,namespace,attempt_ids,granted_at,document,
   completed,checkpoint_history_completed,available_at,created_at,updated_at)
  SELECT (d->>'id')::uuid,(d->>'runId')::uuid,d->>'namespace',ARRAY(SELECT jsonb_array_elements_text(d->'attemptIds')::uuid),
   (d->>'grantedAt')::timestamptz,d,true,true,transaction_timestamp(),transaction_timestamp(),transaction_timestamp() FROM (SELECT {document} d) x;
  publications_count:=1;
 ELSIF {pending_publication} THEN
  UPDATE edgeai.stream_completion_publication SET completed=true,checkpoint_history_completed=true,updated_at=transaction_timestamp()
   WHERE id=({document}->>'id')::uuid AND lease_owner IS NULL AND lease_until IS NULL;
  GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>1 THEN RAISE EXCEPTION 'Completion publication changed'; END IF;
  publications_count:=1;
 END IF;
 FOR item IN SELECT * FROM jsonb_array_elements({task_reports}) LOOP
  row_data:=item->'row';
  IF (item->>'insert')::boolean THEN
   INSERT INTO edgeai.stream_task_completion(attempt_id,checkpoint_id,created_at)
    VALUES((row_data->>'attempt_id')::uuid,(row_data->>'checkpoint_id')::uuid,(row_data->>'created_at')::timestamptz);
   reports_count:=reports_count+1;
  END IF;
  UPDATE edgeai.stream_task_completion SET granted_at=(row_data->>'granted_at')::timestamptz
   WHERE attempt_id=(row_data->>'attempt_id')::uuid AND granted_at IS NULL;
  GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>1 THEN RAISE EXCEPTION 'Original task completion changed'; END IF;
  grants_count:=grants_count+1;
 END LOOP;
 FOR item IN SELECT * FROM jsonb_array_elements({device_reports}) LOOP
  row_data:=item->'row';
  IF (item->>'insert')::boolean THEN
   INSERT INTO edgeai.stream_device_completion(generation_id,sequence,created_at)
    VALUES((row_data->>'generation_id')::uuid,(row_data->>'sequence')::bigint,(row_data->>'created_at')::timestamptz);
   reports_count:=reports_count+1;
  END IF;
  UPDATE edgeai.stream_device_completion SET granted_at=(row_data->>'granted_at')::timestamptz
   WHERE generation_id=(row_data->>'generation_id')::uuid AND granted_at IS NULL;
  GET DIAGNOSTICS changed=ROW_COUNT; IF changed<>1 THEN RAISE EXCEPTION 'Original device completion changed'; END IF;
  grants_count:=grants_count+1;
 END LOOP;
 PERFORM set_config('edgeai.stream_history_recovery','off',true);
 SET CONSTRAINTS ALL IMMEDIATE;
 INSERT INTO stream_completion_recovery_result VALUES(jsonb_build_object('generationsRestored',generations_count,
  'checkpointsRestored',checkpoints_count,'bindingsRestored',bindings_count,'reportsRestored',reports_count,
  'grantsRestored',grants_count,'publicationsRestored',publications_count,'afterGuard',({guard})));
END
""".format(database=literal(plan['targetDatabase']),oid=literal(plan['databaseOid']),marker=literal(plan['marker']),
           guard=GUARD,before=value(plan['beforeGuard']),scope=value(scope),bindings=value(changes['bindings']),
           generations=value(changes['generations']),checkpoints=value(changes['checkpoints']),
           generation_columns=','.join(sorted(GENERATION_FIELDS)),generation_values=','.join('generation.'+k for k in sorted(GENERATION_FIELDS)),
           insert_publication=str(changes['publication']).lower(),pending_publication=str(changes['publicationPending']).lower(),
           document=value(plan['document']),task_reports=value(changes['taskCompletions']),device_reports=value(changes['deviceCompletions']))
    return "BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s';\nLOCK TABLE " + ','.join('edgeai.'+t for t in TABLES) + \
        ' IN SHARE ROW EXCLUSIVE MODE;\nCREATE TEMP TABLE edgeai_stream_recovery_scope(value jsonb) ON COMMIT DROP;\n' \
        'CREATE TEMP TABLE stream_completion_recovery_result(value jsonb) ON COMMIT DROP;\nDO ' + literal(body) + \
        ';\nSELECT value FROM stream_completion_recovery_result; COMMIT;\n'


def apply(pg, args, plan):
    durable_json(args.output/'intent.json',plan)
    fresh = prepare(pg,args)
    if {k:v for k,v in fresh.items() if k!='preparedAt'} != {k:v for k,v in plan.items() if k!='preparedAt'}:
        raise Blocked('Completion recovery inputs changed before commit')
    path = args.output/'transaction.sql'
    with private_file(path,'w') as out:
        out.write(transaction_sql(plan));out.flush();os.fsync(out.fileno())
    with path.open('rb') as source:
        result = json.loads(pg.call('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-f','-'],args.database,source=source,timeout=45,reject_stderr=True))
    after = prepare(pg,args)
    if after['beforeGuard'] != result['afterGuard'] or any(after['changes'].values()) or any(
            after[k] != plan[k] for k in plan if k not in ('changes','beforeGuard','preparedAt','streamEvidence')):
        raise Blocked('Completion post-commit evidence changed; retain quarantine and do not claim rollback')
    report = {k:plan[k] for k in ('formatVersion','scope','targetDatabase','databaseOid','namespace','namespaceUid','recoveryId')}
    report.update(result,status='STREAM_COMPLETION_HISTORY_RESTORED',activated=False,
                  databaseModified=any(v for k,v in result.items() if k!='afterGuard'),
                  intentSha256=hashlib.sha256((args.output/'intent.json').read_bytes()).hexdigest(),
                  excluded=['new-producer-authority','unknown-post-snapshot-executions','global-writer-quiescence','service-activation'])
    durable_json(args.output/'completion.json',report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('context','namespace','namespace-uid','recovery-id','database','completion-id','runtime-start-bucket','runtime-start-certificate-sha256','broker-digest'):
        parser.add_argument('--'+name,required=True)
    for name in ('restore-report','termination-report','runtime-start-backup','mqtt-state-directory','mqtt-ca-file','mqtt-original-password-file','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--unclaimed-jobs',action='store_true')
    parser.add_argument('--transport',choices=('native','compose'),default='native')
    parser.add_argument('--pg-bin',type=Path);parser.add_argument('--timeout',type=int,default=120)
    args=parser.parse_args();args.vd_tasks=True
    args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    pg=Postgres(args.transport,args.pg_bin,diagnostics=args.output/'postgres')
    try:
        result=apply(pg,args,prepare(pg,args))
    except Exception as error:
        with private_file(args.output/'failure.log','w') as out:traceback.print_exc(file=out)
        durable_json(args.output/'failure.json',{'status':'BLOCKED' if isinstance(error,(Blocked,ValueError)) else 'FAIL',
            'errorType':type(error).__name__,'activated':False,
            'failureLocation':[Path(f.filename).name+':'+str(f.lineno) for f in traceback.extract_tb(error.__traceback__)[-5:]],
            'instruction':'Keep quarantine and rerun identical evidence in a new output directory'})
        print('STREAM completion recovery failed; private diagnostics retained')
        raise SystemExit(2 if isinstance(error,(Blocked,ValueError)) else 1) from None
    print('PASS: original STREAM checkpoint history and completion grants restored; quarantine retained')


if __name__=='__main__':main()
