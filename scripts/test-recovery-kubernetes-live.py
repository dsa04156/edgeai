"""Actual restored PostgreSQL and owned Kubernetes metadata fixtures; no model process is started."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import sys
import time
import traceback
import uuid

from postgres_backup import Postgres, ROOT, backup, restore, identifier, literal, private_file
from recovery_kubernetes import Kubernetes, PART, MANAGER, RUNTIME, VD, database_inventory

Api = runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--context',required=True)
    p.add_argument('--transport',choices=['native','compose'],default='native')
    p.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-kubernetes-test.json')
    args=p.parse_args()
    token=uuid.uuid4().hex
    namespace='edgeai-recovery-test-'+token[:16]
    source,target='edgeai_backup_kinv_'+token,'edgeai_restore_kinv_'+token
    work=ROOT/'.tools'/('recovery-kubernetes-test-'+token); work.mkdir(mode=0o700)
    pg=Postgres(args.transport,diagnostics=work/'postgres')
    kube=Kubernetes(args.context)
    command=['kubectl','--context',args.context,'--request-timeout=15s']
    owned,apis={},[]
    namespace_uid=None
    report={'status':'RUNNING','scope':'recovery-kubernetes-observation','sourceMode':'SYNTHETIC',
        'runtimeBoundary':'ACTUAL_KUBERNETES_METADATA_WITH_UNSCHEDULED_PODS','cases':[],
        'ownedNamespaceRemoved':False,'ownedDatabasesRemoved':False,'ownedApisStopped':False}

    def kubectl(arguments,document=None):
        result=subprocess.run(command+arguments,input=None if document is None else json.dumps(document).encode(),
            capture_output=True,timeout=30)
        assert result.returncode==0,'Kubernetes fixture operation failed; response suppressed'
        return json.loads(result.stdout) if result.stdout.strip() else None

    def create(document): return kubectl(['create','-f','-','-o','json'],document)

    def cli(output,restore_path=None,extra=None,expected=0):
        result=subprocess.run([sys.executable,'scripts/recovery_kubernetes.py','--context',args.context,
            '--namespace',namespace,'--database',target,'--restore-report',str(restore_path or pg.directory/'restore-report.json'),
            '--transport',args.transport,'--output',str(output),*(extra or [])],capture_output=True,timeout=120)
        with private_file(work/('cli-'+uuid.uuid4().hex+'.log')) as log: log.write(result.stdout); log.write(result.stderr)
        assert result.returncode==expected,'Recovery inventory unexpected exit; private log retained'
        return result

    def fingerprints():
        tables=json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'",target))
        return {table:hashlib.sha256(pg.sql('SELECT to_jsonb(t)::text FROM edgeai."'+table+'" t ORDER BY to_jsonb(t)::text',target).encode()).hexdigest() for table in tables}

    def passed(name): report['cases'].append(name); print('PASS: '+name,flush=True)

    code=1
    try:
        # Prove ownership of the explicitly selected project cluster before creating any fixture.
        kube.namespace('edgeai')
        pg.sql('CREATE DATABASE '+identifier(source),'postgres')
        owned[source]=pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(source),'postgres')
        api=Api(source,work); apis.append(api)
        api.request('POST','profiles/DEVICE',{'key':'recovery-inventory-'+token,'version':'1.0.0','spec':{'protocol':'synthetic'}},201)
        api.close()
        backup(pg,source,work/'backup')
        restored=restore(pg,work/'backup',target); owned[target]=restored['databaseOid']
        catalog=database_inventory(pg,target,pg.directory/'restore-report.json')
        assert not catalog['runtimes'] and not catalog['vds']
        before=fingerprints(); assert len(before)==44
        report['serverVersion']=int(pg.sql('SHOW server_version_num',target))
        passed('actual-archive-restore-and-read-only-database-inventory')

        ns=create({'apiVersion':'v1','kind':'Namespace','metadata':{'name':namespace,
            'labels':{PART:'edgeai',MANAGER:'edgeai-bootstrap','edgeai.io/recovery-test':token}}})
        namespace_uid=ns['metadata']['uid']
        canary='never-export-'+uuid.uuid4().hex
        pod_spec={'restartPolicy':'Never','nodeName':'edgeai-unassigned-'+token[:16],
            'automountServiceAccountToken':False,'containers':[{'name':'fixture','image':'unused.invalid/edgeai:metadata-only',
                'env':[{'name':'PRIVATE_CANARY','value':canary}]}]}
        attempt,task,run,vd_runtime,vd=[str(uuid.uuid4()) for _ in range(5)]
        labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/attempt-id':attempt,'edgeai.io/task-id':task,
            'edgeai.io/run-id':run,'edgeai.io/epoch':'1'}
        def job(name,labels):
            return create({'apiVersion':'batch/v1','kind':'Job','metadata':{'namespace':namespace,'name':name,'labels':labels},
                'spec':{'suspend':True,'template':{'metadata':{'labels':labels},'spec':pod_spec}}})
        created_job=job('edgeai-'+attempt,labels)
        create({'apiVersion':'v1','kind':'Pod','metadata':{'namespace':namespace,'name':'runner-after-snapshot',
            'labels':labels,'annotations':{'private-fixture':canary},'ownerReferences':[{'apiVersion':'batch/v1','kind':'Job',
                'controller':True,'name':'edgeai-'+attempt,'uid':created_job['metadata']['uid']}]},'spec':pod_spec})
        create({'apiVersion':'v1','kind':'Pod','metadata':{'namespace':namespace,'name':'edgeai-vd-'+vd_runtime,
            'labels':{PART:'edgeai',MANAGER:VD,'edgeai.io/vd-id':vd,'edgeai.io/vd-runtime-id':vd_runtime,
                'edgeai.io/generation':'1'}},'spec':pod_spec})
        job('edgeai-'+str(uuid.uuid4()),{PART:'edgeai',MANAGER:'unrelated-controller'})
        job('foreign-kept',{MANAGER:'someone-else'})

        def external_specs():
            return {kind:{item['metadata']['uid']:{'name':item['metadata']['name'],'labels':item['metadata'].get('labels',{}),
                    'spec':item['spec']} for item in kube.items(namespace,kind)[0]} for kind in ['Job','Pod']}
        resources_before=external_specs()
        output=work/'inventory'; result=cli(output)
        observed=json.loads((output/'inventory.json').read_text())
        assert observed['counts']=={'ABSENT_FROM_RESTORED_DATABASE':3,'OWNERSHIP_CONFLICT':1}
        assert not observed['quiesced'] and not observed['activated']
        assert canary not in (output/'inventory.json').read_text() and canary.encode() not in result.stdout+result.stderr
        assert observed['namespaces'][0]['listedJobs']==3 and observed['namespaces'][0]['listedPods']==2
        passed('post-snapshot-job-and-runner-vd-pods-found-without-db-rows')
        passed('ownership-conflict-visible-foreign-resource-preserved-and-private-fields-excluded')
        preserved=(output/'inventory.json').read_bytes(); cli(output,expected=1)
        assert (output/'inventory.json').read_bytes()==preserved
        passed('existing-inventory-not-overwritten')
        bad=copy.deepcopy(restored); bad['restoreIdentity']=uuid.uuid4().hex
        with private_file(work/'wrong-restore.json','w') as f: json.dump(bad,f)
        rejected=work/'rejected'; cli(rejected,work/'wrong-restore.json',expected=1)
        assert not (rejected/'inventory.json').exists()
        rejected=work/'foreign-namespace'; cli(rejected,extra=['--namespace','default'],expected=1)
        assert not (rejected/'inventory.json').exists()
        passed('wrong-restore-identity-and-unowned-namespace-refused')
        assert fingerprints()==before and external_specs()==resources_before
        report.update(databaseTablesPreserved=len(before),observedObjects=len(observed['objects']),classifications=observed['counts'],
            originalResourceUidsPreserved=True,quiesced=False,activated=False)
        passed('all-restored-tables-and-five-kubernetes-resource-identities-specs-preserved')
        code=0
    except Exception as error:
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as f: traceback.print_exc(file=f)
        print('FAIL: recovery inventory test; private diagnostics retained',flush=True)
    finally:
        try:
            for api in apis: api.close()
            report['ownedApisStopped']=all(api.process.poll() is not None for api in apis)
            if namespace_uid:
                current=kube.read('/api/v1/namespaces/'+namespace)
                assert current['metadata']['uid']==namespace_uid
                assert current['metadata']['labels'].get('edgeai.io/recovery-test')==token
                kubectl(['delete','--raw','/api/v1/namespaces/'+namespace,'-f','-'],
                    {'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':namespace_uid}})
                deadline=time.monotonic()+90
                while time.monotonic()<deadline:
                    remaining=kubectl(['get','namespace',namespace,'--ignore-not-found','-o','json'])
                    if remaining is None: break
                    assert remaining['metadata']['uid']==namespace_uid
                    time.sleep(.5)
                else: raise AssertionError('Owned namespace removal timed out')
            report['ownedNamespaceRemoved']=True
            for database,oid in list(owned.items()):
                assert pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(database),'postgres')==oid
                pg.sql('DROP DATABASE '+identifier(database),'postgres'); del owned[database]
            report['ownedDatabasesRemoved']=not owned
        except Exception as error:
            report['cleanupFailureType']=type(error).__name__; code=1
        report['status']='PASS' if code==0 else 'FAIL'
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(report,indent=2)+'\n')
        print(report['status']+': '+str(len(report['cases']))+' recovery Kubernetes cases; private diagnostics '+str(work))
    return code


if __name__=='__main__': raise SystemExit(main())
