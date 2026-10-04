"""Fence new Kubernetes producers and retain observed Pods until container termination is proved.

This component never activates a restored database, removes the fence or releases its finalizers.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

from postgres_backup import Blocked, ROOT, private_file
from recovery_kubernetes import Kubernetes, PART, MANAGER, RUNTIME, VD, classify, namespace_name, uid

FENCE = 'edgeai-recovery-fence'
FINALIZER = 'edgeai.io/recovery-stop'
OPERATION = 'edgeai.io/recovery-operation'
HARD = {'count/pods':'0','count/jobs.batch':'0'}
EMPTY = {'runtimes':[],'vds':[]}


def utc(): return datetime.now(timezone.utc).isoformat()


def object_path(kind,namespace,name):
    if not re.fullmatch('[a-z0-9][a-z0-9.-]{0,252}',name): raise ValueError('Invalid object name')
    group,plural = {'Pod':('/api/v1','pods'),'Job':('/apis/batch/v1','jobs'),
        'ResourceQuota':('/api/v1','resourcequotas')}[kind]
    return group+'/namespaces/'+namespace_name(namespace)+'/'+plural+'/'+name


def termination_proof(pod):
    """A deleted API object or a NodeLost phase alone is never termination evidence."""
    meta,spec,status=pod['metadata'],pod['spec'],pod.get('status',{})
    if not meta.get('deletionTimestamp'): return None
    if spec.get('ephemeralContainers'): raise Blocked('Ephemeral containers require a separate termination review')
    if not spec.get('nodeName'):
        if status.get('containerStatuses') or status.get('initContainerStatuses'): return None
        return {'kind':'NEVER_BOUND_TO_NODE','containers':[]}
    if status.get('phase') not in ('Succeeded','Failed'): return None
    containers=[]
    for spec_key,status_key in [('containers','containerStatuses'),('initContainers','initContainerStatuses')]:
        expected={c['name'] for c in spec.get(spec_key,[])}
        actual=status.get(status_key,[])
        if {c['name'] for c in actual}!=expected or len(actual)!=len(expected): return None
        for container in actual:
            ended=container.get('state',{}).get('terminated')
            if (not ended or ended.get('reason') in ('ContainerStatusUnknown','ContainerCreating','NodeLost')
                    or not ended.get('containerID') or not ended.get('startedAt') or not ended.get('finishedAt')
                    or type(ended.get('exitCode')) is not int): return None
            containers.append({'name':container['name'],'containerId':ended['containerID'],
                'exitCode':ended['exitCode'],'startedAt':ended['startedAt'],'finishedAt':ended['finishedAt']})
    if not containers: return None
    return {'kind':'ALL_CONTAINERS_TERMINATED','nodeName':spec['nodeName'],'containers':containers}


class Stop:
    def __init__(self,kube,namespace,namespace_uid,operation,deadline):
        self.kube,self.namespace,self.namespace_uid=kube,namespace_name(namespace),uid(namespace_uid)
        self.operation,self.deadline=uid(operation),time.monotonic()+deadline
        self.quota_uid=None
        self.fence_owned=False
        self.job_uids=set()

    def scope(self):
        if self.kube.namespace(self.namespace)!=self.namespace_uid:
            raise ValueError('Namespace UID differs from the selected recovery scope')

    def time_left(self):
        if time.monotonic()>=self.deadline: raise Blocked('Termination is unconfirmed; recovery fence and evidence are retained')

    def call(self,args,document):
        self.scope(); self.time_left()
        return subprocess.run(self.kube.command+args,input=json.dumps(document).encode(),capture_output=True,timeout=25)

    def owned(self,kind,item):
        result=classify(kind,item,EMPTY,self.job_uids)
        if result is None or result['classification']!='ABSENT_FROM_RESTORED_DATABASE':
            raise ValueError('Dedicated recovery namespace contains an unowned or conflicting producer')
        if kind=='Pod':
            meta=item['metadata']
            if item['spec'].get('ephemeralContainers'): raise Blocked('Ephemeral containers require a separate termination review')
            if FINALIZER in meta.get('finalizers',[]) and meta.get('annotations',{}).get(OPERATION)!=self.operation:
                raise ValueError('Pod is retained by a different recovery operation')
            if meta.get('deletionTimestamp') and FINALIZER not in meta.get('finalizers',[]):
                raise Blocked('Pod termination began before recovery could retain its evidence')
        return result

    def snapshot(self):
        self.scope()
        jobs,_=self.kube.items(self.namespace,'Job')
        self.job_uids={j['metadata']['uid'] for j in jobs}
        for job in jobs: self.owned('Job',job)
        pods,_=self.kube.items(self.namespace,'Pod')
        for pod in pods: self.owned('Pod',pod)
        return jobs,pods

    def fence(self):
        path=object_path('ResourceQuota',self.namespace,FENCE)
        # A list avoids interpreting a transport error as an absent fence.
        quotas=self.kube.read('/api/v1/namespaces/'+self.namespace+'/resourcequotas')['items']
        current=next((q for q in quotas if q['metadata']['name']==FENCE),None)
        if current is None:
            document={'apiVersion':'v1','kind':'ResourceQuota','metadata':{'name':FENCE,'namespace':self.namespace,
                'labels':{PART:'edgeai',MANAGER:'edgeai-recovery'},'annotations':{OPERATION:self.operation}},'spec':{'hard':HARD}}
            response=self.call(['create','-f','-','-o','json'],document)
            if response.returncode: raise RuntimeError('Recovery fence creation failed; existing objects were not overwritten')
            current=json.loads(response.stdout)
        self.quota_uid=uid(current['metadata']['uid'])
        while True:
            self.time_left(); self.scope()
            current=self.kube.read(path); self.check_fence(current)
            self.fence_owned=True
            if current.get('status',{}).get('hard')==HARD and self.probe('Pod') and self.probe('Job'): return
            time.sleep(.3)

    def check_fence(self,value):
        meta=value['metadata']
        if (meta.get('uid')!=self.quota_uid or meta.get('name')!=FENCE or meta.get('namespace')!=self.namespace
                or meta.get('labels',{}).get(PART)!='edgeai' or meta.get('labels',{}).get(MANAGER)!='edgeai-recovery'
                or meta.get('annotations',{}).get(OPERATION)!=self.operation or meta.get('deletionTimestamp')
                or value.get('spec')!={'hard':HARD}):
            raise ValueError('Recovery fence identity or policy differs')

    def probe(self,kind):
        # Server-side dry run exercises admission without ever creating a probe workload.
        pod={'restartPolicy':'Never','automountServiceAccountToken':False,
            'containers':[{'name':'probe','image':'unused.invalid/edgeai:quota-probe'}]}
        value={'apiVersion':'batch/v1' if kind=='Job' else 'v1','kind':kind,
            'metadata':{'namespace':self.namespace,'name':'edgeai-fence-probe-'+uuid.uuid4().hex},
            'spec':{'template':{'spec':pod}} if kind=='Job' else pod}
        result=self.call(['create','--dry-run=server','-f','-','-o','json'],value)
        return result.returncode!=0 and ('exceeded quota: '+FENCE).encode() in result.stderr

    def fresh(self,kind,original):
        self.time_left(); self.scope()
        value=self.kube.read(object_path(kind,self.namespace,original['metadata']['name']))
        if value['metadata']['uid']!=original['metadata']['uid']:
            raise ValueError('Producer UID changed; replacement will not be modified')
        self.owned(kind,value)
        return value

    def patch(self,kind,original,changes):
        for _ in range(15):
            value=self.fresh(kind,original)
            meta=value['metadata']; operations=changes(value)
            if not operations: return value
            operations=[{'op':'test','path':'/metadata/uid','value':meta['uid']},
                {'op':'test','path':'/metadata/resourceVersion','value':meta['resourceVersion']},*operations]
            result=self.call(['-n',self.namespace,'patch',kind.lower(),meta['name'],'--type=json',
                '--patch-file=/dev/stdin','-o','json'],operations)
            if result.returncode==0: return json.loads(result.stdout)
            # Re-read and revalidate ownership on every retry; no blind patch after a conflict.
            time.sleep(.1)
        raise RuntimeError('Recovery compare-and-set patch did not succeed; fence remains')

    def protect(self,pod):
        def changes(value):
            meta=value['metadata']; existing=meta.get('finalizers',[])
            if FINALIZER in existing: return []
            annotations={**meta.get('annotations',{}),OPERATION:self.operation}
            return [{'op':'add','path':'/metadata/annotations','value':annotations},
                {'op':'add','path':'/metadata/finalizers','value':[*existing,FINALIZER]}]
        return self.patch('Pod',pod,changes)

    def terminate(self,pod):
        for _ in range(15):
            value=self.fresh('Pod',pod); meta=value['metadata']
            if FINALIZER not in meta.get('finalizers',[]): raise ValueError('Recovery retention finalizer is absent')
            if meta.get('deletionTimestamp'): return
            # Preserve the configured grace period. Never force-delete an observed producer.
            response=self.call(['delete','--raw',object_path('Pod',self.namespace,meta['name']),'-f','-'],
                {'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':meta['uid'],'resourceVersion':meta['resourceVersion']}})
            if response.returncode==0: return
            time.sleep(.1)
        raise RuntimeError('Producer termination request failed; fence remains')

    def execute(self,report):
        self.snapshot()  # Refuse unrelated resources before creating a namespace-wide fence.
        self.fence(); report['quotaUid']=self.quota_uid; report['fenceRetained']=True
        jobs,pods=self.snapshot()
        retained={pod['metadata']['uid']:self.protect(pod) for pod in pods}
        for job in jobs:
            self.patch('Job',job,lambda value:[] if value['spec'].get('suspend') is True else
                [{'op':'add','path':'/spec/suspend','value':True}])
        for pod in retained.values(): self.terminate(pod)
        while True:
            self.time_left()
            self.check_fence(self.kube.read(object_path('ResourceQuota',self.namespace,FENCE)))
            current_jobs,current_pods=self.snapshot()
            if {j['metadata']['uid'] for j in jobs}!={j['metadata']['uid'] for j in current_jobs}:
                raise Blocked('Job roster changed after fencing; repeat recovery observation')
            if any(j['spec'].get('suspend') is not True for j in current_jobs):
                raise Blocked('An external writer resumed a Job; fence remains')
            if set(retained)!={p['metadata']['uid'] for p in current_pods}:
                raise Blocked('Pod roster changed after fencing; termination is unconfirmed')
            proofs=[]
            for pod in current_pods:
                if FINALIZER not in pod['metadata'].get('finalizers',[]):
                    raise Blocked('Retained termination evidence was removed externally')
                proof=termination_proof(pod)
                if proof is not None:
                    proofs.append({'name':pod['metadata']['name'],'uid':pod['metadata']['uid'],**proof})
            report['observedPods']=len(retained); report['terminatedPods']=proofs
            if len(proofs)==len(retained):
                if not self.probe('Pod') or not self.probe('Job'): raise Blocked('Admission fence no longer rejects new producers')
                report.update(status='OBSERVED_KUBERNETES_PRODUCERS_TERMINATED',completedAt=utc(),
                    suspendedJobs=[{'name':j['metadata']['name'],'uid':j['metadata']['uid']} for j in jobs])
                return
            time.sleep(.3)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--context',required=True)
    p.add_argument('--namespace',required=True)
    p.add_argument('--namespace-uid',required=True)
    p.add_argument('--recovery-id',required=True)
    p.add_argument('--timeout',type=int,default=180)
    p.add_argument('--output',type=Path)
    a=p.parse_args()
    output=a.output or ROOT/'.tools'/('recovery-stop-'+uuid.uuid4().hex)
    report={'formatVersion':1,'scope':'observed-kubernetes-producer-termination','status':'RUNNING',
        'namespace':a.namespace,'namespaceUid':a.namespace_uid,'recoveryId':a.recovery_id,
        'activated':False,'globalQuiescenceProven':False,'fenceRetained':False,'startedAt':utc(),
        'limitations':['remote-broker-device-and-storage-writers-not-fenced','pre-existing-in-flight-creates-not-globally-serialized',
            'external-admin-changes-are-not-prevented','fence-and-finalizers-must-remain-until-coordinated-recovery']}
    created=False; code=1; operation=None
    try:
        if not 1<=a.timeout<=1800: raise ValueError('Timeout must be between 1 and 1800 seconds')
        output=output.absolute(); output.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        output.mkdir(mode=0o700,exist_ok=False); created=True
        operation=Stop(Kubernetes(a.context),a.namespace,a.namespace_uid,a.recovery_id,a.timeout)
        operation.execute(report); code=0
    except Exception as error:
        report.update(status='BLOCKED' if isinstance(error,Blocked) else 'FAIL',failureType=type(error).__name__)
        if operation and operation.fence_owned:
            report['quotaUid']=operation.quota_uid
            try:
                operation.scope()
                operation.check_fence(operation.kube.read(object_path('ResourceQuota',a.namespace,FENCE)))
                report['fenceRetained']=True
            except Exception:
                report['fenceRetained']=None  # Unknown is not a successful fence observation.
        code=2 if isinstance(error,Blocked) else 1
    finally:
        if created:
            with private_file(output/'termination-report.json','w') as target: json.dump(report,target,indent=2); target.write('\n')
            print('Private recovery report: '+str(output/'termination-report.json'))
        print(report['status']+': service remains inactive; this command never removes a recovery fence or finalizer')
    return code


if __name__=='__main__': raise SystemExit(main())
