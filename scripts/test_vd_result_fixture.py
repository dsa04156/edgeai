"""Owned VD database allocation fixture with a real Secret-owned supervisor Pod.

Readiness and allocation history are explicit fixtures. Child claim and Result
publication subsequently use the actual TLS API and Pod-bound TokenReview.
"""
import json
import secrets
import uuid

from postgres_backup import literal, private_file
from test_recovery_runtime_starts import q


def seed(api,Api,pg,database,work,namespace,service,workflow_version,create,kube,image,program):
    from test_vd_supervisor_fixture import supervisor
    vd,vr,pod=supervisor(api,pg,database,namespace,service,create,kube,image,program)
    session=pg.sql('SELECT session_id::text FROM edgeai.vd_runtime WHERE id='+q(vr),database)
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
