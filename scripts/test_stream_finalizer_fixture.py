"""Real failed Runner APIs and retained Pods with explicit retry/binding fixtures.

The database inheritance trigger stays enabled. Scheduling and VD readiness are
fixtures; claim, failure, inherited checkpoint download and Result use real APIs.
"""
import hashlib
import json
import subprocess
import time
import urllib.request
import uuid

from postgres_backup import literal
from recovery_kubernetes import PART, MANAGER, RUNTIME
from test_recovery_runtime_starts import q


def job(pg,db,namespace,run,attempt,runtime,create,kube,image,program):
    name='edgeai-'+attempt['id']
    labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/run-id':run,'edgeai.io/task-id':attempt['task_id'],
        'edgeai.io/attempt-id':attempt['id'],'edgeai.io/epoch':str(attempt['epoch'])}
    created=create({'apiVersion':'batch/v1','kind':'Job','metadata':{'namespace':namespace,'name':name,'labels':labels},
        'spec':{'backoffLimit':0,'parallelism':1,'completions':1,'template':{'metadata':{'labels':labels},'spec':{
            'restartPolicy':'Never','terminationGracePeriodSeconds':30,'automountServiceAccountToken':False,
            'nodeSelector':{'kubernetes.io/arch':'amd64'},'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001},
            'containers':[{'name':'runner','image':image,'command':['python3','-B','-c',program],
                'resources':{'requests':{'cpu':'10m','memory':'32Mi'},'limits':{'cpu':'100m','memory':'96Mi'}},
                'securityContext':{'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']}}}]}}}})
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        pods=[p for p in kube.items(namespace,'Pod')[0] if p['metadata'].get('labels',{}).get('edgeai.io/attempt-id')==attempt['id']]
        if len(pods)==1 and pods[0].get('status',{}).get('phase')=='Running':
            response=subprocess.run(kube.command+['-n',namespace,'exec',pods[0]['metadata']['name'],'-c','runner','--','python3','-c',
                "from pathlib import Path;import os;os.kill(int(Path('/tmp/child-ready').read_text()),0)"],capture_output=True,timeout=15)
            if response.returncode==0:break
        time.sleep(.3)
    else:raise AssertionError('Owned real producer did not become ready')
    pg.sql("UPDATE edgeai.runtime_instance SET observed_state='SUBMITTED',job_uid="+q(created['metadata']['uid'])+' WHERE id='+q(runtime),db)
    return pods[0]


def inherited(request,stream,authority,tls):
    reply=request('streams/execution',{},200)
    assert reply['state']=='FINALIZE' and reply['checkpointId']==stream.checkpoint['id']
    assert reply['attemptId']==authority['attemptId'] and reply['epoch']==authority['epoch']
    assert reply['checkpointActor']=={'attemptId':stream.grant['attempt_id'],'epoch':stream.checkpoint['epoch']}
    value=request('streams/checkpoints/finalized',{'checkpointId':stream.checkpoint['id']},200)
    with urllib.request.urlopen(value['download']['url'],context=tls,timeout=15) as response:
        wire=response.read();assert response.status==200
    assert len(wire)==stream.checkpoint['bytes'] and hashlib.sha256(wire).hexdigest()==stream.checkpoint['sha256']


def stop_predecessor(pg,db,kube,namespace,pod,runtime):
    """Do not install the final namespace fence until every successor is captured."""
    name=pod['metadata']['name'];current=kube.read('/api/v1/namespaces/'+namespace+'/pods/'+name)
    assert current['metadata']['uid']==pod['metadata']['uid'] and current['metadata']['labels'][PART]=='edgeai'
    # The wrapper writes CHILD_REAPED only after its actual child has exited.
    subprocess.run(kube.command+['-n',namespace,'exec',name,'-c',current['spec']['containers'][0]['name'],'--',
        'python3','-c','import os,signal;os.kill(1,signal.SIGTERM)'],capture_output=True,timeout=15)
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        current=kube.read('/api/v1/namespaces/'+namespace+'/pods/'+name)
        assert current['metadata']['uid']==pod['metadata']['uid']
        statuses=current.get('status',{}).get('containerStatuses',[])
        if (current.get('status',{}).get('phase')=='Succeeded' and len(statuses)==1 and
                statuses[0].get('state',{}).get('terminated',{}).get('message')=='CHILD_REAPED'):break
        time.sleep(.3)
    else:raise AssertionError('Actual predecessor child termination was not observed')
    supervisor=pg.sql('SELECT vd_runtime_id::text FROM edgeai.vd_task_allocation WHERE runtime_id='+q(runtime),db)
    sql="BEGIN; UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id="+q(runtime)+'; '
    sql+="UPDATE edgeai.runtime_instance SET desired_state='STOPPED',observed_state='TERMINATED',updated_at=now() WHERE id="+q(runtime)+'; '
    if supervisor:
        sql+="UPDATE edgeai.vd_runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id="+q(supervisor)+'; '
        sql+="UPDATE edgeai.vd_runtime SET desired_state='STOPPED',observed_state='TERMINATED',updated_at=now() WHERE id="+q(supervisor)+'; '
        sql+='UPDATE edgeai.vd_runtime_binding b SET closed_at=now(),closed_revision=v.revision FROM edgeai.virtual_device v WHERE b.vd_id=v.id AND b.runtime_id='+q(supervisor)+' AND b.closed_at IS NULL; '
        sql+="UPDATE edgeai.vd_task_allocation SET closed_at=now(),close_reason='POD_GONE' WHERE runtime_id="+q(runtime)+' AND closed_at IS NULL; '
    pg.sql(sql+'COMMIT',db)


def successor(pg,db,namespace,run,previous,runtime,stream,create,kube,image,program,service):
    pending=json.loads(pg.sql('SELECT to_jsonb(r) FROM edgeai.task_retry r WHERE task_id='+q(previous['task_id']),db))
    assert pending['failed_attempt_id']==previous['id'] and pending['namespace']==namespace
    assert pg.sql('SELECT state FROM edgeai.task_attempt WHERE id='+q(previous['id']),db)=='FAILED'
    assert pg.sql('SELECT failure_reason FROM edgeai.runtime_instance WHERE id='+q(runtime),db)=='STORAGE_FAILED'
    deadline=time.monotonic()+5
    while pg.sql('SELECT (available_at<=now() AND now()<deadline)::text FROM edgeai.task_retry WHERE task_id='+q(previous['task_id']),db)!='true':
        assert time.monotonic()<deadline;time.sleep(.1)
    now=pg.sql('SELECT now()::text',db)
    attempt={**previous,'id':str(uuid.uuid4()),'number':previous['number']+1,'epoch':previous['epoch']+1,
        'state':'QUEUED','cause':'RETRY','created_at':now,'updated_at':now}
    row=json.loads(pg.sql("SELECT to_jsonb(r)-'claim_nonce' FROM edgeai.runtime_instance r WHERE id="+q(runtime),db))
    row.update(id=str(uuid.uuid4()),attempt_id=attempt['id'],epoch=attempt['epoch'],job_name=None if previous['mode']=='VD' else 'edgeai-'+attempt['id'],
        claim_nonce=str(uuid.uuid4()),desired_state='RUNNING',observed_state='PENDING',job_uid=None,producer_pod_uid=None,
        node_uid=None,node_name=None,failure_reason=None,created_at=now,updated_at=now,expires_at=pending['deadline'])
    row.pop('runtime_kind')
    def insert(table,value):
        columns=','.join(value)
        return 'INSERT INTO edgeai.'+table+'('+columns+') SELECT '+columns+' FROM jsonb_populate_record(NULL::edgeai.'+table+','+literal(json.dumps(value))+'::jsonb); '
    pg.sql("BEGIN; UPDATE edgeai.task SET state='READY',updated_at="+literal(now)+'::timestamptz WHERE id='+q(previous['task_id'])+'; '+
        insert('task_attempt',attempt)+insert('stream_finalization_recovery',{'attempt_id':attempt['id'],
            'predecessor_attempt_id':previous['id'],'granted_attempt_id':stream.grant['attempt_id'],'created_at':now})+
        insert('runtime_instance',row)+'DELETE FROM edgeai.task_retry WHERE task_id='+q(previous['task_id'])+'; '+
        "UPDATE edgeai.task SET state='RUNNING' WHERE id="+q(previous['task_id'])+'; '+
        "UPDATE edgeai.task_attempt SET state='DISPATCHING' WHERE id="+q(attempt['id'])+'; COMMIT',db)
    if previous['mode']=='VD':
        from test_vd_supervisor_fixture import supervisor,allocate
        vd,vr,pod=supervisor(None,pg,db,namespace,service,create,kube,image,program,vd={'id':previous['vd_id']},generation=attempt['epoch'])
        allocate(pg,db,row['id'],vd['id'],vr,pod)
    else:pod=job(pg,db,namespace,run,attempt,row['id'],create,kube,image,program)
    return attempt,row['id'],pod
