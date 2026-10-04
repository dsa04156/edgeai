"""Read-only inventory of Kubernetes producers, including resources absent from a restored DB."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import uuid
from urllib.parse import urlencode

from postgres_backup import Blocked, Postgres, ROOT, identifier, private_file
from recovery_references import SUPPORTED_VERSIONS, read_json

PART = 'app.kubernetes.io/part-of'
MANAGER = 'app.kubernetes.io/managed-by'
RUNTIME = 'edgeai-runtime-controller'
VD = 'edgeai-vd-controller'
DATABASE_SQL = """
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object(
 'database',current_database(),
 'oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),
 'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),
 'readOnly',current_setting('transaction_read_only'),'snapshot',pg_current_snapshot()::text,
 'migrations',(SELECT json_agg(json_build_object('version',version,'success',success))
   FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),
 'runtimes',(SELECT coalesce(json_agg(to_jsonb(r)),'[]'::json) FROM
   (SELECT id,run_id,task_id,attempt_id,epoch,namespace,job_name,job_uid,producer_pod_uid,observed_state
    FROM edgeai.runtime_instance WHERE runtime_kind='KUBERNETES') r),
 'vds',(SELECT coalesce(json_agg(to_jsonb(v)),'[]'::json) FROM
   (SELECT id,vd_id,generation,namespace,pod_name,pod_uid,observed_state FROM edgeai.vd_runtime) v)
);
COMMIT;
"""


def uid(value):
    if not isinstance(value,str) or str(uuid.UUID(value)) != value:
        raise ValueError('Invalid Kubernetes identity')
    return value


def namespace_name(value):
    if not re.fullmatch('[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?',value):
        raise ValueError('Invalid namespace')
    return value


class Kubernetes:
    def __init__(self,context):
        if not context or context.startswith('-'):
            raise ValueError('An explicit Kubernetes context is required')
        self.command = ['kubectl','--context',context,'--request-timeout=15s']

    def read(self,path):
        response = subprocess.run(self.command+['get','--raw',path],capture_output=True,timeout=20)
        if response.returncode:
            raise RuntimeError('Kubernetes read failed; response suppressed')
        return json.loads(response.stdout)

    def namespace(self,name):
        value = self.read('/api/v1/namespaces/'+namespace_name(name))
        meta = value['metadata']; labels = meta.get('labels',{})
        if (meta.get('name') != name or labels.get(PART) != 'edgeai'
                or labels.get(MANAGER) != 'edgeai-bootstrap' or meta.get('deletionTimestamp')):
            raise ValueError('Namespace is not owned and active')
        return uid(meta['uid'])

    def items(self,namespace,kind):
        prefix = '/apis/batch/v1' if kind == 'Job' else '/api/v1'
        path = prefix+'/namespaces/'+namespace_name(namespace)+'/'+('jobs' if kind == 'Job' else 'pods')
        objects, seen, tokens = [], set(), set()
        continuation, version = '', None
        for _ in range(10000):
            page = self.read(path+'?'+urlencode({'limit':200,**({'continue':continuation} if continuation else {})}))
            meta = page['metadata']; current = meta.get('resourceVersion')
            if not isinstance(current,str) or not current or (version is not None and current != version):
                raise ValueError('Kubernetes list snapshot changed between pages')
            version = current
            for item in page['items']:
                if item.get('kind',kind) != kind or item['metadata'].get('namespace') != namespace:
                    raise ValueError('Kubernetes list scope differs')
                identity = uid(item['metadata']['uid'])
                if identity in seen: raise ValueError('Duplicate Kubernetes object')
                seen.add(identity); objects.append(item)
            continuation = meta.get('continue','')
            if not continuation: return objects,version
            if continuation in tokens: raise ValueError('Repeated Kubernetes continuation')
            tokens.add(continuation)
        raise Blocked('Kubernetes inventory page limit exceeded')


def database_inventory(pg,database,restore_path):
    identifier(database)
    restored,digest = read_json(restore_path)
    if (not database.startswith('edgeai_restore_') or database == 'edgeai_restore_'
            or restored.get('scope') != 'postgresql-database-only'
            or restored.get('status') != 'RESTORED_DB_ONLY' or restored.get('activated') is not False
            or restored.get('created') is not True or restored.get('targetDatabase') != database
            or not re.fullmatch('[a-f0-9]{32}',restored.get('restoreIdentity',''))):
        raise ValueError('A successful inactive restore report is required')
    value = json.loads(pg.call('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-c',DATABASE_SQL],
        database,timeout=60,reject_stderr=True))
    if (value['database'] != database or value['oid'] != str(restored.get('databaseOid'))
            or value['marker'] != 'edgeai-restore:'+restored['restoreIdentity'] or value['readOnly'] != 'on'):
        raise ValueError('Restored database identity or read-only snapshot differs')
    migrations = value['migrations'] or []
    if (len(migrations) != len(SUPPORTED_VERSIONS) or {r['version'] for r in migrations} != SUPPORTED_VERSIONS
            or any(r['success'] is not True for r in migrations)):
        raise Blocked('Review the runtime inventory for this database schema before proceeding')
    value['restoreReportSha256'] = digest
    return value


def classify(kind,item,catalog,job_uids):
    """Observe ownership conflicts; no output from this function authorizes deletion."""
    meta = item['metadata']; labels = meta.get('labels',{})
    namespace,name = meta['namespace'],meta['name']
    runtime = next((r for r in catalog['runtimes'] if r['namespace']==namespace and r['job_name']==name),None) if kind=='Job' else None
    vd = next((v for v in catalog['vds'] if v['namespace']==namespace and v['pod_name']==name),None) if kind=='Pod' else None
    refs = meta.get('ownerReferences',[])
    relevant_owner = any(r.get('uid') in job_uids for r in refs)
    claimed = labels.get(PART)=='edgeai' and labels.get(MANAGER) in (RUNTIME,VD)
    reserved = name.startswith('edgeai-vd-') or bool(re.fullmatch('edgeai-[a-f0-9-]{36}',name))
    if not (claimed or runtime or vd or relevant_owner or reserved): return None
    result = {'kind':kind,'namespace':namespace,'name':name,'uid':uid(meta['uid']),
        'resourceVersion':meta['resourceVersion'],'terminating':bool(meta.get('deletionTimestamp')),
        'classification':'OWNERSHIP_CONFLICT'}
    try:
        if not claimed: return result
        manager = labels[MANAGER]
        fields = ('run-id','task-id','attempt-id') if manager==RUNTIME else ('vd-id','vd-runtime-id')
        identity = {key:uid(labels.get('edgeai.io/'+key)) for key in fields}
        number_key = 'epoch' if manager==RUNTIME else 'generation'
        number = labels.get('edgeai.io/'+number_key,'')
        if not re.fullmatch('[1-9][0-9]{0,15}',number) or int(number)>9007199254740991: return result
        result['identity'] = {**identity,number_key:int(number)}
        if manager==VD:
            if kind!='Pod' or name!='edgeai-vd-'+identity['vd-runtime-id']: return result
            expected = next((v for v in catalog['vds'] if v['id']==identity['vd-runtime-id']),None)
            if expected and (expected['namespace']!=namespace or expected['pod_name']!=name
                    or expected['vd_id']!=identity['vd-id'] or expected['generation']!=int(number)): return result
            expected_uid = expected['pod_uid'] if expected else None
        else:
            expected_name = 'edgeai-'+identity['attempt-id']
            if kind=='Job':
                if name!=expected_name: return result
            elif len([r for r in refs if r.get('controller') is True and r.get('kind')=='Job'
                    and r.get('name')==expected_name and r.get('uid') in job_uids])!=1:
                # An orphan Pod still needs attention; never silently treat it as absent.
                result['classification']='ORPHAN_OR_CONFLICTING_RUNTIME_POD'; return result
            expected = next((r for r in catalog['runtimes'] if r['attempt_id']==identity['attempt-id']),None)
            if expected and (expected['namespace']!=namespace or expected['job_name']!=expected_name
                    or expected['run_id']!=identity['run-id'] or expected['task_id']!=identity['task-id']
                    or expected['epoch']!=int(number)): return result
            expected_uid = (expected['job_uid'] if kind=='Job' else expected['producer_pod_uid']) if expected else None
        result['classification'] = ('ABSENT_FROM_RESTORED_DATABASE' if expected is None else
            'DATABASE_UID_NOT_RECORDED' if expected_uid is None else
            'DATABASE_UID_MATCH' if expected_uid==result['uid'] else 'DATABASE_UID_MISMATCH')
        if expected: result['databaseState']=expected['observed_state']
    except (ValueError,TypeError,KeyError):
        result.pop('identity',None)
    return result


def inventory(kube,namespaces,catalog):
    results, scopes = [],[]
    for namespace in namespaces:
        before = kube.namespace(namespace)
        jobs,jobs_version = kube.items(namespace,'Job')
        pods,pods_version = kube.items(namespace,'Pod')
        job_uids = {j['metadata']['uid'] for j in jobs if j['metadata'].get('labels',{}).get(PART)=='edgeai'
            and j['metadata'].get('labels',{}).get(MANAGER)==RUNTIME}
        for kind,items in [('Job',jobs),('Pod',pods)]:
            for item in items:
                result = classify(kind,item,catalog,job_uids)
                if result is not None: results.append(result)
        if kube.namespace(namespace)!=before: raise ValueError('Namespace identity changed during inventory')
        scopes.append({'namespace':namespace,'uid':before,'jobsResourceVersion':jobs_version,
            'podsResourceVersion':pods_version,'listedJobs':len(jobs),'listedPods':len(pods)})
    observed = {(r['namespace'],r['kind'],r['name']) for r in results}
    missing = []
    for kind,rows,name_key in [('Job',catalog['runtimes'],'job_name'),('Pod',catalog['vds'],'pod_name')]:
        for row in rows:
            if row['namespace'] in namespaces and (row['namespace'],kind,row[name_key]) not in observed:
                missing.append({'kind':kind,'namespace':row['namespace'],'name':row[name_key],
                    'databaseId':row['id'],'classification':'NOT_OBSERVED','databaseState':row['observed_state']})
    return {'formatVersion':1,'scope':'restored-database-kubernetes-observation',
        'status':'OBSERVED_KUBERNETES_PRODUCERS','activated':False,'quiesced':False,
        'databaseSnapshot':catalog['snapshot'],'restoreReportSha256':catalog['restoreReportSha256'],
        'namespaces':scopes,'objects':sorted(results,key=lambda r:(r['namespace'],r['kind'],r['name'])),
        'counts':dict(Counter(r['classification'] for r in results)),
        'databaseObjectsNotObserved':missing,
        'databaseNamespacesNotObserved':sorted({r['namespace'] for r in catalog['runtimes']+catalog['vds']}-set(namespaces)),
        'observedAt':datetime.now(timezone.utc).isoformat(),
        'limitations':['observations-are-not-an-atomic-cross-system-snapshot','controllers-may-still-create-producers',
            'remote-broker-device-and-claim-secret-state-not-observed','absence-is-not-proof-of-termination',
            'UID-and-ownership-must-be-rechecked-before-any-later-mutation']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context',required=True)
    parser.add_argument('--namespace',action='append',required=True)
    parser.add_argument('--database',required=True)
    parser.add_argument('--restore-report',type=Path,required=True)
    parser.add_argument('--transport',choices=['native','compose'],default='native')
    parser.add_argument('--pg-bin',type=Path)
    parser.add_argument('--output',type=Path)
    args = parser.parse_args()
    output = args.output or ROOT/'.tools'/('recovery-kubernetes-'+uuid.uuid4().hex)
    try:
        if len(set(args.namespace))!=len(args.namespace): raise ValueError('Duplicate namespace')
        for namespace in args.namespace: namespace_name(namespace)
        output = output.absolute(); output.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        output.mkdir(mode=0o700,exist_ok=False)
        pg = Postgres(args.transport,args.pg_bin,diagnostics=output/'postgres')
        catalog = database_inventory(pg,args.database,args.restore_report)
        report = inventory(Kubernetes(args.context),args.namespace,catalog)
        with private_file(output/'inventory.json','w') as target: json.dump(report,target,indent=2); target.write('\n')
        print('OBSERVED_KUBERNETES_PRODUCERS: '+str(len(report['objects']))+' objects; no producers stopped or service activated')
        print('Private inventory: '+str(output/'inventory.json'))
        return 0
    except Exception as error:
        print(('BLOCKED' if isinstance(error,Blocked) else 'FAIL')+': recovery inventory ('+type(error).__name__+'); no mutation performed')
        return 2 if isinstance(error,Blocked) else 1


if __name__=='__main__': raise SystemExit(main())
