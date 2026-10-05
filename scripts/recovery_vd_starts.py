"""Validate original child admission against its retained VD allocation and supervisor.

A supervisor exit proves retirement only. Each child still needs its own original
admission, immutable work, session, slot and subsequently committed Result.
"""
from datetime import datetime, timezone
import hashlib
import re

from postgres_backup import Blocked
from recovery_remote_inventory import uid
from recovery_runtime_starts import instant_ns
from recovery_stop_kubernetes import termination_proof
from recovery_work_digest import canonical, digest, parse

MEDIA_TYPE='application/vnd.edgeai.vd-task-start+json'
IDENTITY={'runtimeId','runId','taskId','attemptId','epoch','namespace','vdId','allocationId',
          'vdRuntimeId','generation','sessionId','podName','podUid','nodeUid','nodeName','slot',
          'assignedSequence','assignedAt','configurationDigest'}
FIELDS=IDENTITY|{'apiVersion','readyAt','supervisorLeaseUntil','supervisorDrainDeadline',
                 'workDigest','expiresAt','offloadId','startDeadline','admittedAt'}


def validate(authority,context,pod,node,proof,namespace):
    if not isinstance(authority,dict) or set(authority)!=FIELDS or authority['apiVersion']!='edgeai.vd.task.start/v1':
        raise Blocked('Unsupported VD child start authority schema')
    runtime,attempt,allocation,supervisor=(context[k] for k in ('runtime','attempt','allocation','supervisor'))
    if allocation is None or supervisor is None:raise Blocked('VD admission needs its original allocation and supervisor')
    expected={field:runtime[column] for field,column in {'runtimeId':'id','runId':'run_id','taskId':'task_id',
        'attemptId':'attempt_id','epoch':'epoch','namespace':'namespace','vdId':'vd_id'}.items()}
    expected.update({field:allocation[column] for field,column in {'allocationId':'id','vdRuntimeId':'vd_runtime_id',
        'generation':'generation','sessionId':'session_id','podUid':'pod_uid','slot':'slot','assignedSequence':'assigned_sequence'}.items()})
    expected.update({field:supervisor[column] for field,column in {'podName':'pod_name','nodeUid':'node_uid',
        'nodeName':'node_name','configurationDigest':'configuration_digest'}.items()})
    if (any(type(authority[k]) is not int for k in ('epoch','generation','slot','assignedSequence')) or
            any(authority[k]!=v for k,v in expected.items()) or runtime['runtime_kind']!='VD' or
            runtime['namespace']!=namespace or runtime['desired_state']!='STOPPED' or runtime['observed_state']!='TERMINATED' or
            supervisor['desired_state']!='STOPPED' or supervisor['observed_state']!='TERMINATED' or
            attempt['mode']!='VD' or attempt['vd_id']!=runtime['vd_id'] or attempt['id']!=runtime['attempt_id'] or
            attempt['task_id']!=runtime['task_id'] or attempt['epoch']!=runtime['epoch'] or
            allocation['runtime_id']!=runtime['id'] or allocation['vd_id']!=runtime['vd_id'] or
            allocation['closed_at'] is None or allocation['close_reason'] not in ('POD_GONE','PROCESS_EXIT') or
            any(allocation[k]!=supervisor[v] for k,v in [('vd_runtime_id','id'),('vd_id','vd_id'),
                ('generation','generation'),('session_id','session_id'),('pod_uid','pod_uid')]) or supervisor['namespace']!=namespace):
        raise Blocked('VD start differs from retired child, original allocation or frozen supervisor')
    for field in ('runtimeId','runId','taskId','attemptId','vdId','allocationId','vdRuntimeId','sessionId','podUid','nodeUid'):
        uid(authority[field])
    if (not isinstance(authority['configurationDigest'],str) or not re.fullmatch('sha256:[0-9a-f]{64}',authority['configurationDigest']) or
            'sha256:'+hashlib.sha256(b'edgeai-vd-runtime-configuration-v1\n'+canonical(supervisor['configuration']).encode()).hexdigest()!=authority['configurationDigest'] or
            supervisor['configuration'].get('serviceProfileVersionId')!=context['serviceProfileVersionId'] or
            supervisor['configuration'].get('namespace')!=namespace or
            not 1<=authority['slot']<=supervisor['configuration']['maxConcurrentTasks'] or
            instant_ns(authority['assignedAt'])!=instant_ns(allocation['assigned_at']) or
            instant_ns(authority['readyAt'])!=instant_ns(supervisor['ready_at'])):
        raise Blocked('VD start configuration, assignment time or first readiness differs')
    meta=pod['metadata'];actual=termination_proof(pod)
    retained=None if actual is None else {'name':meta['name'],'uid':meta['uid'],**actual}
    if (meta['uid']!=authority['podUid'] or meta['name']!=authority['podName'] or meta['namespace']!=namespace or
            proof!=retained or proof['kind']!='ALL_CONTAINERS_TERMINATED' or
            pod['spec'].get('nodeName')!=authority['nodeName'] or proof['nodeName']!=authority['nodeName'] or
            node['metadata']['name']!=authority['nodeName'] or node['metadata']['uid']!=authority['nodeUid']):
        raise Blocked('VD authority differs from actual retained supervisor or node')
    labels=meta.get('labels',{})
    for key,field in {'vd-runtime-id':'vdRuntimeId','vd-id':'vdId','generation':'generation'}.items():
        if labels.get('edgeai.io/'+key)!=str(authority[field]):raise Blocked('VD supervisor labels differ')
    if authority['nodeName'] in attempt['excluded_node_names']:raise Blocked('VD start contradicts excluded node placement')
    for field,column in {'podUid':'producer_pod_uid','nodeUid':'node_uid','nodeName':'node_name'}.items():
        if runtime[column] is not None and runtime[column]!=authority[field]:raise Blocked('Recorded VD producer conflicts with original start')
    work=parse(context['workJson'])
    if (not isinstance(work,dict) or not isinstance(work.get('inputs'),list) or len(work['inputs'])!=context['inputCount'] or
            authority['workDigest']!=digest(context['workJson'])):
        raise Blocked('VD admission differs from immutable work or complete fixed inputs')
    admitted=instant_ns(authority['admittedAt']);expiry=instant_ns(authority['expiresAt'])
    lower=max(instant_ns(runtime['created_at']),instant_ns(attempt['created_at']),
              instant_ns(allocation['assigned_at']),instant_ns(supervisor['ready_at']))
    if (expiry!=instant_ns(runtime['expires_at']) or admitted<lower or admitted>=expiry or
            not admitted<instant_ns(authority['supervisorLeaseUntil'])<=admitted+60000000000 or
            admitted>instant_ns(datetime.now(timezone.utc).isoformat()) or
            admitted>instant_ns(allocation['closed_at'])):
        raise Blocked('VD admission falls outside its original assignment or recorded lease')
    # Heartbeats can move the DB lease after the original admission. Never replace
    # its recorded lease with the later value or create a fresh recovery lease.
    drain=authority['supervisorDrainDeadline']
    if drain is not None and (supervisor['drain_deadline'] is None or
            instant_ns(drain)!=instant_ns(supervisor['drain_deadline']) or admitted>=instant_ns(drain)):
        raise Blocked('VD admission contradicts the immutable drain deadline')
    if supervisor['drain_deadline'] is not None and admitted>=instant_ns(supervisor['drain_deadline']):
        raise Blocked('VD admission occurred after draining expired')
    containers=[c for c in proof['containers'] if c['name']=='vd-supervisor']
    if len(containers)!=1 or not instant_ns(containers[0]['startedAt'])<=admitted<instant_ns(containers[0]['finishedAt'])+1000000000:
        raise Blocked('VD admission is outside the retained supervisor lifetime')
    transfers=context['offloads']
    if len(transfers)>1:raise Blocked('Multiple operations claim the same VD admission')
    if not transfers:
        if authority['offloadId'] is not None or authority['startDeadline'] is not None:raise Blocked('Unexpected VD transfer authority')
    else:
        op=transfers[0]
        if (authority['offloadId']!=op['id'] or instant_ns(authority['startDeadline'])!=instant_ns(op['start_deadline']) or
                admitted<instant_ns(op['created_at']) or admitted>=instant_ns(op['start_deadline']) or
                op['state']=='STARTING' and admitted<instant_ns(op['updated_at'])):
            raise Blocked('VD admission differs from the original transfer deadline')
    return authority
