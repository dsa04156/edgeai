"""Owned VD database allocation fixture with a real Secret-owned supervisor Pod.

Readiness and allocation history are explicit fixtures. Child claim and Result
publication subsequently use the actual TLS API and Pod-bound TokenReview.
"""
import hashlib
import json
import secrets
import time
import uuid

from postgres_backup import literal, private_file
from recovery_kubernetes import PART, MANAGER, VD
from test_recovery_runtime_starts import q


def seed(api,Api,pg,database,work,namespace,service,workflow_version,create,kube,image,program):
    spec={'apiVersion':'edgeai.vd/v1','type':'emulation','serviceProfileVersionId':service,
        'sources':{},'state':{'mode':'STATELESS'},
        'runtime':{'maxConcurrentTasks':2,'startupTimeoutSeconds':120,'drainTimeoutSeconds':120}}
    profile=api.request('POST','profiles/VD',{'key':'result-vd-profile','version':'1.0.0','spec':spec},201)
    vd=api.request('POST','virtual-devices',{'key':'result-vd','displayName':'Result VD','profileVersionId':profile['id'],
        'sources':[],'placement':{'mode':'AUTO'}},201)
    vr=str(uuid.uuid4());pod_name='edgeai-vd-'+vr
    configuration={'sources':{},'serviceAccount':'default','controlPlane':'https://fixture.invalid','serviceProfileVersionId':service,'namespace':namespace,'placementMode':'AUTO',
        'targetNodeId':None,'targetNodeName':None,'maxConcurrentTasks':2,'startupSeconds':120,'drainSeconds':120}
    raw=json.dumps(configuration,sort_keys=True,separators=(',',':'))
    pg.sql('BEGIN; INSERT INTO edgeai.vd_runtime(id,vd_id,generation,requested_revision,configuration,configuration_digest,'
        'namespace,pod_name,claim_nonce,desired_state,observed_state,startup_deadline,created_at,updated_at) VALUES ('+
        q(vr)+','+q(vd['id'])+',1,0,'+literal(raw)+'::jsonb,'+literal('sha256:'+hashlib.sha256(b'edgeai-vd-runtime-configuration-v1\n'+raw.encode()).hexdigest())+','+
        literal(namespace)+','+literal(pod_name)+','+q(str(uuid.uuid4()))+",'RUNNING','PENDING',now()+interval '120s',now(),now()); "+
        'INSERT INTO edgeai.vd_runtime_binding(id,vd_id,runtime_id,opened_revision,opened_at) VALUES ('+
        q(str(uuid.uuid4()))+','+q(vd['id'])+','+q(vr)+',0,now()); '+
        'INSERT INTO edgeai.vd_runtime_command(id,runtime_id,kind,completed,available_at,created_at,updated_at) VALUES ('+
        q(str(uuid.uuid4()))+','+q(vr)+",'CREATE',true,now(),now(),now()); COMMIT",database)
    labels={PART:'edgeai',MANAGER:VD,'edgeai.io/vd-runtime-id':vr,'edgeai.io/vd-id':vd['id'],'edgeai.io/generation':'1'}
    secret=create({'apiVersion':'v1','kind':'Secret','metadata':{'namespace':namespace,'name':pod_name+'-claim','labels':labels},
        'immutable':True,'type':'Opaque'})
    create({'apiVersion':'v1','kind':'Pod','metadata':{'namespace':namespace,'name':pod_name,'labels':labels,
        'ownerReferences':[{'apiVersion':'v1','kind':'Secret','name':secret['metadata']['name'],
            'uid':secret['metadata']['uid'],'controller':True,'blockOwnerDeletion':True}]},'spec':{
        'restartPolicy':'Never','terminationGracePeriodSeconds':30,'automountServiceAccountToken':False,
        'nodeSelector':{'kubernetes.io/arch':'amd64'},'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001},
        'containers':[{'name':'vd-supervisor','image':image,'command':['python3','-B','-c',program],
            'resources':{'requests':{'cpu':'10m','memory':'32Mi'},'limits':{'cpu':'100m','memory':'96Mi'}},
            'securityContext':{'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']}}}]}})
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        pod=kube.read('/api/v1/namespaces/'+namespace+'/pods/'+pod_name)
        if pod.get('status',{}).get('phase')=='Running' and all(c.get('ready') for c in pod['status'].get('containerStatuses',[])):break
        time.sleep(.3)
    else:raise AssertionError('Owned VD supervisor did not become ready')
    node=kube.read('/api/v1/nodes/'+pod['spec']['nodeName']);session=str(uuid.uuid4())
    pg.sql("UPDATE edgeai.vd_runtime SET observed_state='READY',pod_uid="+q(pod['metadata']['uid'])+
        ',node_uid='+q(node['metadata']['uid'])+',node_name='+literal(node['metadata']['name'])+',session_id='+q(session)+
        ",ready_at=now(),lease_until=now()+interval '60 seconds',updated_at=now() WHERE id="+q(vr),database)
    api.close();key=work/'vd-fixture.key'
    with private_file(key,'w') as target:target.write(secrets.token_hex(32))
    enabled=None
    try:
        enabled=Api(database,work,extra_env={'EDGEAI_RUNTIME_ENABLED':'true','EDGEAI_VD_ENABLED':'true',
            'EDGEAI_RUNTIME_WORKER_ENABLED':'false','EDGEAI_RUNTIME_NAMESPACE':namespace,'EDGEAI_RUNNER_KEY_FILE':str(key)})
        run=enabled.request('POST','workflow-runs',{'workflowVersionId':workflow_version,
            'execution':{'mode':'VD','vdId':vd['id']},'parameters':{}},201,str(uuid.uuid4()))
    finally:
        if enabled is not None:enabled.close()
        key.unlink(missing_ok=True)
    attempt=json.loads(pg.sql('SELECT to_jsonb(a) FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id WHERE t.run_id='+q(run['id']),database))
    child=pg.sql('SELECT id::text FROM edgeai.task WHERE run_id='+q(run['id'])+' AND id<>'+q(attempt['task_id']),database)
    runtime=pg.sql('SELECT id::text FROM edgeai.runtime_instance WHERE attempt_id='+q(attempt['id']),database)
    pg.sql('INSERT INTO edgeai.vd_task_allocation(id,runtime_id,vd_id,vd_runtime_id,generation,session_id,pod_uid,slot,assigned_sequence,assigned_at) VALUES ('+
        ','.join(q(v) for v in (str(uuid.uuid4()),runtime,vd['id'],vr))+',1,'+q(session)+','+q(pod['metadata']['uid'])+',1,0,now()); '+
        "UPDATE edgeai.runtime_instance SET observed_state='SUBMITTED' WHERE id="+q(runtime),database)
    return run,attempt,child,runtime,pod
