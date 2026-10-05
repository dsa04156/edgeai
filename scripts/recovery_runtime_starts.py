"""Inspect original Kubernetes start authority against a restored DB and retained termination proof.

Observation is read-only. The caller may reconcile transfer admission separately;
this evidence never proves a workload Result or fills a restored producer claim.
"""
from datetime import datetime,timezone
import hashlib
import json
import re
from types import SimpleNamespace

from postgres_backup import Blocked
from recovery_kubernetes import Kubernetes
from recovery_remote_fence import unique
from recovery_remote_inventory import uid
from recovery_remote_storage import Storage,private_json,header,version
from recovery_stop_kubernetes import object_path
from recovery_stop_kubernetes import termination_proof
from recovery_work_digest import digest
from storage_backup import validate_manifest

MEDIA_TYPE='application/vnd.edgeai.runtime-start+json'
FIELDS={'apiVersion','runtimeId','runId','taskId','attemptId','epoch','namespace','jobName','jobUid',
        'podUid','nodeUid','nodeName','workDigest','expiresAt','offloadId','startDeadline','admittedAt'}

# Preserve numbers in the work document as text until the lossless parser is used.
def context_query(vd=False):
    return """
SELECT coalesce(jsonb_agg(jsonb_build_object('runtime',to_jsonb(r)-'claim_nonce',
 {extra}
 'attempt',to_jsonb(a),'hasStream',EXISTS(SELECT FROM edgeai.task_dependency WHERE workflow_version_id=w.workflow_version_id AND mode='STREAM')
   OR EXISTS(SELECT FROM edgeai.data_route WHERE run_id=w.id),
 'offloads',(SELECT coalesce(jsonb_agg(to_jsonb(o)),'[]'::jsonb) FROM edgeai.task_offload o
   WHERE o.target_attempt_id=a.id OR EXISTS(SELECT FROM edgeai.task_offload_member m WHERE m.operation_id=o.id AND m.target_attempt_id=a.id)),
 'inputCount',(SELECT count(*) FROM edgeai.task_dependency e WHERE e.to_task_id=d.id AND e.mode='BATCH'),
 'workJson',jsonb_build_object('spec',v.spec,'parameters',d.parameters||w.parameters,'inputs',
   (SELECT coalesce(jsonb_agg(jsonb_build_object('port',e.to_port,'bucket',f.bucket,'key',f.object_key,
     'versionId',f.object_version,'bytes',f.bytes,'sha256',f.sha256,'mediaType',f.media_type) ORDER BY e.to_port),'[]'::jsonb)
    FROM edgeai.task_dependency e JOIN edgeai.task parent ON parent.definition_id=e.from_task_id AND parent.run_id=w.id AND parent.state='SUCCEEDED'
    JOIN edgeai.task_result result ON result.task_id=parent.id AND result.committed
    JOIN edgeai.result_artifact f ON f.result_id=result.id AND f.port=e.from_port
    WHERE e.to_task_id=d.id AND e.mode='BATCH'))::text
 ) ORDER BY r.id),'[]'::jsonb)
FROM edgeai.runtime_instance r JOIN edgeai.task_attempt a ON a.id=r.attempt_id
JOIN edgeai.task t ON t.id=r.task_id JOIN edgeai.workflow_run w ON w.id=t.run_id
JOIN edgeai.task_definition d ON d.id=t.definition_id JOIN edgeai.profile_version v ON v.id=d.service_profile_version_id
WHERE r.runtime_kind='{kind}'
""".format(kind='VD' if vd else 'KUBERNETES', extra="""
 'allocation',(SELECT to_jsonb(x) FROM edgeai.vd_task_allocation x WHERE x.runtime_id=r.id),
 'supervisor',(SELECT to_jsonb(s)-'claim_nonce' FROM edgeai.vd_runtime s
   JOIN edgeai.vd_task_allocation x ON x.vd_runtime_id=s.id WHERE x.runtime_id=r.id),
 'serviceProfileVersionId',d.service_profile_version_id,
 """ if vd else '')


QUERY=context_query()


def instant_ns(value):
    if not isinstance(value,str):raise Blocked('Start authority requires a UTC instant')
    match=re.fullmatch(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(?:Z|\+00:00)',value)
    if not match:raise Blocked('Start authority requires a UTC instant')
    try:parsed=datetime.fromisoformat(match[1]).replace(tzinfo=timezone.utc)
    except ValueError as error:raise Blocked('Invalid start authority instant') from error
    seconds=int((parsed-datetime(1970,1,1,tzinfo=timezone.utc)).total_seconds())
    return seconds*1000000000+int((match[2] or '').ljust(9,'0'))


def validate(authority,context,pod,node,proof,namespace):
    runtime,attempt=context['runtime'],context['attempt']
    if not isinstance(authority,dict) or set(authority)!=FIELDS or authority['apiVersion']!='edgeai.runtime.start/v1':
        raise Blocked('Unsupported Kubernetes start authority schema')
    expected={field:runtime[column] for field,column in {'runtimeId':'id','runId':'run_id','taskId':'task_id',
        'attemptId':'attempt_id','epoch':'epoch','namespace':'namespace','jobName':'job_name','jobUid':'job_uid'}.items()}
    if (type(authority['epoch']) is not int or any(authority[k]!=v for k,v in expected.items()) or
            runtime['namespace']!=namespace or runtime['runtime_kind']!='KUBERNETES' or
            runtime['desired_state']!='STOPPED' or runtime['observed_state']!='TERMINATED' or
            attempt['id']!=runtime['attempt_id'] or attempt['task_id']!=runtime['task_id'] or
            attempt['epoch']!=runtime['epoch'] or attempt['mode'] not in ('AUTO','NODE')):
        raise Blocked('Start authority differs from the retired runtime and frozen attempt')
    for field in ('runtimeId','runId','taskId','attemptId','jobUid','podUid','nodeUid'):uid(authority[field])
    meta=pod['metadata'];owners=[o for o in meta.get('ownerReferences',[]) if o.get('controller') is True]
    current_proof=termination_proof(pod)
    retained_proof=None if current_proof is None else {'name':meta['name'],'uid':meta['uid'],**current_proof}
    if (meta['uid']!=authority['podUid'] or meta['namespace']!=namespace or len(owners)!=1 or
            owners[0].get('apiVersion')!='batch/v1' or owners[0].get('kind')!='Job' or
            owners[0].get('uid')!=authority['jobUid'] or owners[0].get('name')!=authority['jobName'] or
            proof['uid']!=meta['uid'] or proof['kind']!='ALL_CONTAINERS_TERMINATED' or retained_proof!=proof or
            pod['spec'].get('nodeName')!=authority['nodeName'] or proof['nodeName']!=authority['nodeName'] or
            node['metadata']['name']!=authority['nodeName'] or node['metadata']['uid']!=authority['nodeUid']):
        raise Blocked('Start authority does not match the actual retained producer and node')
    if (attempt['node_id'] is not None and attempt['node_id']!=authority['nodeUid'] or
            authority['nodeName'] in attempt['excluded_node_names']):
        raise Blocked('Start producer contradicts the frozen node placement')
    labels=meta.get('labels',{})
    for key,field in {'run-id':'runId','task-id':'taskId','attempt-id':'attemptId','epoch':'epoch'}.items():
        if labels.get('edgeai.io/'+key)!=str(authority[field]):raise Blocked('Start producer labels differ')
    for field,column in {'podUid':'producer_pod_uid','nodeUid':'node_uid','nodeName':'node_name'}.items():
        if runtime[column] is not None and runtime[column]!=authority[field]:raise Blocked('Recorded producer claim conflicts with its start journal')
    from recovery_work_digest import parse
    work=parse(context['workJson'])
    if (not isinstance(work,dict) or not isinstance(work.get('inputs'),list) or len(work['inputs'])!=context['inputCount'] or
            authority['workDigest']!=digest(context['workJson'])):
        raise Blocked('Start authority differs from immutable work or its complete fixed inputs')
    admitted,expiry=instant_ns(authority['admittedAt']),instant_ns(authority['expiresAt'])
    if (expiry!=instant_ns(runtime['expires_at']) or admitted<max(instant_ns(runtime['created_at']),instant_ns(attempt['created_at'])) or
            admitted>=expiry or admitted>instant_ns(datetime.now(timezone.utc).isoformat())):
        raise Blocked('Start authority falls outside the original runtime lease')
    runners=[c for c in proof['containers'] if c['name']=='runner']
    # Kube timestamps have whole-second resolution; compare the represented interval.
    if len(runners)!=1 or not instant_ns(runners[0]['startedAt'])<=admitted<instant_ns(runners[0]['finishedAt'])+1000000000:
        raise Blocked('Start admission is outside the retained Runner lifetime')
    transfers=context['offloads']
    if len(transfers)>1:raise Blocked('Multiple operations claim the same runtime admission')
    if not transfers:
        if authority['offloadId'] is not None or authority['startDeadline'] is not None:raise Blocked('Unexpected transfer authority')
    else:
        op=transfers[0]
        if (authority['offloadId']!=op['id'] or instant_ns(authority['startDeadline'])!=instant_ns(op['start_deadline']) or
                admitted<instant_ns(op['created_at']) or admitted>=instant_ns(op['start_deadline']) or
                op['state']=='STARTING' and admitted<instant_ns(op['updated_at'])):
            raise Blocked('Start authority differs from the original operation or deadline')
    return authority


def observe(catalog,args,retired,vd=False):
    if vd:
        from recovery_vd_starts import validate as validator, MEDIA_TYPE as media_type
    else:
        validator,media_type=validate,MEDIA_TYPE
    prefix='authority/vd-task-start/' if vd else 'authority/runtime-start/'
    selected=[getattr(args,name,None) for name in ('runtime_start_backup','runtime_start_bucket','runtime_start_certificate_sha256')]
    if not any(selected):return None
    if not all(selected):raise ValueError('Runtime start inspection requires backup, bucket and TLS certificate pin')
    backup_path,bucket,pin=selected
    manifest,backup_hash=private_json(backup_path/'manifest.json');validate_manifest(manifest)
    for key in ('sourceDeploymentId','targetDeploymentId'):uid(manifest[key])
    if (manifest['sourceDeploymentId']==manifest['targetDeploymentId'] or bucket not in manifest['buckets'] or
            not re.fullmatch('[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]',bucket)):
        raise Blocked('Start journal requires an explicit distinct backup installation and bucket')
    store=Storage(SimpleNamespace(certificate_sha256=pin,timeout=min(300,max(10,args.timeout))))
    if store.identity()!=manifest['targetDeploymentId']:raise Blocked('Start journal backup installation differs')
    store.versioned(bucket);records={};kube=Kubernetes(args.context)
    proofs={p['uid']:p for p in retired['evidence']['pods']}
    identities={r['uid']:r for r in retired['evidence']['identities'] if r['kind']=='Pod'}
    proven=set(retired['selected']['vdTasks' if vd else 'runtimes'])
    for context in catalog['runtimeStartContexts']:
        runtime=context['runtime']
        if runtime['namespace']!=args.namespace or runtime['id'] not in proven:continue
        key=prefix+runtime['id']+'.json';matches=[o for o in manifest['versions'] if o['bucket']==bucket and o['key']==key]
        code,headers,_=store.request('HEAD','/'+bucket+'/'+key)
        if not matches:
            if code!=404:raise Blocked('Uncaptured start journal exists or its absence cannot be verified')
            records[runtime['id']]=None;continue
        if len(matches)!=1 or not 2<=matches[0]['bytes']<=8192:raise Blocked('Start journal backup has conflicting versions or size')
        item=matches[0]
        if code!=200 or version(header(headers,'x-amz-version-id'))!=item['versionId']:raise Blocked('Start journal latest version differs from its backup')
        code,headers,raw=store.request('GET','/'+bucket+'/'+key,{'versionId':version(item['versionId'])},max_bytes=8192)
        if (code!=200 or version(header(headers,'x-amz-version-id'))!=item['versionId'] or
                header(headers,'Content-Type')!=media_type or header(headers,'Content-Length')!=str(item['bytes']) or
                any(k.lower() in ('content-encoding','transfer-encoding') for k,v in headers) or
                len(raw)!=item['bytes'] or hashlib.sha256(raw).hexdigest()!=item['sha256']):
            raise Blocked('Pinned start journal bytes differ from the backup')
        try:authority=json.loads(raw,object_pairs_hook=unique)
        except (ValueError,UnicodeError) as error:raise Blocked('Invalid start journal JSON') from error
        if not isinstance(authority,dict) or authority.get('podUid') not in identities:raise Blocked('Start producer lacks retained termination evidence')
        identity=identities[authority['podUid']]
        pod=kube.read(object_path('Pod',args.namespace,identity['name']))
        node_name=pod.get('spec',{}).get('nodeName')
        if not isinstance(node_name,str) or not re.fullmatch('[a-z0-9][a-z0-9.-]{0,252}',node_name):raise Blocked('Invalid start producer node')
        node=kube.read('/api/v1/nodes/'+node_name)
        records[runtime['id']]={'object':item,'authority':validator(authority,context,pod,node,proofs[authority['podUid']],args.namespace)}
    if store.identity()!=manifest['targetDeploymentId'] or private_json(backup_path/'manifest.json')[1]!=backup_hash:
        raise Blocked('Start journal inputs changed during observation')
    # Recheck both retained physical evidence and each current object head. A
    # captured version alone cannot justify adopting a replacement admission.
    import recovery_kubernetes_retire as retirement
    if retirement.live_evidence(args)[0]!=retired['evidence']:
        raise Blocked('Retained producer evidence changed during start inspection')
    for rid,record in records.items():
        code,headers,_=store.request('HEAD','/'+bucket+'/'+prefix+rid+'.json')
        if record is None:
            if code!=404:raise Blocked('Start journal absence changed during inspection')
        elif code!=200 or version(header(headers,'x-amz-version-id'))!=record['object']['versionId']:
            raise Blocked('Start journal head changed during inspection')
    store.versioned(bucket)
    return {'storageBackupSha256':backup_hash,'targetDeploymentId':manifest['targetDeploymentId'],
        'bucket':bucket,'records':records,'namespaceUid':args.namespace_uid,'recoveryId':args.recovery_id}
