"""Read-only comparison of a quarantined Device journal and the exact restored PostgreSQL history."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import uuid

from postgres_backup import Blocked, Postgres, literal
from recovery_kubernetes import database_inventory
from recovery_remote_inventory import canonical
import device_journal_backup as journals
import private_material_backup as material
from edgeai_runner.stream_protocol import decode

TABLES = ('flyway_schema_history','device','device_session','workflow_run','task','task_attempt',
    'data_route','route_generation','stream_run_configuration','stream_run_binding','stream_device_binding',
    'stream_checkpoint','stream_device_completion','stream_task_completion','task_offload','task_offload_member',
    'profile_version','task_definition')
GUARD = 'SELECT jsonb_build_object(' + ','.join(literal(name) +
    ",(SELECT encode(sha256(convert_to(coalesce(string_agg(encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' "
    "ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex') FROM edgeai." + name + ' t)' for name in TABLES) + ')'


def read_json(path):
    with material.private_input(path, 1048576) as source: raw = source.read()
    return material.json_value(raw), hashlib.sha256(raw).hexdigest()


def restored_journal(directory):
    directory = directory.absolute(); journals.private_directory(directory)
    receipt, receipt_sha = read_json(directory / 'restore-report.json')
    marker, marker_sha = read_json(directory / 'source/journal/recovery.json')
    keys = ('formatVersion','scope','status','bundleId','snapshotSha256','activated')
    if (any(marker.get(k) != receipt.get(k) for k in keys) or set(marker) != set(keys) or
            marker['formatVersion'] != 1 or marker['scope'] != journals.SCOPE or
            marker['status'] != 'QUARANTINED_DEVICE_JOURNAL' or marker['activated'] is not False or
            not re.fullmatch('[0-9a-f]{32}',marker['bundleId']) or
            not re.fullmatch('[0-9a-f]{64}',marker['snapshotSha256']) or
            not re.fullmatch('[0-9a-f]{64}',receipt.get('ciphertextSha256',''))):
        raise Blocked('A matching quarantined Device restore receipt and marker are required')
    wire = journals.capture(directory / 'source', 30, _quarantined=True)
    value, bindings, _ = journals.validate(wire)
    if (hashlib.sha256(wire).hexdigest() != marker['snapshotSha256'] or receipt.get('routes') != len(bindings) or
            receipt.get('pendingFrames') != sum(len(r['frames']) for r in value['routes']) or
            receipt.get('completionIntent') != (value['completion'] is not None) or
            read_json(directory / 'restore-report.json')[1] != receipt_sha or
            read_json(directory / 'source/journal/recovery.json')[1] != marker_sha):
        raise Blocked('Restored Device bytes or receipt changed')
    return value, bindings, {'journalRestoreReportSha256':receipt_sha,'journalMarkerSha256':marker_sha,
        'journalSnapshotSha256':marker['snapshotSha256'],'journalBundleId':marker['bundleId']}


def query(run_id, actor):
    for value in (run_id,actor.id,actor.device_id):
        if str(uuid.UUID(value)) != value: raise ValueError('Canonical recovery identity required')
    run, device, session = (literal(v)+'::uuid' for v in (run_id,actor.device_id,actor.id))
    return ("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT jsonb_build_object("
        "'database',current_database(),'oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),"
        "'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),"
        "'readOnly',current_setting('transaction_read_only'),'migrations',(SELECT jsonb_agg(jsonb_build_object("
        "'version',version,'success',success)) FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),"
        "'guard',("+GUARD+"),'run',(SELECT jsonb_build_object('id',id,'state',state) FROM edgeai.workflow_run WHERE id="+run+"),"
        "'configured',EXISTS(SELECT FROM edgeai.stream_run_configuration WHERE run_id="+run+"),"
        "'brokerDigest',(SELECT broker_digest FROM edgeai.stream_run_configuration WHERE run_id="+run+"),"
        "'frozen',EXISTS(SELECT FROM edgeai.stream_run_binding WHERE run_id="+run+"),"
        "'device',(SELECT jsonb_build_object('state',state,'sessionEpoch',session_epoch,'profileId',profile_version_id,'sourceMode',source_mode)"
        " FROM edgeai.device WHERE id="+device+"),"
        "'session',(SELECT jsonb_build_object('deviceId',device_id,'epoch',epoch,'closed',closed_at IS NOT NULL)"
        " FROM edgeai.device_session WHERE id="+session+"),"
        "'activeOffload',EXISTS(SELECT FROM edgeai.task_offload WHERE run_id="+run+" AND state IN ('DRAINING','STARTING','CANCELLING')),"
        "'routes',(SELECT coalesce(jsonb_agg(jsonb_build_object('route',to_jsonb(r),"
        "'pin',(SELECT to_jsonb(p) FROM edgeai.stream_device_binding p WHERE p.route_id=r.id),"
        "'generations',(SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY generation),'[]'::jsonb) FROM edgeai.route_generation g WHERE g.route_id=r.id),"
        "'latestConsumerEpoch',(SELECT max(epoch) FROM edgeai.task_attempt WHERE task_id=r.consumer_task_id),"
        "'checkpoint',(SELECT to_jsonb(c) FROM edgeai.stream_checkpoint c WHERE c.task_id=r.consumer_task_id ORDER BY serial DESC LIMIT 1),"
        "'completions',(SELECT coalesce(jsonb_agg(to_jsonb(c)),'[]'::jsonb) FROM edgeai.stream_device_completion c JOIN edgeai.route_generation g ON g.id=c.generation_id WHERE g.route_id=r.id)"
        ") ORDER BY r.id),'[]'::jsonb) FROM edgeai.data_route r WHERE r.run_id="+run+" AND r.source_device_id="+device+")); COMMIT;")


def classify(catalog, value, bindings, run_id):
    actor = bindings[0].producer; conflicts = []; routes = []
    def conflict(reason, route_id=None): conflicts.append({'reason':reason, **({'routeId':route_id} if route_id else {})})
    if catalog['run'] is None or not catalog['configured'] or not catalog['frozen']:
        conflict('RUN_OR_FROZEN_STREAM_MEMBERSHIP_MISSING')
    device, session = catalog['device'], catalog['session']
    if (device is None or session is None or session['deviceId'] != actor.device_id or session['epoch'] != actor.epoch):
        conflict('DEVICE_SESSION_IDENTITY_MISMATCH')
    elif device['state'] != 'ACTIVE' or device['sessionEpoch'] != actor.epoch or session['closed']:
        conflict('DEVICE_SESSION_NO_LONGER_CURRENT')
    if catalog['activeOffload']: conflict('ACTIVE_TRANSFER_REQUIRES_RECONCILIATION')
    expected = {b.route_id:b for b in bindings}; actual = {r['route']['id']:r for r in catalog['routes']}
    if expected.keys() != actual.keys(): conflict('COMPLETE_DEVICE_ROUTE_SET_DIFFERS')
    cursors = {r['routeId']:r for r in value['routes']}
    intent = value['completion']
    if intent is not None and intent['runId'] != run_id: conflict('COMPLETION_RUN_IDENTITY_MISMATCH')
    intent_routes = {} if intent is None else {r['routeId']:r for r in intent['routes']}
    for route_id in sorted(expected.keys() & actual.keys()):
        binding = expected[route_id]; context = actual[route_id]; route = context['route']; pin = context['pin']; local = cursors[route_id]
        if (pin is None or pin['run_id'] != run_id or pin['device_id'] != actor.device_id or
                pin['session_id'] != actor.id or pin['epoch'] != actor.epoch):
            conflict('IMMUTABLE_DEVICE_PIN_MISMATCH',route_id)
        if device and (route['source_profile_version_id'] != device['profileId'] or route['source_mode'] != device['sourceMode']):
            conflict('IMMUTABLE_DEVICE_SOURCE_MISMATCH',route_id)
        generations = context['generations']; generation = next((g for g in generations if g['generation'] == binding.generation),None)
        if generation is None:
            conflict('JOURNAL_GENERATION_MISSING_FROM_DATABASE',route_id); continue
        if generation['broker_digest'] != catalog['brokerDigest']: conflict('GENERATION_BROKER_CONFIGURATION_MISMATCH',route_id)
        if generation['generation'] != max(g['generation'] for g in generations): conflict('JOURNAL_GENERATION_REQUIRES_HANDOVER',route_id)
        if (generation['producer_session_id'] != actor.id or generation['producer_epoch'] != actor.epoch or
                generation['source_device_id'] != actor.device_id): conflict('GENERATION_PRODUCER_MISMATCH',route_id)
        if generation['consumer_epoch'] != context['latestConsumerEpoch']: conflict('CONSUMER_ATTEMPT_CHANGED',route_id)
        for frame in local['frames']:
            decoded = decode(journals.encode(frame))
            if decoded.kind == 'DATA' and (decoded.media_type != route['media_type'] or len(decoded.payload) > route['max_payload_bytes']):
                conflict('PENDING_FRAME_VIOLATES_ROUTE_CONTRACT',route_id); break
        checkpoint = context['checkpoint']; consumer = {'received':0,'committed':0,'ended':False}
        checkpoint_id = None
        if checkpoint is not None:
            checkpoint_id = checkpoint['id']
            summary = checkpoint['summary_json']; matches = [r for r in summary['routes'] if r['routeId'] == route_id]
            inputs = [b for b in summary['manifest']['inputs'] if b['routeId'] == route_id]
            if (checkpoint['attempt_id'] != generation['consumer_attempt_id'] or checkpoint['epoch'] != generation['consumer_epoch'] or
                    generation['id'] not in checkpoint['generation_ids'] or len(matches) != 1 or len(inputs) != 1 or
                    inputs[0] != {'routeId':route_id,'generation':binding.generation,'producer':actor.document()}):
                conflict('CONSUMER_CHECKPOINT_BINDING_MISMATCH',route_id); continue
            consumer = matches[0]
        if local['committed'] > consumer['committed']: conflict('ACKNOWLEDGED_SOURCE_FRAMES_MISSING_FROM_CONSUMER_RECOVERY',route_id)
        if consumer['received'] > local['received']: conflict('CONSUMER_AHEAD_OF_DEVICE_SNAPSHOT',route_id)
        if consumer['ended'] and (not local['ended'] or consumer['received'] != local['received']): conflict('END_POSITION_MISMATCH',route_id)
        completion_row = next((r for r in context['completions'] if r['generation_id'] == generation['id']),None)
        if completion_row is not None and (not local['ended'] or local['committed'] != local['received'] or completion_row['sequence'] != local['received']):
            conflict('RECORDED_DEVICE_COMPLETION_DIFFERS',route_id)
        if route_id in intent_routes and (intent_routes[route_id]['generationId'] != generation['id'] or
                intent_routes[route_id]['sequence'] != local['received']): conflict('LOCAL_COMPLETION_GENERATION_DIFFERS',route_id)
        routes.append({'routeId':route_id,'generationId':generation['id'],'generation':binding.generation,
            'databaseGenerationState':generation['state'],'consumerCheckpointId':checkpoint_id,
            'sourceGenerated':local['received'],'sourceAcknowledged':local['committed'],
            'consumerReceived':consumer['received'],'consumerCommitted':consumer['committed'],
            'sourceEnded':local['ended'],'consumerEnded':consumer['ended'],
            'replayFromSequence':consumer['committed']+1 if consumer['committed']<local['received'] else None})
    return routes, conflicts


def compare(pg, args):
    value, bindings, evidence = restored_journal(args.journal_restore)
    sql = query(args.run_id,bindings[0].producer)
    catalog = database_inventory(pg,args.database,args.restore_report,sql)
    routes, conflicts = classify(catalog,value,bindings,args.run_id)
    after = database_inventory(pg,args.database,args.restore_report,sql)
    current, _, current_evidence = restored_journal(args.journal_restore)
    if catalog != after or evidence != current_evidence or current != value:
        raise Blocked('Database or Device restore changed during comparison')
    return {'formatVersion':1,'scope':'restored-device-journal-database-comparison',
        'status':'DEVICE_JOURNAL_CONFLICTS' if conflicts else 'DEVICE_JOURNAL_METADATA_MATCHED',
        'targetDatabase':args.database,'databaseOid':catalog['oid'],'restoreReportSha256':catalog['restoreReportSha256'],
        'runId':args.run_id,'brokerDigest':catalog['brokerDigest'],**evidence,'databaseGuardSha256':hashlib.sha256(canonical(catalog['guard'])).hexdigest(),
        'routes':routes,'conflicts':conflicts,'databaseModified':False,'journalModified':False,
        'activated':False,'checkpointObjectsVerified':False,'producerQuiescenceProven':False,
        'verifiedAt':datetime.now(timezone.utc).isoformat(),
        'excluded':['checkpoint-object-verification','original-producer-retirement','broker-authority','service-activation']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('database','run-id'):parser.add_argument('--'+name,required=True)
    for name in ('restore-report','journal-restore','output'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--transport',choices=['native','compose'],default='native');parser.add_argument('--pg-bin',type=Path)
    args=parser.parse_args();created=False
    try:
        args.output.mkdir(mode=0o700);created=True
        result=compare(Postgres(args.transport,args.pg_bin,diagnostics=args.output/'postgres'),args)
        material.write_json(args.output/'comparison.json',result)
        print(result['status']+': '+str(len(result['conflicts']))+' conflicts; journal and database remain quarantined')
        return 2 if result['conflicts'] else 0
    except Exception as error:
        blocked=isinstance(error,Blocked)
        if created:material.write_json(args.output/'failure.json',{'status':'BLOCKED' if blocked else 'FAIL',
            'failureType':type(error).__name__,'databaseModified':False,'journalModified':False,'activated':False})
        print(('BLOCKED' if blocked else 'FAIL')+': Device journal comparison; private diagnostics retained')
        return 2 if blocked else 1


if __name__=='__main__':raise SystemExit(main())
