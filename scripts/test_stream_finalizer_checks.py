"""Exercise inherited completion authority through real restored result recovery."""
from copy import deepcopy
import json
from unittest.mock import patch
import uuid

from postgres_backup import Blocked,literal
from test_recovery_runtime_starts import q


def check(pg,fixture,options,results,refuse_cli,fingerprints,passed,report):
    args=options();db=args.database;pristine=fingerprints(db)
    state=json.loads(pg.sql(results.stream_results.catalog_query(),db))
    task=fixture.checkpoint['task_id'];run=fixture.checkpoint['run_id']
    attempts=sorted((a for a in state['attempts'] if a['task_id']==task),key=lambda a:a['number'])
    links=state['inheritance']
    assert len(attempts)==3 and [a['epoch'] for a in attempts]==[1,2,3]
    assert [a['state'] for a in attempts]==['FAILED','FAILED','RUNNING']
    assert len(links)==2 and all(l['granted_attempt_id']==attempts[0]['id'] for l in links)
    assert len(state['completions'])==len(state['checkpoints'])==len(state['generations'])==1
    plan=results.prepare(pg,args)
    assert plan['entries'][0]['attemptId']==attempts[2]['id']
    assert plan['streamEvidence']['barriers'][0]['grants']==[fixture.grant]
    assert fixture.grant['attempt_id']==attempts[0]['id']
    fixture.failed_finalizer_history={
        'attempts':sorted((a for a in state['attempts'] if a['id'] in {p['id'] for p in attempts[:2]}),key=lambda a:a['id']),
        'runtimes':sorted((r for r in state['runtimes'] if r['attempt_id'] in {p['id'] for p in attempts[:2]}),key=lambda r:r['id'])}
    passed('restored finalizer epoch 3 inherits epoch 1 grant/checkpoint through both failed predecessors without new routes or grants')
    original=results.database_inventory
    def latest(s):return next(a for a in s['attempts'] if a['id']==attempts[2]['id'])
    def predecessor(s):return next(a for a in s['attempts'] if a['id']==attempts[1]['id'])
    def link(s):return next(l for l in s['inheritance'] if l['attempt_id']==attempts[2]['id'])
    variants=[('missingChain',lambda s:s['inheritance'].clear()),
        ('missingMiddleLink',lambda s:s['inheritance'].__setitem__(slice(None),[l for l in s['inheritance'] if l['attempt_id']!=attempts[1]['id']])),
        ('wrongGrant',lambda s:link(s).update(granted_attempt_id=str(uuid.uuid4()))),
        ('skippedPredecessor',lambda s:link(s).update(predecessor_attempt_id=attempts[0]['id'])),
        ('cycle',lambda s:link(s).update(predecessor_attempt_id=attempts[2]['id'])),
        ('wrongTask',lambda s:predecessor(s).update(task_id=str(uuid.uuid4()))),
        ('predecessorSucceeded',lambda s:predecessor(s).update(state='SUCCEEDED')),
        ('offloadNotRetry',lambda s:latest(s).update(cause='OFFLOAD')),
        ('remoteNotFinalizer',lambda s:latest(s).update(mode='REMOTE')),
        ('skippedEpoch',lambda s:latest(s).update(epoch=4)),
        ('skippedNumber',lambda s:latest(s).update(number=4)),
        ('changedLinkTime',lambda s:link(s).update(created_at='2000-01-01T00:00:00Z'))]
    for name,change in variants:
        def altered(*a,**kw):
            catalog=original(*a,**kw)
            if 'stream' in catalog:catalog=deepcopy(catalog);change(catalog['stream'])
            return catalog
        with patch.object(results,'database_inventory',altered):
            try:results.prepare(pg,options())
            except Blocked as error:assert 'Finalizer' in str(error),name
            else:raise AssertionError('Contradictory finalizer inheritance accepted: '+name)
        assert fingerprints(db)==pristine
    passed('12 missing or contradictory finalizer chain observations are rejected at the inherited-grant boundary')
    policy=json.loads(pg.sql('SELECT to_jsonb(w) FROM edgeai.workflow_run w WHERE id='+q(run),db))
    changes=[('retry_max_attempts','2',str(policy['retry_max_attempts'])),
        ('retry_backoff_seconds','300',str(policy['retry_backoff_seconds'])),
        ('retry_max_elapsed_seconds','1',str(policy['retry_max_elapsed_seconds'])),
        ('retry_on',"ARRAY['RUNNER_FAILED']::text[]",'ARRAY['+','.join(literal(x) for x in policy['retry_on'])+']::text[]')]
    for column,value,restore in changes:
        pg.sql('UPDATE edgeai.workflow_run SET '+column+'='+value+' WHERE id='+q(run),db)
        try:refuse_cli(options())
        finally:pg.sql('UPDATE edgeai.workflow_run SET '+column+'='+restore+' WHERE id='+q(run),db)
        assert fingerprints(db)==pristine
    passed('real restored retry budgets, backoff, deadline and allowed failure policy contradictions block the full CLI without writes')
    report.update(finalizerChainValidated=True,finalizerSuccessors=2,actualFailedRunnerRequests=2,
        finalizerCheckpointDownloads=2,finalizerAlteredObservations=len(variants),finalizerPolicyCliRefusals=len(changes),
        retrySchedulingBoundary='EXPLICIT_DATABASE_FIXTURE_WITH_ORIGINAL_RETRY_QUEUE_AND_ENABLED_INHERITANCE_TRIGGER')


def preserved(pg,fixture,options,results,passed,report):
    original=fixture.failed_finalizer_history
    for index in range(5):
        state=json.loads(pg.sql(results.stream_results.catalog_query(),options(index).database))
        for key,expected in original.items():
            ids={row['id'] for row in expected}
            assert sorted((row for row in state[key] if row['id'] in ids),key=lambda row:row['id'])==expected
        assert state['completions']==[fixture.grant] and len(state['checkpoints'])==len(state['generations'])==1
        assert state['checkpoints'][0]['id']==fixture.checkpoint['id']
        assert len(state['inheritance'])==2
    passed('all five successful restored snapshots retain both failed attempts, producer histories and the original single grant/checkpoint/generation')
    report.update(inheritedFinalizerFullCliVerified=True,failedFinalizerHistoryPreserved=True)
