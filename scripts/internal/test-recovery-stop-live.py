"""Actual running parent/child processes in an owned namespace; fence, termination and resume."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

from postgres_backup import ROOT,private_file
from recovery_kubernetes import Kubernetes,PART,MANAGER,RUNTIME,VD
from recovery_stop_kubernetes import FINALIZER,OPERATION,FENCE,object_path,termination_proof


PROGRAM = """
import signal,subprocess,sys,time
from pathlib import Path
child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(600)'])
def stop(signum,frame):
    signal.signal(signal.SIGTERM,signal.SIG_IGN)
    time.sleep(30)
    child.terminate(); child.wait(timeout=5)
    Path('/dev/termination-log').write_text('CHILD_REAPED')
    sys.exit(0)
signal.signal(signal.SIGTERM,stop)
Path('/tmp/child-ready').write_text(str(child.pid))
while True: time.sleep(1)
"""


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context',required=True)
    parser.add_argument('--runner-image')
    parser.add_argument('--runner-source')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-stop-test.json')
    args=parser.parse_args()
    token=uuid.uuid4().hex; operation=str(uuid.uuid4())
    namespace='edgeai-stop-test-'+token[:16]
    work=ROOT/'.tools'/('recovery-stop-test-'+token); work.mkdir(mode=0o700)
    kube=Kubernetes(args.context); namespace_uid=None
    command=['kubectl','--context',args.context,'--request-timeout=15s']
    report={'status':'RUNNING','scope':'kubernetes-recovery-stop','sourceMode':'SYNTHETIC','cases':[],
        'processBoundary':'ACTUAL_RUNNING_CONTAINER_PARENT_AND_CHILD','ownedNamespaceRemoved':False}

    def call(arguments,body=None):
        result=subprocess.run(command+arguments,input=None if body is None else json.dumps(body).encode(),capture_output=True,timeout=30)
        if result.returncode: raise RuntimeError('Test Kubernetes operation failed; details suppressed')
        return json.loads(result.stdout) if result.stdout.strip() else None

    def create(value): return call(['create','-f','-','-o','json'],value)

    def cli(name,expected=0,timeout=90,namespace_id=None,recovery_id=None):
        output=work/name
        result=subprocess.run([sys.executable,'scripts/internal/recovery_stop_kubernetes.py','--context',args.context,
            '--namespace',namespace,'--namespace-uid',namespace_id or namespace_uid,'--recovery-id',recovery_id or operation,
            '--timeout',str(timeout),'--output',str(output)],capture_output=True,timeout=timeout+90)
        with private_file(work/('command-'+uuid.uuid4().hex+'.log')) as log: log.write(result.stdout); log.write(result.stderr)
        assert result.returncode==expected,'Recovery stop CLI unexpected exit; private report retained'
        return json.loads((output/'termination-report.json').read_text())

    def passed(name): report['cases'].append(name); print('PASS: '+name,flush=True)

    code=1
    try:
        kube.namespace('edgeai')
        pin=json.loads((ROOT/'deploy/kubernetes/overlays/dev/release.json').read_text())
        assert bool(args.runner_image)==bool(args.runner_source)
        image=args.runner_image or 'ghcr.io/dsa04156/edgeai-runner@'+pin['runnerDigest']
        source=args.runner_source or pin['sourceRevision']
        assert re.fullmatch(r'ghcr\.io/dsa04156/edgeai-runner@sha256:[0-9a-f]{64}',image)
        assert re.fullmatch('[0-9a-f]{40}',source)
        report['image']=image; report['imageSourceRevision']=source
        ns=create({'apiVersion':'v1','kind':'Namespace','metadata':{'name':namespace,
            'labels':{PART:'edgeai',MANAGER:'edgeai-bootstrap','edgeai.io/recovery-test':token}}})
        namespace_uid=ns['metadata']['uid']
        attempt,task,run,vr,vd=[str(uuid.uuid4()) for _ in range(5)]
        labels={PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/attempt-id':attempt,
            'edgeai.io/task-id':task,'edgeai.io/run-id':run,'edgeai.io/epoch':'1'}
        pod={'restartPolicy':'Never','terminationGracePeriodSeconds':60,'automountServiceAccountToken':False,
            'nodeSelector':{'kubernetes.io/arch':'amd64'},'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001},
            'containers':[{'name':'runner','image':image,'command':['python3','-B','-c',PROGRAM],
                'resources':{'requests':{'cpu':'10m','memory':'32Mi'},'limits':{'cpu':'100m','memory':'96Mi'}},
                'securityContext':{'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']}}}]}
        job=create({'apiVersion':'batch/v1','kind':'Job','metadata':{'name':'edgeai-'+attempt,'namespace':namespace,'labels':labels},
            'spec':{'backoffLimit':0,'template':{'metadata':{'labels':labels},'spec':pod}}})
        create({'apiVersion':'v1','kind':'Pod','metadata':{'name':'edgeai-vd-'+vr,'namespace':namespace,
            'finalizers':['edgeai.io/recovery-test-sentinel'],
            'labels':{PART:'edgeai',MANAGER:VD,'edgeai.io/vd-runtime-id':vr,'edgeai.io/vd-id':vd,'edgeai.io/generation':'1'}},'spec':pod})
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            pods=kube.items(namespace,'Pod')[0]
            if len(pods)==2 and all(any(c.get('state',{}).get('running') for c in p.get('status',{}).get('containerStatuses',[])) for p in pods):
                ready=True
                for p in pods:
                    probe=subprocess.run(command+['-n',namespace,'exec',p['metadata']['name'],'--','python3','-c',
                        "from pathlib import Path;import os;os.kill(int(Path('/tmp/child-ready').read_text()),0)"],capture_output=True,timeout=15)
                    ready &= probe.returncode==0
                if ready: break
            time.sleep(.3)
        else: raise AssertionError('Owned parent and child processes did not become ready')
        original_ids={p['metadata']['uid'] for p in pods}
        report['runningPodUids']=sorted(original_ids)
        passed('two-real-containers-and-their-child-processes-running')

        bad=cli('wrong-namespace',expected=1,namespace_id=str(uuid.uuid4()))
        assert not bad['fenceRetained']
        assert not kube.read('/api/v1/namespaces/'+namespace+'/resourcequotas')['items']
        passed('wrong-namespace-uid-refused-before-mutation')
        foreign=create({'apiVersion':'batch/v1','kind':'Job','metadata':{'namespace':namespace,'name':'foreign-kept'},
            'spec':{'suspend':True,'template':{'spec':pod}}})
        cli('foreign-resource',expected=1)
        assert not kube.read('/api/v1/namespaces/'+namespace+'/resourcequotas')['items']
        observed=kube.read(object_path('Job',namespace,'foreign-kept'))
        assert observed['metadata']['uid']==foreign['metadata']['uid'] and observed['spec']==foreign['spec']
        call(['delete','--raw',object_path('Job',namespace,'foreign-kept'),'-f','-'],
            {'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':foreign['metadata']['uid']}})
        passed('foreign-workload-preserved-and-namespace-fencing-refused')

        first=cli('first',expected=2,timeout=15)
        assert first['status']=='BLOCKED' and first['fenceRetained'] is True and not first['activated']
        quota=kube.read(object_path('ResourceQuota',namespace,FENCE))
        assert quota['metadata']['uid']==first['quotaUid']
        retained=kube.items(namespace,'Pod')[0]
        assert {p['metadata']['uid'] for p in retained}==original_ids
        assert all(FINALIZER in p['metadata'].get('finalizers',[]) for p in retained)
        assert any(termination_proof(p) is None for p in retained)
        passed('timeout-retains-admission-fence-and-unconfirmed-pod-evidence')
        refused=cli('different-operation',expected=1,recovery_id=str(uuid.uuid4()))
        assert refused['status']=='FAIL'
        assert kube.read(object_path('ResourceQuota',namespace,FENCE))['metadata']['uid']==first['quotaUid']
        passed('different-recovery-operation-cannot-adopt-existing-fence')

        resumed=cli('resumed')
        assert resumed['status']=='OBSERVED_KUBERNETES_PRODUCERS_TERMINATED'
        assert resumed['quotaUid']==first['quotaUid'] and resumed['fenceRetained'] is True
        assert not resumed['activated'] and not resumed['globalQuiescenceProven']
        assert resumed['observedPods']==2 and len(resumed['terminatedPods'])==2
        assert all(p['kind']=='ALL_CONTAINERS_TERMINATED' and len(p['containers'])==1 and p['containers'][0]['exitCode']==0 for p in resumed['terminatedPods'])
        actual=kube.items(namespace,'Pod')[0]
        assert {p['metadata']['uid'] for p in actual}==original_ids
        assert all(p['status']['containerStatuses'][0]['state']['terminated'].get('message')=='CHILD_REAPED' for p in actual)
        assert any('edgeai.io/recovery-test-sentinel' in p['metadata']['finalizers'] for p in actual)
        assert kube.read(object_path('Job',namespace,job['metadata']['name']))['spec']['suspend'] is True
        report.update(terminatedContainers=2,reapedChildren=2,retainedPodEvidence=True,
            quotaUidPreserved=True,otherFinalizerPreserved=True,activated=False,globalQuiescenceProven=False)
        passed('resume-proves-both-parent-and-child-exit-and-preserves-other-finalizer')
        # The CLI itself verifies actual server-side admission rejection before and after stopping.
        report['admissionProbes']=['Pod','Job']; passed('actual-server-dry-run-rejects-new-pods-and-jobs-at-retained-fence')
        code=0
    except Exception as error:
        import traceback
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as out: traceback.print_exc(file=out)
        print('FAIL: recovery stop acceptance; private evidence retained',flush=True)
    finally:
        try:
            if namespace_uid:
                ns=kube.read('/api/v1/namespaces/'+namespace)
                assert ns['metadata']['uid']==namespace_uid and ns['metadata']['labels']['edgeai.io/recovery-test']==token
                call(['delete','--raw','/api/v1/namespaces/'+namespace,'-f','-'],
                    {'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':namespace_uid}})
                deadline=time.monotonic()+180
                while time.monotonic()<deadline:
                    ns=call(['get','namespace',namespace,'--ignore-not-found','-o','json'])
                    if ns is None: break
                    assert ns['metadata']['uid']==namespace_uid and ns['metadata']['labels']['edgeai.io/recovery-test']==token
                    for p in kube.items(namespace,'Pod')[0]:
                        meta=p['metadata']; finalizers=meta.get('finalizers',[])
                        removable={FINALIZER,'edgeai.io/recovery-test-sentinel'}&set(finalizers)
                        if removable and termination_proof(p) is not None:
                            patch=[{'op':'test','path':'/metadata/uid','value':meta['uid']},
                                {'op':'test','path':'/metadata/resourceVersion','value':meta['resourceVersion']},
                                {'op':'replace','path':'/metadata/finalizers','value':[v for v in finalizers if v not in removable]}]
                            subprocess.run(command+['-n',namespace,'patch','pod',meta['name'],'--type=json','--patch-file=/dev/stdin','-o','json'],
                                input=json.dumps(patch).encode(),capture_output=True,timeout=20)
                    time.sleep(.4)
                else: raise AssertionError('Owned namespace cleanup could not confirm termination')
            report['ownedNamespaceRemoved']=True
        except Exception as error:
            report['cleanupFailureType']=type(error).__name__; code=1
        report['status']='PASS' if code==0 else 'FAIL'
        args.report.parent.mkdir(parents=True,exist_ok=True); args.report.write_text(json.dumps(report,indent=2)+'\n')
        print(report['status']+': '+str(len(report['cases']))+' Kubernetes recovery stop cases; private evidence '+str(work))
    return code


if __name__=='__main__': raise SystemExit(main())
