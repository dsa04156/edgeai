"""Restore original committed Kubernetes/VD BATCH Results; keep the database quarantined."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
from types import SimpleNamespace

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
import recovery_kubernetes_retire as retirement
import recovery_kubernetes_workflows as workflows
import recovery_runtime_starts as starts
import recovery_vd_starts as vd_starts
from recovery_remote_fence import unique
from recovery_remote_inventory import uid
from recovery_remote_retire import durable_json
from recovery_remote_results import transaction_sql
from recovery_remote_storage import Storage, private_json, header, version
from recovery_work_digest import canonical
from storage_backup import validate_manifest

MEDIA_TYPE = 'application/vnd.edgeai.runtime-result+json'
VD_MEDIA_TYPE = 'application/vnd.edgeai.vd-task-result+json'
FIELDS = {'apiVersion','resultId','runtimeId','runId','taskId','attemptId','epoch','namespace',
          'jobName','jobUid','podUid','nodeUid','nodeName','startKey','manifestDigest','committedAt','outputs'}
OUTPUT_FIELDS = {'port','bucket','objectKey','versionId','bytes','sha256','mediaType'}
TABLES = (*workflows.TABLES, 'runtime_result_publication')
GUARD_QUERY = workflows.GUARD_QUERY[:-1] + ", 'runtime_result_publication',(SELECT encode(sha256(convert_to(" \
    "coalesce(string_agg(encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' ORDER BY to_jsonb(t)::text),'')," \
    "'UTF8')),'hex') FROM edgeai.runtime_result_publication t))"
def context_query(vd=False):
    return """
SELECT coalesce(jsonb_agg(jsonb_build_object(
 'runtime',to_jsonb(r)-'claim_nonce','attempt',to_jsonb(a),'task',to_jsonb(t),'runState',w.state,
 'outputs',v.spec->'outputs','latestEpoch',(SELECT max(epoch) FROM edgeai.task_attempt WHERE task_id=t.id),
 'retryPending',EXISTS(SELECT FROM edgeai.task_retry WHERE task_id=t.id),
 'hasStream',EXISTS(SELECT FROM edgeai.task_dependency WHERE workflow_version_id=w.workflow_version_id AND mode='STREAM')
   OR EXISTS(SELECT FROM edgeai.data_route WHERE run_id=w.id),
 'activeOffload',EXISTS(SELECT FROM edgeai.task_offload WHERE run_id=w.id AND state IN ('DRAINING','STARTING','CANCELLING')),
 'pendingCommands',EXISTS(SELECT FROM edgeai.runtime_command WHERE runtime_id=r.id
   AND (NOT completed OR lease_owner IS NOT NULL OR lease_until IS NOT NULL)),
 'publication',(SELECT to_jsonb(p) FROM edgeai.runtime_result_publication p WHERE p.runtime_id=r.id),
 'result',(SELECT to_jsonb(s)||jsonb_build_object('outputs',(
   SELECT jsonb_agg(jsonb_build_object('port',f.port,'bucket',f.bucket,'key',f.object_key,
    'versionId',f.object_version,'bytes',f.bytes,'sha256',f.sha256,'mediaType',f.media_type) ORDER BY port)
   FROM edgeai.result_artifact f WHERE f.result_id=s.id)) FROM edgeai.task_result s WHERE s.task_id=t.id)
) ORDER BY r.id),'[]'::jsonb)
FROM edgeai.runtime_instance r JOIN edgeai.task_attempt a ON a.id=r.attempt_id
JOIN edgeai.task t ON t.id=r.task_id JOIN edgeai.workflow_run w ON w.id=t.run_id
JOIN edgeai.task_definition d ON d.id=t.definition_id JOIN edgeai.profile_version v ON v.id=d.service_profile_version_id
WHERE r.runtime_kind='{kind}'
""".format(kind='VD' if vd else 'KUBERNETES')


CONTEXT_QUERY=context_query()


def catalog_sql(vd=False):
    return ("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT jsonb_build_object("
    "'database',current_database(),'oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),"
    "'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),"
    "'readOnly',current_setting('transaction_read_only'),'migrations',(SELECT json_agg(json_build_object("
    "'version',version,'success',success)) FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),"
    "'guard',(" + GUARD_QUERY + "),'contexts',(" + context_query(vd) + "),'runtimeStartContexts',(" + starts.context_query(vd) +
    "),'attemptIds',(SELECT coalesce(json_agg(id),'[]'::json) FROM edgeai.task_attempt)); COMMIT;")


CATALOG_SQL=catalog_sql()


def digest(outputs):
    values = [{k: item[k] for k in ('port','bytes','sha256','mediaType','versionId')}
              for item in sorted(outputs,key=lambda item:item['port'])]
    return 'sha256:' + hashlib.sha256(b'edgeai-result-v1\n' + canonical(values).encode()).hexdigest()


def validate(authority, start, context, proof, bucket, vd=False):
    fields=vd_starts.IDENTITY|{'apiVersion','resultId','startKey','manifestDigest','committedAt','outputs'} if vd else FIELDS
    api_version='edgeai.vd.task.result/v1' if vd else 'edgeai.runtime.result/v1'
    if not isinstance(authority,dict) or set(authority)!=fields or authority['apiVersion']!=api_version:
        raise Blocked('Unsupported committed Result schema')
    identity = vd_starts.IDENTITY if vd else ('runtimeId','runId','taskId','attemptId','epoch','namespace','jobName','jobUid','podUid','nodeUid','nodeName')
    numeric=('epoch','generation','slot','assignedSequence') if vd else ('epoch',)
    if any(type(authority[k]) is not int for k in numeric) or any(authority[k]!=start[k] for k in identity):
        raise Blocked('Result producer differs from original start authority')
    try:uid(authority['resultId'])
    except ValueError as error:raise Blocked('Invalid original Result identity') from error
    if authority['startKey']!=('authority/vd-task-start/' if vd else 'authority/runtime-start/')+authority['runtimeId']+'.json':
        raise Blocked('Result references another start journal')
    committed = starts.instant_ns(authority['committedAt'])
    runners = [c for c in proof['containers'] if c['name']==('vd-supervisor' if vd else 'runner')]
    # The source Result already passed the source API lease check before commit.
    # Its original timestamp is a DB fact, not a new admission with a new lease.
    if (committed%1000 or committed<starts.instant_ns(start['admittedAt']) or
            committed>starts.instant_ns(datetime.now(timezone.utc).isoformat()) or len(runners)!=1 or
            not starts.instant_ns(runners[0]['startedAt'])<=committed<starts.instant_ns(runners[0]['finishedAt'])+1000000000):
        raise Blocked('Original Result timestamp is outside its producer lifetime or DB precision')
    outputs=authority['outputs'];spec=context['outputs']
    if not isinstance(outputs,list) or not 1<=len(outputs)<=16 or not isinstance(spec,dict):
        raise Blocked('Result requires the complete SERVICE output contract')
    ports=[];normalized=[]
    for item in outputs:
        if (not isinstance(item,dict) or set(item)!=OUTPUT_FIELDS or not isinstance(item['port'],str) or
                len(item['port'])>100 or not re.fullmatch('[a-z][a-z0-9]*([._-][a-z0-9]+)*',item['port']) or item['port'] not in spec or
                type(item['bytes']) is not int or not 0<=item['bytes']<=268435456 or
                item['bytes']>spec[item['port']]['maxBytes'] or item['mediaType']!=spec[item['port']]['mediaType'] or
                item['bucket']!=bucket or not isinstance(item['sha256'],str) or not re.fullmatch('[0-9a-f]{64}',item['sha256'])):
            raise Blocked('Result output differs from the fixed SERVICE contract')
        version(item['versionId'])
        expected='tasks/'+authority['taskId']+'/attempts/'+authority['attemptId']+'/'+item['port']+'/'+item['sha256']
        if item['objectKey']!=expected:raise Blocked('Output key is outside the original task/attempt scope')
        ports.append(item['port']);normalized.append({('key' if k=='objectKey' else k):v for k,v in item.items()})
    if ports!=sorted(set(ports)) or set(ports)!=set(spec) or authority['manifestDigest']!=digest(normalized):
        raise Blocked('Result outputs are incomplete, repeated, unsorted or have a different digest')
    return normalized


def observe(args, retired, catalog):
    vd=getattr(args,'vd_tasks',False)
    selected={**catalog,'runtimeStartContexts':[c for c in catalog['runtimeStartContexts'] if c['runtime']['id'] in args.runtime_id]}
    admission=starts.observe(selected,args,retired,vd=vd)
    if admission is None:raise Blocked('Both original start and committed Result journals are required')
    manifest,backup_hash=private_json(args.runtime_start_backup/'manifest.json');validate_manifest(manifest)
    if backup_hash!=admission['storageBackupSha256']:raise Blocked('Start and Result backup snapshots differ')
    bucket=args.runtime_start_bucket
    store=Storage(SimpleNamespace(certificate_sha256=args.runtime_start_certificate_sha256,timeout=args.timeout))
    records={};contexts={c['runtime']['id']:c for c in catalog['contexts']}
    proofs={p['uid']:p for p in retired['evidence']['pods']}
    for rid in sorted(args.runtime_id):
        record=admission['records'].get(rid)
        if record is None:raise Blocked('Selected Result has no verified original start admission')
        key=('authority/vd-task-result/' if vd else 'authority/runtime-result/')+rid+'.json'
        matches=[o for o in manifest['versions'] if o['bucket']==bucket and o['key']==key]
        if len(matches)!=1 or not 2<=matches[0]['bytes']<=1048576:raise Blocked('One bounded committed Result version is required')
        item=matches[0]
        code,headers,_=store.request('HEAD','/'+bucket+'/'+key)
        if code!=200 or version(header(headers,'x-amz-version-id'))!=item['versionId']:raise Blocked('Result head differs from captured authority')
        code,headers,raw=store.request('GET','/'+bucket+'/'+key,{'versionId':version(item['versionId'])},max_bytes=1048576)
        if (code!=200 or version(header(headers,'x-amz-version-id'))!=item['versionId'] or header(headers,'Content-Type')!=(VD_MEDIA_TYPE if vd else MEDIA_TYPE) or
                header(headers,'Content-Length')!=str(item['bytes']) or len(raw)!=item['bytes'] or
                any(k.lower() in ('content-encoding','transfer-encoding') for k,v in headers) or hashlib.sha256(raw).hexdigest()!=item['sha256']):
            raise Blocked('Pinned Result bytes or metadata differ from the backup')
        try:authority=json.loads(raw,object_pairs_hook=unique)
        except (ValueError,UnicodeError) as error:raise Blocked('Invalid Result journal JSON') from error
        outputs=validate(authority,record['authority'],contexts[rid],proofs[record['authority']['podUid']],bucket,vd=vd)
        if vd:
            allocation=next(c['allocation'] for c in selected['runtimeStartContexts'] if c['runtime']['id']==rid)
            if starts.instant_ns(authority['committedAt'])>starts.instant_ns(allocation['closed_at']):
                raise Blocked('VD Result was committed after its allocation closed')
        for output in outputs:
            captured=[v for v in manifest['versions'] if all(v[k]==output[k] for k in ('bucket','key','versionId'))]
            if len(captured)!=1 or any(captured[0][k]!=output[k] for k in ('bytes','sha256')):
                raise Blocked('Result output version is absent from this backup')
            store.verify(output)
        records[rid]={'object':item,'authority':authority,'outputs':outputs}
    if (private_json(args.runtime_start_backup/'manifest.json')[1]!=backup_hash or
            store.identity()!=manifest['targetDeploymentId'] or retirement.live_evidence(args)[0]!=retired['evidence']):
        raise Blocked('Physical or storage evidence changed during Result verification')
    for record in records.values():
        code,headers,_=store.request('HEAD','/'+bucket+'/'+record['object']['key'])
        if code!=200 or version(header(headers,'x-amz-version-id'))!=record['object']['versionId']:raise Blocked('Result authority head changed')
    store.versioned(bucket)
    return {'startEvidence':admission,'resultRecords':records}


def prepare(pg,args):
    vd=getattr(args,'vd_tasks',False)
    if not args.runtime_id or len(set(args.runtime_id))!=len(args.runtime_id):raise Blocked('Select distinct restored runtime IDs explicitly')
    for rid in args.runtime_id:uid(rid)
    schema=database_inventory(pg,args.database,args.restore_report,retirement.CATALOG_SQL)
    if {m['version'] for m in schema['migrations']} not in ({str(v) for v in range(1,36)},{str(v) for v in range(1,37)}):
        raise Blocked('Kubernetes Result recovery requires the reviewed V35/V36 publication schema')
    if vd and '36' not in {m['version'] for m in schema['migrations']}:
        raise Blocked('VD Result recovery requires the reviewed V36 publication schema')
    retired=retirement.prepare(pg,args)
    if not set(args.runtime_id)<=set(retired['selected']['vdTasks' if vd else 'runtimes']):raise Blocked('Every selected producer needs retained termination proof')
    catalog=database_inventory(pg,args.database,args.restore_report,catalog_sql(vd))
    if (any(retired[k]!=catalog[v] for k,v in [('databaseOid','oid'),('marker','marker'),('restoreReportSha256','restoreReportSha256')]) or
            any(retired['beforeGuard'][t]!=catalog['guard'][t] for t in retirement.TABLES)):
        raise Blocked('Database changed during Result snapshot')
    evidence=observe(args,retired,catalog);entries=[]
    start_contexts={c['runtime']['id']:c for c in catalog['runtimeStartContexts']}
    for context in catalog['contexts']:
        runtime=context['runtime'];rid=runtime['id']
        if rid not in args.runtime_id:continue
        authority=evidence['resultRecords'][rid]['authority'];outputs=evidence['resultRecords'][rid]['outputs']
        attempt,task,result,publication=(context[k] for k in ('attempt','task','result','publication'))
        if (runtime['desired_state']!='STOPPED' or runtime['observed_state']!='TERMINATED' or context['pendingCommands'] or
                context['hasStream'] or context['activeOffload'] or context['retryPending'] or runtime['failure_reason'] is not None or
                context['latestEpoch']!=runtime['epoch'] or task['cancellation_reason'] is not None or
                any(o['state']!='SUCCEEDED' or o['failure_reason'] is not None for o in start_contexts[rid]['offloads'])):
            raise Blocked('Result cannot bypass retirement, STREAM, offload, retry, cancellation or newer history')
        for observed in retired['evidence']['identities']:
            identity=observed.get('identity',{})
            if identity.get('run-id')==runtime['run_id'] and identity.get('attempt-id') not in catalog['attemptIds']:
                raise Blocked('Run has observed post-snapshot execution that must be recovered first')
        restore_binding=runtime['producer_pod_uid'] is None
        if result is None:
            if (attempt['state'] not in ('DISPATCHING','RUNNING') or task['state']!='RUNNING' or
                    context['runState']!='RUNNING' or publication is not None):
                raise Blocked('Success cannot override a stopped or inconsistent workflow')
        else:
            if (restore_binding or not result['committed'] or result['producer_kind']!=('VD' if vd else 'KUBERNETES') or
                    result['remote_allocation_id'] is not None or result['vd_runtime_id']!=(authority['vdRuntimeId'] if vd else None) or
                    any(result[k]!=authority[v] for k,v in [('id','resultId'),('task_id','taskId'),('attempt_id','attemptId'),
                        ('runtime_id','runtimeId'),('epoch','epoch'),('producer_pod_uid','podUid'),('manifest_digest','manifestDigest')]) or
                    starts.instant_ns(result['created_at'])!=starts.instant_ns(authority['committedAt']) or result['outputs']!=outputs or
                    attempt['state']!='SUCCEEDED' or task['state']!='SUCCEEDED' or
                    publication is None or publication['result_id']!=authority['resultId'] or
                    publication['lease_owner'] is not None or publication['lease_until'] is not None):
                raise Blocked('Existing Result or publication differs from original committed history')
        entries.append({k:authority[k] for k in ('resultId','runtimeId','runId','taskId','attemptId','epoch',
            'podUid','nodeUid','nodeName','manifestDigest','committedAt')} | {'outputs':outputs,'existing':result is not None,
            'restoreBinding':restore_binding,'publicationPending':publication is not None and not publication['completed']} |
            ({k:authority[k] for k in ('vdId','allocationId','vdRuntimeId','generation','sessionId','slot','assignedSequence')} if vd else {}))
    if len(entries)!=len(args.runtime_id):raise Blocked('Selected producer is absent from the fixed result catalog')
    return {'formatVersion':1,'scope':'restored-vd-result-commit' if vd else 'restored-kubernetes-result-commit','targetDatabase':args.database,
        'databaseOid':catalog['oid'],'marker':catalog['marker'],'restoreReportSha256':catalog['restoreReportSha256'],
        'namespace':args.namespace,'namespaceUid':args.namespace_uid,'recoveryId':args.recovery_id,
        'physicalEvidence':retired['evidence'],**evidence,'beforeGuard':catalog['guard'],'entries':entries,
        'preparedAt':datetime.now(timezone.utc).isoformat()}


def apply(pg,args,plan):
    vd=getattr(args,'vd_tasks',False)
    durable_json(args.output/'intent.json',plan)
    fresh=prepare(pg,args)
    if {k:v for k,v in fresh.items() if k!='preparedAt'}!={k:v for k,v in plan.items() if k!='preparedAt'}:
        raise Blocked('Recovery inputs changed before commit')
    path=args.output/'transaction.sql'
    with private_file(path,'w') as target:
        target.write(transaction_sql(plan,tables=TABLES,guard_query=GUARD_QUERY,kubernetes=not vd,vd=vd));target.flush();os.fsync(target.fileno())
    with path.open('rb') as source:
        result=json.loads(pg.call('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-f','-'],args.database,
            source=source,timeout=45,reject_stderr=True))
    after=prepare(pg,args)
    expected={**plan,'beforeGuard':result['afterGuard'],'preparedAt':after['preparedAt'],
        'entries':[{**e,'existing':True,'restoreBinding':False,'publicationPending':False} for e in plan['entries']]}
    if after!=expected:raise Blocked('Post-commit evidence changed; retain quarantine and do not claim rollback')
    counters=('resultsCreated','childrenReadied','runsReconciled','bindingsRestored','publicationsCompleted')
    report={k:plan[k] for k in ('formatVersion','scope','targetDatabase','databaseOid','namespace','namespaceUid','recoveryId')}
    report.update(result,status='VD_RESULTS_COMMITTED' if vd else 'KUBERNETES_RESULTS_COMMITTED',activated=False,databaseModified=any(result[k] for k in counters),
        resultsVerified=len(plan['entries']),intentSha256=hashlib.sha256((args.output/'intent.json').read_bytes()).hexdigest(),
        verifiedAt=datetime.now(timezone.utc).isoformat(),excluded=['new-execution-authority','stream-results',
        'unknown-post-snapshot-executions','global-producer-retirement','service-activation'])
    durable_json(args.output/'results.json',report)
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('context','namespace','namespace-uid','recovery-id','database','runtime-start-bucket','runtime-start-certificate-sha256'):
        parser.add_argument('--'+name,required=True)
    for name in ('restore-report','termination-report','runtime-start-backup','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--runtime-id',action='append',required=True)
    parser.add_argument('--unclaimed-jobs',action='store_true')
    parser.add_argument('--vd-tasks',action='store_true',help='Restore VD child Results using original allocation/session journals')
    parser.add_argument('--transport',choices=('native','compose'),default='native')
    parser.add_argument('--pg-bin',type=Path);parser.add_argument('--timeout',type=int,default=120)
    args=parser.parse_args();args.output.mkdir(mode=0o700,parents=True,exist_ok=False);submitted=False
    try:
        pg=Postgres(args.transport,args.pg_bin,diagnostics=args.output/'postgres')
        plan=prepare(pg,args);submitted=True;report=apply(pg,args,plan)
        print(report['status']+': '+str(report['resultsVerified'])+' results; database remains quarantined');return 0
    except Exception as error:
        blocked=isinstance(error,(Blocked,OSError,http.client.HTTPException));status='BLOCKED' if blocked else 'FAIL'
        durable_json(args.output/'failure.json',{'status':status,'failureType':type(error).__name__,
            'activated':False,'databaseModified':None if submitted else False,
            'instruction':'Keep quarantine and original evidence; rerun identical inputs in a new output directory'})
        print(status+': Kubernetes result recovery; private evidence retained; keep database quarantined')
        return 2 if blocked else 1


if __name__=='__main__':raise SystemExit(main())
