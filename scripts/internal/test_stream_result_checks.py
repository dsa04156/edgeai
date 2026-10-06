"""Refuse missing/contradictory STREAM barriers through the shared recovery path."""
from copy import deepcopy
import json
from unittest.mock import patch
import uuid

from postgres_backup import Blocked
from recovery_remote_storage import private_json


def check(pg,fixture,options,results,refuse_cli,fingerprints,passed,report):
    args=options();db=args.database;pristine=fingerprints(db)
    refuse_cli(options(stream_results=False))
    refuse_cli(options(mqtt_state_directory=None))
    refuse_cli(options(broker_digest='sha256:'+'0'*64))
    passed('STREAM Result cannot bypass its explicit group barrier and current original broker proof')
    # This is a genuine older backup, not a trigger bypass or fabricated NULL grant.
    refuse_cli(options(5))
    passed('actual pre-grant database snapshot cannot infer a missing barrier from later Result success')
    plan=results.prepare(pg,options());proof=plan['streamEvidence']
    assert len(proof['barriers'])==1 and len(proof['barriers'][0]['taskIds'])==1 and len(proof['checkpoints'])==1
    assert proof['barriers'][0]['grants']==[fixture.grant]
    assert proof['checkpoints'][0]['checkpointId']==fixture.checkpoint['id']
    passed('actual authenticated completion grant joins the original checkpoint bytes and immutable Result authority')
    original=results.database_inventory
    def contract(state):
        return next(c for c in state['contracts'] if c['taskId']==fixture.checkpoint['task_id'])
    def change_execution(state):
        target=contract(state);work=json.loads(target['workJson'])
        work['spec']['stream']['stepTimeoutSeconds']+=1
        target['workJson']=json.dumps(work)
    variants=[('missingGrant',lambda s:s['completions'].clear()),
        ('missingDeviceEnd',lambda s:s['deviceCompletions'].clear()),
        ('wrongDeviceCursor',lambda s:s['deviceCompletions'][0].update(sequence=3)),
        ('wrongGrantTime',lambda s:s['deviceCompletions'][0].update(granted_at='2000-01-01T00:00:00Z')),
        ('wrongMembership',lambda s:s['bindings'][0].update(route_digest='sha256:'+'0'*64)),
        ('wrongEpoch',lambda s:s['generations'][0].update(consumer_epoch=99)),
        ('unclosedRoute',lambda s:s['generations'][0].update(closed_at=None)),
        ('unretiredProducer',lambda s:s['runtimes'][0].update(desired_state='RUNNING')),
        ('wrongCheckpointSummary',lambda s:s['checkpoints'][0]['summary_json'].update(stateBytes=99)),
        ('wrongCheckpointVersion',lambda s:s['checkpoints'][0].update(object_version=str(uuid.uuid4()))),
        ('wrongService',lambda s:contract(s).update(serviceProfileVersionId=str(uuid.uuid4()))),
        ('wrongExecution',change_execution),
        ('wrongCheckpointGrantTime',lambda s:s['completions'][0].update(granted_at='2000-01-01T00:00:00Z'))]
    for name,change in variants:
        def altered(*a,**kw):
            catalog=original(*a,**kw)
            if 'stream' in catalog:catalog=deepcopy(catalog);change(catalog['stream'])
            return catalog
        with patch.object(results,'database_inventory',altered):
            try:results.prepare(pg,options())
            except Blocked:pass
            else:raise AssertionError('Contradictory STREAM barrier accepted: '+name)
        assert fingerprints(db)==pristine
    passed('missing group grants, Device END/cursors, membership, actor, sealed bytes and grant times refuse all writes')
    manifest_path=args.runtime_start_backup/'manifest.json';manifest,sha=private_json(manifest_path)
    original_bytes=manifest_path.read_bytes()
    changed={**manifest,'versions':[v for v in manifest['versions'] if v['key']!=fixture.checkpoint['object_key']]}
    try:
        manifest_path.write_text(json.dumps(changed));refuse_cli(options())
    finally:manifest_path.write_bytes(original_bytes)
    assert private_json(manifest_path)[1]==sha and fingerprints(db)==pristine
    passed('an uncaptured sealed checkpoint cannot be substituted by a later Result file')
    # Keep the actual source broker state distinct from an old observation report.
    from recovery_device_test_support import mqtt
    cfg=fixture.broker.cfg
    from types import SimpleNamespace
    import time
    def toggle(command):
        active=SimpleNamespace(**vars(cfg),deadline=time.monotonic()+cfg.timeout)
        with mqtt.Connection(active,fixture.broker.replacement) as connection:
            connection.call(command,username=fixture.broker.device_name)
    toggle('enableClient')
    try:refuse_cli(options())
    finally:toggle('disableClient')
    assert fingerprints(db)==pristine
    passed('actual original broker principal reactivation invalidates a previously valid STREAM Result plan')
    report.update(streamResultCases=6,streamCompletionGrantFromActualApi=True,streamCheckpointVersionsVerified=1,
        streamBarrierAlteredObservations=len(variants),streamPreGrantSnapshotRefused=True)
