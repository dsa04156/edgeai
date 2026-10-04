"""Mixed BATCH recovery with actual reference TLS workers and retained Kubernetes containers."""
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import ssl
import subprocess
import sys
import time
from types import SimpleNamespace
import uuid

from postgres_backup import Blocked, ROOT, literal, private_file
from recovery_kubernetes import PART, MANAGER, RUNTIME
from recovery_remote_inventory import binding_digest, canonical
from recovery_remote_retire import durable_json
import recovery_remote_retire as remote_retirement
import recovery_kubernetes_workflows as workflows

sys.path.insert(0,str(ROOT/'simulator/tests'))
from test_remote_recovery import RemoteRecoveryTest


def q(value):return literal(value)+'::uuid'


def seed(pg,db,kube,create,namespace,recovery_id,pod_spec,vd_work,kube_attempt,kube_run,kube_runtime,provider,work):
    binding=provider.binding();binding['recoveryId']=recovery_id
    remote=SimpleNamespace(endpoint='https://127.0.0.1:'+str(provider.port),ca_file=provider.root/'cert.pem',
        certificate_sha256=hashlib.sha256(ssl.PEM_cert_to_DER_cert((provider.root/'cert.pem').read_text())).hexdigest(),
        provider_id=binding['providerId'],recovery_id=recovery_id,provider_key='reference',
        recovery_token_file=provider.root/'operator',timeout=60,page_size=2)
    digest=binding_digest(remote)
    connection=work/'mixed-remote-connection.json'
    durable_json(connection,{'endpoint':remote.endpoint,'caFile':str(remote.ca_file),
        'certificateSha256':remote.certificate_sha256,'providerId':remote.provider_id,
        'providerKey':'reference','recoveryTokenFile':str(remote.recovery_token_file)})
    def clone(table,origin,changes):
        pg.sql('INSERT INTO edgeai.'+table+' SELECT (jsonb_populate_record(NULL::edgeai.'+table+',to_jsonb(t)||'+
            literal(json.dumps(changes))+'::jsonb)).* FROM edgeai.'+table+' t WHERE id='+q(origin),db)
    def allocate(row,epoch,mode):
        allocation=str(uuid.uuid4());row['allocation']=allocation
        _,_,raw,_=provider.work(delay=60000 if mode=='running' else 0)
        body=json.loads(raw)
        body['identity']={'allocationId':allocation,'runId':row['run'],'taskId':row['task'],'attemptId':row['attempt'],'epoch':epoch}
        body['expiresAt']=(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()
        raw=canonical(body);request_digest='sha256:'+hashlib.sha256(b'edgeai-reference-allocation-v1\n'+raw).hexdigest()
        pg.sql('BEGIN; INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,claim_nonce,desired_state,observed_state,expires_at,remote_allocation_id,created_at,updated_at) VALUES ('+
            ','.join(q(row[k]) for k in ('runtime','attempt','task','run'))+','+str(epoch)+','+literal(namespace)+','+q(str(uuid.uuid4()))+
            ",'RUNNING','PENDING',"+literal(body['expiresAt'])+'::timestamptz,'+q(allocation)+',now(),now()); '+
            'INSERT INTO edgeai.remote_allocation(id,runtime_id,provider_key,configuration_digest,source_mode,work,request_digest,created_at) VALUES ('+
            q(allocation)+','+q(row['runtime'])+",'reference',"+literal(digest)+",'SYNTHETIC',"+literal(raw.decode())+'::jsonb,'+literal(request_digest)+',now()); COMMIT',db)
        headers={'X-EdgeAI-Run-Id':row['run'],'X-EdgeAI-Task-Id':row['task'],'X-EdgeAI-Attempt-Id':row['attempt'],
            'X-EdgeAI-Epoch':str(epoch),'X-EdgeAI-Request-Digest':request_digest}
        path='/reference/v1/allocations/'+allocation
        assert provider.rpc(path,'PUT',raw,headers,provider.token)[0]==201
        if mode!='allocated':
            assert provider.rpc(path+'/start','POST',headers=headers,credential=provider.token)[0]==200
            deadline=time.monotonic()+5
            while True:
                value=provider.rpc(path,headers=headers,credential=provider.token)[1]
                if value['state']==('RUNNING' if mode=='running' else 'SUCCEEDED'):break
                assert time.monotonic()<deadline;time.sleep(.02)
        return row
    def remote_attempt(row,epoch,cause):
        pg.sql('INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,cause,remote_provider_key,remote_configuration_digest,remote_source_mode,created_at,updated_at) VALUES ('+
            q(row['attempt'])+','+q(row['task'])+','+str(epoch)+','+str(epoch)+",'DISPATCHING','REMOTE',"+literal(cause)+
            ",'reference',"+literal(digest)+",'SYNTHETIC',now(),now())",db)
    fixtures={}
    for name,source,mode in [('to-remote',{'runtime':kube_runtime,'attempt':kube_attempt['id'],'task':kube_attempt['task_id'],'run':kube_run},'running'),
            ('success-pending',vd_work['claimed'],'success'),('cancel-remote',vd_work['assigned'],'allocated')]:
        cancelling=name=='cancel-remote'
        if cancelling:
            pg.sql('UPDATE edgeai.runtime_instance r SET producer_pod_uid=s.producer_pod_uid,node_uid=s.node_uid,node_name=s.node_name '
                'FROM edgeai.runtime_instance s WHERE s.id='+q(vd_work['claimed']['runtime'])+' AND r.id='+q(source['runtime']),db)
        pg.sql("UPDATE edgeai.task_attempt SET state='OFFLOADED' WHERE id="+q(source['attempt'])+
            '; UPDATE edgeai.task SET state='+literal('CANCELLING' if cancelling else 'RUNNING')+',cancellation_reason='+
            ("'RUN_CANCELLED'" if cancelling else 'NULL')+' WHERE id='+q(source['task'])+
            '; UPDATE edgeai.workflow_run SET state='+literal('CANCELLING' if cancelling else 'RUNNING')+' WHERE id='+q(source['run']),db)
        target={**source,'runtime':str(uuid.uuid4()),'attempt':str(uuid.uuid4())}
        remote_attempt(target,2,'OFFLOAD');allocate(target,2,mode)
        op=str(uuid.uuid4())
        pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,target_attempt_id,idempotency_key,request_digest,namespace,state,drain_deadline,'
            'start_timeout_seconds,start_deadline,remote_provider_key,remote_configuration_digest,remote_source_mode,created_at,updated_at) VALUES ('+
            ','.join(q(v) for v in (op,source['task'],source['run'],source['attempt'],target['attempt'],str(uuid.uuid4())))+','+literal('sha256:'+'e'*64)+','+
            literal(namespace)+','+literal('CANCELLING' if cancelling else 'STARTING')+",now()-interval '2 minutes',60,now()-interval '1 second','reference',"+
            literal(digest)+",'SYNTHETIC',now()-interval '3 minutes',now())",db)
        fixtures[name]={'source':source,'target':target,'operation':op}
    source={k:str(uuid.uuid4()) for k in ('runtime','attempt','task','run')}
    frozen={'remote_provider_key':'reference','remote_configuration_digest':digest,'remote_source_mode':'SYNTHETIC'}
    clone('workflow_run',kube_run,{'id':source['run'],'state':'RUNNING','idempotency_key':str(uuid.uuid4()),'mode':'REMOTE',**frozen})
    clone('task',kube_attempt['task_id'],{'id':source['task'],'run_id':source['run'],'state':'RUNNING','cancellation_reason':None,
        'initial_mode':'REMOTE',**{'initial_'+k:v for k,v in frozen.items()}})
    remote_attempt(source,1,'INITIAL');allocate(source,1,'running')
    pg.sql("UPDATE edgeai.task_attempt SET state='OFFLOADED' WHERE id="+q(source['attempt']),db)
    nodes=kube.read('/api/v1/nodes')['items']
    node=next(n for n in nodes if n['metadata']['labels'].get('kubernetes.io/arch')=='amd64' and not n['spec'].get('unschedulable') and
        any(c['type']=='Ready' and c['status']=='True' for c in n['status']['conditions']))
    node_id,node_name=node['metadata']['uid'],node['metadata']['name']
    pg.sql('INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES ('+
        q(node_id)+','+literal(node_name)+",'amd64','linux','READY','1','1Gi','{}',now()) ON CONFLICT(id) DO NOTHING",db)
    target={**source,'runtime':str(uuid.uuid4()),'attempt':str(uuid.uuid4())};target.pop('allocation')
    pg.sql('INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,created_at,updated_at) VALUES ('+
        q(target['attempt'])+','+q(target['task'])+",2,2,'DISPATCHING','NODE',"+q(node_id)+",'OFFLOAD',now(),now())",db)
    name='edgeai-'+target['attempt'];labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/run-id':target['run'],
        'edgeai.io/task-id':target['task'],'edgeai.io/attempt-id':target['attempt'],'edgeai.io/epoch':'2'}
    spec=copy.deepcopy(pod_spec);spec['nodeName']=node_name
    job=create({'apiVersion':'batch/v1','kind':'Job','metadata':{'namespace':namespace,'name':name,'labels':labels},
        'spec':{'backoffLimit':0,'template':{'metadata':{'labels':labels},'spec':spec}}})
    pg.sql('INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,job_uid,claim_nonce,desired_state,observed_state,created_at,updated_at) VALUES ('+
        ','.join(q(target[k]) for k in ('runtime','attempt','task','run'))+',2,'+literal(namespace)+','+literal(name)+','+q(job['metadata']['uid'])+','+
        q(str(uuid.uuid4()))+",'RUNNING','SUBMITTED',now(),now())",db)
    op=str(uuid.uuid4())
    pg.sql('INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,target_attempt_id,target_node_id,idempotency_key,request_digest,namespace,state,'
        'drain_deadline,start_timeout_seconds,start_deadline,created_at,updated_at) VALUES ('+
        ','.join(q(v) for v in (op,source['task'],source['run'],source['attempt'],target['attempt'],node_id,str(uuid.uuid4())))+','+
        literal('sha256:'+'f'*64)+','+literal(namespace)+",'STARTING',now()-interval '2 minutes',60,now()-interval '1 second',now()-interval '3 minutes',now())",db)
    fixtures['to-node']={'source':source,'target':target,'operation':op}
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        pods=[p for p in kube.items(namespace,'Pod')[0] if any(o.get('uid')==job['metadata']['uid'] for o in p['metadata'].get('ownerReferences',[]))]
        if len(pods)==1 and pods[0].get('status',{}).get('phase')=='Running':
            ready=subprocess.run(kube.command+['-n',namespace,'exec',pods[0]['metadata']['name'],'--','python3','-c',
                "from pathlib import Path;import os;os.kill(int(Path('/tmp/child-ready').read_text()),0)"],capture_output=True,timeout=15)
            if ready.returncode==0:break
        time.sleep(.3)
    else:raise AssertionError('Mixed Remote target Job did not become ready')
    assert provider.rpc()[1]['activeWorkers']==2
    fenced=provider.cli(binding)
    assert fenced['workersStopped'] and fenced['controllerRejected']
    assert fenced['observed']['states']=={'ALLOCATED':0,'RUNNING':0,'CANCELLING':0,'SUCCEEDED':1,'FAILED':0,'CANCELLED':3}
    return {'provider':provider,'remote':remote,'connection':connection,'fixtures':fixtures}


def check(pg,db,receipt,options,retire_cli,fingerprints,passed,work,fixture,report):
    provider,fixtures=fixture['provider'],fixture['fixtures']
    def opts(**overrides):return options(db,receipt,offloads=True,unclaimed_jobs=True,remote_connection=fixture['connection'],**overrides)
    def cli(a,expected=0):
        command=[sys.executable,'scripts/recovery_kubernetes_workflows.py']
        for key,value in vars(a).items():
            if value is True:command+=['--'+key.replace('_','-')]
            elif value is not None and value is not False:command+=['--'+key.replace('_','-'),str(value)]
        result=subprocess.run(command,capture_output=True,timeout=180)
        with private_file(work/('mixed-offload-'+uuid.uuid4().hex+'.log')) as log:log.write(result.stdout+result.stderr)
        assert result.returncode==expected,'Mixed workflow CLI differs; private diagnostics retained'
        assert provider.token.encode() not in result.stdout+result.stderr and provider.operator.encode() not in result.stdout+result.stderr
        return json.loads((a.output/('workflows.json' if expected==0 else 'failure.json')).read_text())
    def row(table,rid):return json.loads(pg.sql('SELECT to_jsonb(t) FROM edgeai.'+table+' t WHERE id='+q(rid),db))
    before=fingerprints(db)
    try:workflows.prepare(pg,opts())
    except Blocked:pass
    else:raise AssertionError('Fresh fence was accepted before Remote database retirement')
    assert fingerprints(db)==before
    passed('actual-mixed-provider-fence-does-not-substitute-for-recorded-runtime-and-command-retirement')
    a=SimpleNamespace(**vars(fixture['remote']),database=db,restore_report=receipt,output=work/('remote-retire-'+uuid.uuid4().hex))
    a.output.mkdir(mode=0o700)
    remote_retirement.apply(pg,a,remote_retirement.prepare(pg,a))
    retire_cli(options(db,receipt,unclaimed_jobs=True))
    pristine=fingerprints(db);rows_before=provider.rows()
    ordinary=workflows.prepare(pg,options(db,receipt,offloads=True,unclaimed_jobs=True))
    assert len(ordinary['unresolvedOffloads'])==4 and all(r['reason']=='REMOTE_OFFLOAD_REQUIRES_PROVIDER_EVIDENCE' for r in ordinary['unresolvedOffloads'])
    assert ordinary['remoteEvidence'] is None and fingerprints(db)==pristine
    passed('mixed-transfers-remain-unresolved-without-explicit-fresh-provider-authority')
    for key,value in [('certificateSha256','0'*64),('providerId',str(uuid.uuid4()))]:
        bad=work/('wrong-remote-'+uuid.uuid4().hex+'.json');config=json.loads(fixture['connection'].read_text());config[key]=value;durable_json(bad,config)
        a=opts();a.remote_connection=bad
        assert cli(a,2)['databaseModified'] is False and fingerprints(db)==pristine
    passed('actual-tls-pin-and-provider-identity-mismatches-refuse-all-mixed-recovery-writes')
    provider.stop()
    assert cli(opts(),2)['databaseModified'] is False and fingerprints(db)==pristine
    provider.start();assert provider.rows()==rows_before
    passed('an-offline-provider-cannot-be-replaced-by-a-cached-report-and-restart-retains-its-fence')

    a=opts();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a)
    pending=fixtures['success-pending']
    assert plan['unresolvedOffloads']==[{'operationId':pending['operation'],'reason':'REMOTE_TARGET_OUTCOME_REQUIRES_RECONCILIATION'}]
    expected={fixtures['to-node']['operation']:'CHECK_OFFLOAD_START',fixtures['to-remote']['operation']:'CHECK_OFFLOAD_START',
        fixtures['cancel-remote']['operation']:'CANCEL_OFFLOAD'}
    assert {e['operationId']:e['action'] for e in plan['entries'] if e.get('operationId')}==expected
    assert len(plan['remoteEvidence']['provenRuntimes'])==4
    passed('fresh-reference-evidence-proves-both-transfer-directions-and-preserves-successful-target-output-for-separate-reconciliation')

    original_call=pg.call
    changed=fixtures['to-remote']['target']['allocation']
    previous=row('remote_allocation',changed)['observed_at']
    def racing(tool,arguments,*positional,**keywords):
        if arguments[-2:]==['-f','-']:
            pg.sql("UPDATE edgeai.remote_allocation SET observed_at=observed_at+interval '1 second' WHERE id="+q(changed),db)
        return original_call(tool,arguments,*positional,**keywords)
    pg.call=racing
    try:
        try:workflows.apply(pg,a,plan)
        except RuntimeError:pass
        else:raise AssertionError('An allocation-only concurrent write escaped the mixed transaction guard')
    finally:pg.call=original_call
    raced=fingerprints(db);assert {t for t in pristine if pristine[t]!=raced[t]}=={'remote_allocation'}
    pg.sql('UPDATE edgeai.remote_allocation SET observed_at='+literal(previous)+'::timestamptz WHERE id='+q(changed),db)
    assert fingerprints(db)==pristine
    passed('actual-remote-allocation-only-write-after-final-observation-rolls-back-the-entire-mixed-transaction')

    pg.sql("CREATE FUNCTION edgeai.reject_mixed_recovery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned late write fault'; END $$; "
        'CREATE TRIGGER reject_mixed_recovery BEFORE UPDATE ON edgeai.workflow_run FOR EACH ROW EXECUTE FUNCTION edgeai.reject_mixed_recovery()',db)
    a=opts();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a)
    try:workflows.apply(pg,a,plan)
    except RuntimeError:pass
    else:raise AssertionError('Mixed offload late write fault was not observed')
    pg.sql('DROP TRIGGER reject_mixed_recovery ON edgeai.workflow_run; DROP FUNCTION edgeai.reject_mixed_recovery()',db)
    assert fingerprints(db)==pristine and not (a.output/'workflows.json').exists()
    passed('late-run-trigger-error-rolls-back-both-provider-directions-and-recorded-cancellation')

    old_operations={name:row('task_offload',value['operation']) for name,value in fixtures.items()}
    old_sources={name:row('runtime_instance',value['source']['runtime']) for name,value in fixtures.items()}
    old_pending=row('runtime_instance',pending['target']['runtime'])
    a=opts();a.output.mkdir(mode=0o700);plan=workflows.prepare(pg,a);committed={}
    def lost_reply(tool,arguments,*positional,**keywords):
        value=original_call(tool,arguments,*positional,**keywords)
        if arguments[-2:]==['-f','-']:committed.update(json.loads(value));raise OSError('Injected mixed COMMIT reply loss')
        return value
    pg.call=lost_reply
    try:
        try:workflows.apply(pg,a,plan)
        except OSError:pass
        else:raise AssertionError('Actual mixed COMMIT reply loss not injected')
    finally:pg.call=original_call
    assert committed['offloadsFailed']==2 and committed['offloadsCancelled']==1 and committed['attemptsFailed']==2
    after=fingerprints(db);assert {t for t in pristine if pristine[t]!=after[t]}=={'runtime_instance','task_attempt','task','workflow_run','task_retry','task_offload'}
    assert (a.output/'intent.json').exists() and not (a.output/'workflows.json').exists()
    result=cli(opts());assert not result['databaseModified'] and fingerprints(db)==after and provider.rows()==rows_before
    assert result['unresolvedOffloads']==plan['unresolvedOffloads']
    for name,value in fixtures.items():
        actual=row('task_offload',value['operation']);before_op=old_operations[name]
        assert all(actual[k]==v for k,v in before_op.items() if k not in ('state','failure_reason','updated_at'))
        assert row('runtime_instance',value['source']['runtime'])==old_sources[name]
        assert row('task_attempt',value['source']['attempt'])['state']=='OFFLOADED'
        if name.startswith('to-'):
            assert actual['state']=='FAILED' and actual['failure_reason']=='TARGET_START_TIMEOUT'
            assert row('task_attempt',value['target']['attempt'])['state']=='FAILED'
        elif name=='cancel-remote':assert actual['state']=='CANCELLED'
        else:assert actual==before_op
    assert row('runtime_instance',pending['target']['runtime'])==old_pending
    assert all(row('runtime_instance',fixtures['to-node']['target']['runtime'])[k] is None for k in ('producer_pod_uid','node_uid','node_name'))
    passed('actual-mixed-commit-reply-loss-replays-with-zero-changes-and-preserves-37-tables-source-history-and-successful-remote-work')
    report.update(mixedRemoteOffloadsVerified=True,mixedRemoteAllocations=4,mixedRemoteStartTimeouts=2,
        mixedRemoteCancellations=1,mixedRemoteSuccessesPending=1,mixedRemotePreservedTables=37)
