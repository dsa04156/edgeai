"""Peer-wide authority refusals for mixed STREAM group Result recovery."""
from copy import deepcopy
from datetime import datetime,timedelta
import json
import time
from types import SimpleNamespace
from unittest.mock import patch
import uuid

from postgres_backup import Blocked
from test_recovery_runtime_starts import q


def check(pg,options,members,stream,results,refuse_cli,fingerprints,passed,report):
    db=options().database;pristine=fingerprints(db);original=results.database_inventory
    for selected,peer in [('root','peer'),('peer','root')]:
        plan=results.prepare(pg,options(key=selected));evidence=plan['streamEvidence']
        assert len(plan['entries'])==1 and len(evidence['barriers'])==1 and len(evidence['checkpoints'])==2
        assert set(evidence['barriers'][0]['taskIds'])=={m['attempt']['task_id'] for m in members.values()}
        assert len(evidence['barriers'][0]['grants'])==2
        other=members[peer];cp=stream.checkpoints_by_task[peer]
        def completion(s):return next(c for c in s['completions'] if c['attempt_id']==other['attempt']['id'])
        def runtime(s):return next(r for r in s['runtimes'] if r['id']==other['runtime'])
        def checkpoint(s):return next(c for c in s['checkpoints'] if c['id']==cp['id'])
        def split_grant(s):
            c=completion(s);c['granted_at']=(datetime.fromisoformat(c['granted_at'])+timedelta(microseconds=1)).isoformat()
        changes=[('peerGrantMissing',lambda s:completion(s).update(granted_at=None)),
            ('peerGrantTime',lambda s:completion(s).update(granted_at='2000-01-01T00:00:00Z')),
            ('nonAtomicGrant',split_grant),
            ('peerNotRetired',lambda s:runtime(s).update(desired_state='RUNNING')),
            ('peerCheckpointVersion',lambda s:checkpoint(s).update(object_version=str(uuid.uuid4()))),
            ('peerCheckpointSummary',lambda s:checkpoint(s)['summary_json'].update(stateBytes=99)),
            ('peerTaskProducerEpoch',lambda s:next(g for g in s['generations'] if g['source_task_id'] is not None).update(producer_epoch=99))]
        for name,change in changes:
            def altered(*a,**kw):
                catalog=original(*a,**kw)
                if 'stream' in catalog:catalog=deepcopy(catalog);change(catalog['stream'])
                return catalog
            with patch.object(results,'database_inventory',altered):
                try:results.prepare(pg,options(key=selected))
                except Blocked as error:
                    if name=='nonAtomicGrant':assert 'granted atomically' in str(error)
                else:raise AssertionError('Unselected peer contradiction accepted: '+name)
            assert fingerprints(db)==pristine
        passed(selected+' recovery requires both original group grants, retired producers and exact peer checkpoint/actor observations')
        manifest_path=options().runtime_start_backup/'manifest.json';raw=manifest_path.read_bytes();manifest=json.loads(raw)
        manifest['versions']=[v for v in manifest['versions'] if v['key']!=cp['object_key']]
        try:
            manifest_path.write_text(json.dumps(manifest));refuse_cli(options(key=selected))
        finally:manifest_path.write_bytes(raw)
        passed(selected+' full CLI refuses a backup missing the unselected peer checkpoint without changing any database row')
        # A real change after observation must invalidate the SQL transaction.
        a=options(key=selected);a.output.mkdir(mode=0o700);plan=results.prepare(pg,a)
        statement='UPDATE edgeai.task SET updated_at=updated_at{sign}interval \'1 second\' WHERE id='+q(other['attempt']['task_id'])
        pg.sql(statement.format(sign='+'),db);changed=fingerprints(db)
        try:
            try:results.apply(pg,a,plan)
            except (Blocked,RuntimeError):pass
            else:raise AssertionError('Changed unselected peer accepted after observation')
            assert fingerprints(db)==changed and not (a.output/'results.json').exists()
        finally:pg.sql(statement.format(sign='-'),db)
        assert fingerprints(db)==pristine
        passed(selected+' prepared recovery refuses an actual concurrent unselected peer change and preserves that change')
    import recovery_mqtt_fence as mqtt
    cfg=stream.broker.cfg
    name='edgeai-task-'+members['peer']['attempt']['id']+'-'+str(members['peer']['attempt']['epoch'])
    def toggle(command):
        active=SimpleNamespace(**vars(cfg),deadline=time.monotonic()+cfg.timeout)
        with mqtt.Connection(active,stream.broker.replacement) as connection:connection.call(command,username=name)
    toggle('enableClient')
    try:
        for key in members:refuse_cli(options(key=key))
    finally:toggle('disableClient')
    assert fingerprints(db)==pristine
    passed('actual VD peer broker principal reactivation blocks both runtime kinds despite an older successful fence report')
    report.update(unselectedPeerAlteredObservations=14,missingPeerCheckpointCliRefusals=2,
        concurrentPeerChangeRefusals=2,reactivatedPeerCliRefusals=2)
