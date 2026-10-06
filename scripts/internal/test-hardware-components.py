"""Run owned native CPU, CUDA arithmetic and ARIES access Pods; full M10 remains separate."""
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

from image_identity import verify_image_id

ROOT=Path(__file__).resolve().parents[2]
IMAGE='docker.io/library/python:3.13-slim-bookworm@sha256:5024f48ba9441d4b13a95d3945abc6365538e3a31109833367a1923523c6efed'
CASES={'cpu-amd64':('amd64','cpu',None),'cpu-arm64':('arm64','cpu',None),
    'cuda-amd64':('amd64','cuda','nvidia.com/gpu'),'cuda-arm64':('arm64','cuda','nvidia.com/gpu'),
    'cpu-edge-arm64':('arm64','cpu',None),'cuda-edge-arm64':('arm64','cuda','nvidia.com/gpu.shared'),
    'aries-access':('amd64','aries','mobilint.com/npu')}


def healthy(node):
    c={v['type']:v for v in node['status'].get('conditions',[])}
    return (not node['metadata'].get('deletionTimestamp') and not node['spec'].get('unschedulable') and
        c.get('Ready',{}).get('status')=='True' and all(c.get(k,{}).get('status')=='False' for k in ('DiskPressure','MemoryPressure','PIDPressure')) and
        not any(t['effect'] in ('NoSchedule','NoExecute') for t in node['spec'].get('taints',[])))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--context',required=True)
    parser.add_argument('--cases',nargs='+',choices=CASES,default=list(CASES))
    parser.add_argument('--cuda-arm64-runtime-class',default=None)
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/hardware-components.json');args=parser.parse_args()
    token=uuid.uuid4().hex;namespace='edgeai-hardware-'+token[:12];ns_uid=None;priority_uid=None
    labels={'app.kubernetes.io/part-of':'edgeai','app.kubernetes.io/managed-by':'edgeai-hardware-test','edgeai.io/test-id':token}
    kube=['kubectl','--context',args.context,'--request-timeout=20s']
    operation=None;stage='namespace-create';active_case=None
    def call(arguments,value=None,json_output=True):
        nonlocal operation
        operation=arguments
        r=subprocess.run(kube+arguments,input=None if value is None else json.dumps(value).encode(),capture_output=True,timeout=30)
        if r.returncode:
            diagnostic=ROOT/'.tools/hardware-errors'/token
            diagnostic.parent.mkdir(parents=True,exist_ok=True)
            fd=os.open(diagnostic,os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
            with os.fdopen(fd,'wb') as out:out.write(r.stderr)
            raise RuntimeError('Owned hardware Kubernetes operation failed; private response suppressed')
        return json.loads(r.stdout) if json_output and r.stdout.strip() else r.stdout.decode() if not json_output else None
    def read_ns():
        n=call(['get','namespace',namespace,'-o','json'])
        if n['metadata']['uid']!=ns_uid or any(n['metadata']['labels'].get(k)!=v for k,v in labels.items()):
            raise RuntimeError('Hardware test namespace ownership changed')
        return n
    def create(kind,name,spec=None,**fields):
        read_ns();obj={'apiVersion':'v1','kind':kind,'metadata':{'name':name,'namespace':namespace,'labels':labels},**fields}
        if spec is not None:obj['spec']=spec
        return call(['create','-f','-','-o','json'],obj)
    report={'scope':'hardware-component-tests','status':'RUNNING','fullHardwareAcceptance':False,'sourceMode':'SYNTHETIC',
        'observedAt':datetime.now(timezone.utc).isoformat(),'image':IMAGE,'cases':[],'ownedNamespaceRemoved':False,'ownedPriorityClassRemoved':False}
    code=1
    try:
        ns=call(['create','-f','-','-o','json'],{'apiVersion':'v1','kind':'Namespace','metadata':{'name':namespace,'labels':labels}})
        ns_uid=ns['metadata']['uid'];report['namespaceUid']=ns_uid
        stage='priorityclass-create';read_ns()
        priority=call(['create','-f','-','-o','json'],{'apiVersion':'scheduling.k8s.io/v1','kind':'PriorityClass',
            'metadata':{'name':namespace,'labels':labels,'ownerReferences':[{'apiVersion':'v1','kind':'Namespace','name':namespace,'uid':ns_uid}]},
            'value':0,'globalDefault':False,'preemptionPolicy':'Never'})
        priority_uid=priority['metadata']['uid'];report['priorityClassUid']=priority_uid
        stage='configmap-create'
        create('ConfigMap','probe',immutable=True,data={'probe.py':(ROOT/'scripts/internal/hardware_probe_workload.py').read_text()})
        for case in args.cases:
            active_case=case;stage='node-selection'
            architecture,kind,resource=CASES[case]
            nodes=call(['get','nodes','-o','json'])['items'];candidates=[n for n in nodes if healthy(n) and n['status']['nodeInfo']['architecture']==architecture and
                (resource is None or int(n['status']['allocatable'].get(resource,'0'))>=1)]
            if '-edge-' in case:
                candidates=[n for n in candidates if 'node-role.kubernetes.io/edge' in n['metadata']['labels'] and
                    any(c['type']=='Ready' and c.get('reason')=='EdgeReady' for c in n['status']['conditions'])]
            runtime_class=None
            if case=='cuda-arm64' and args.cuda_arm64_runtime_class:
                runtime_class=call(['get','runtimeclass',args.cuda_arm64_runtime_class,'-o','json'])
                selector=runtime_class.get('scheduling',{}).get('nodeSelector',{})
                candidates=[n for n in candidates if all(n['metadata']['labels'].get(k)==v for k,v in selector.items())]
            pods=call(['get','pods','--all-namespaces','-o','json'])['items'] if resource else []
            def free(n):
                used=0
                for p in pods:
                    if p.get('spec',{}).get('nodeName')!=n['metadata']['name'] or p.get('status',{}).get('phase') in ('Succeeded','Failed'):continue
                    regular=sum(int(c.get('resources',{}).get('requests',{}).get(resource,c.get('resources',{}).get('limits',{}).get(resource,'0'))) for c in p['spec'].get('containers',[]))
                    init=max([int(c.get('resources',{}).get('requests',{}).get(resource,c.get('resources',{}).get('limits',{}).get(resource,'0'))) for c in p['spec'].get('initContainers',[])]+[0])
                    used+=max(regular,init)
                return int(n['status']['allocatable'].get(resource,'0'))-used
            if resource:candidates=[n for n in candidates if free(n)>=1]
            # Prefer nodes whose relevant health conditions have been stable longest.
            # NetworkUnavailable is often first and does not reveal pressure flapping.
            def last_health_change(n):
                return max(c.get('lastTransitionTime','') for c in n['status']['conditions']
                    if c['type'] in ('Ready','DiskPressure','MemoryPressure','PIDPressure'))
            candidates.sort(key=lambda n:(last_health_change(n),n['metadata']['name']))
            if not candidates:
                report['cases'].append({'case':case,'status':'BLOCKED','reason':'NO_READY_UNTAINTED_AVAILABLE_NODE'});continue
            node=candidates[0];node_name=node['metadata']['name'];node_uid=node['metadata']['uid']
            resources={'requests':{'cpu':'50m','memory':'64Mi'},'limits':{'cpu':'1','memory':'512Mi'}}
            if resource:resources['requests'][resource]='1';resources['limits'][resource]='1'
            container={'name':'probe','image':IMAGE,'command':['python3','-B','/probe/probe.py',kind],'resources':resources,
                'env':[{'name':'NVIDIA_DRIVER_CAPABILITIES','value':'compute,utility'},{'name':'CUDA_CACHE_PATH','value':'/tmp/cuda-cache'},
                    {'name':'LD_LIBRARY_PATH','value':'/usr/local/nvidia/lib64:/usr/local/nvidia/lib'}],
                'securityContext':{'allowPrivilegeEscalation':False,'readOnlyRootFilesystem':True,'capabilities':{'drop':['ALL']}},
                'volumeMounts':[{'name':'code','mountPath':'/probe','readOnly':True},{'name':'tmp','mountPath':'/tmp'}]}
            spec={'restartPolicy':'Never','activeDeadlineSeconds':180,'terminationGracePeriodSeconds':15,'automountServiceAccountToken':False,
                'priorityClassName':namespace,'preemptionPolicy':'Never','nodeSelector':{'kubernetes.io/hostname':node['metadata']['labels']['kubernetes.io/hostname']},
                'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001,'seccompProfile':{'type':'RuntimeDefault'}},
                'containers':[container],'volumes':[{'name':'code','configMap':{'name':'probe'}},{'name':'tmp','emptyDir':{'sizeLimit':'64Mi'}}]}
            if runtime_class:spec['runtimeClassName']=runtime_class['metadata']['name']
            stage='pod-create'
            pod=create('Pod',case,spec);pod_uid=pod['metadata']['uid'];deadline=time.monotonic()+240
            assert pod['spec']['preemptionPolicy']=='Never' and pod['spec']['priority']==0
            row={'case':case,'nodeName':node_name,'nodeUid':node_uid,'podUid':pod_uid,
                'resource':resource,'runtimeClass':spec.get('runtimeClassName'),'status':'RUNNING',
                'nodeReadyReason':next(c.get('reason') for c in node['status']['conditions'] if c['type']=='Ready'),
                'nodeHealthLastChangedAt':last_health_change(node)}
            report['cases'].append(row);stage='pod-wait'
            while time.monotonic()<deadline:
                read_ns();pod=call(['-n',namespace,'get','pod',case,'-o','json']);assert pod['metadata']['uid']==pod_uid
                if pod.get('status',{}).get('phase') in ('Succeeded','Failed'):break
                time.sleep(1)
            else:raise TimeoutError('Owned hardware probe did not become terminal')
            current=call(['get','node',node_name,'-o','json']);assert current['metadata']['uid']==node_uid
            row['phase']=pod['status']['phase'];stage='container-result'
            statuses=pod.get('status',{}).get('containerStatuses',[])
            if len(statuses)!=1 or 'terminated' not in statuses[0].get('state',{}):
                row.update(status='FAIL',reason=pod['status'].get('reason','NO_CONTAINER_TERMINATION'),
                    nodeConditions={c['type']:{k:c.get(k) for k in ('status','reason','lastTransitionTime')}
                        for c in current['status']['conditions'] if c['type'] in ('Ready','DiskPressure','MemoryPressure','PIDPressure')})
                print('FAIL: actual '+case+' component; '+row['reason'],flush=True);continue
            end=statuses[0]['state']['terminated'];row.update(exitCode=end['exitCode'],imageID=statuses[0]['imageID'])
            stage='image-identity';verify_image_id(IMAGE,row['imageID'],'linux/'+architecture)
            stage='pod-logs'
            result=json.loads(call(['-n',namespace,'logs',case],json_output=False))
            assert result['scope']=='native-hardware-component' and result['probe']==kind and result['uid']==10001
            assert result['machine']=={'amd64':'x86_64','arm64':'aarch64'}[architecture]
            row['workload']=result;row['status']='PASS' if end['exitCode']==0 and result['status']=='PASS' else 'FAIL'
            print(row['status']+': actual '+case+' component; model acceptance remains separate',flush=True)
        code=1 if any(r['status']=='FAIL' for r in report['cases']) else 2 if any(r['status']=='BLOCKED' for r in report['cases']) else 0
    except Exception as error:
        report.update(failureType=type(error).__name__,failureStage=stage,failureCase=active_case,failedOperation=operation);code=1
        for row in report['cases']:
            if row['status']=='RUNNING':row['status']='FAIL';row['reason']='PROBE_INTERRUPTED'
    finally:
        try:
            if ns_uid:
                read_ns();call(['delete','--raw','/api/v1/namespaces/'+namespace,'-f','-'],
                    {'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':ns_uid}})
                deadline=time.monotonic()+180
                while time.monotonic()<deadline:
                    n=call(['get','namespace',namespace,'--ignore-not-found','-o','json'])
                    if n is None:break
                    assert n['metadata']['uid']==ns_uid;time.sleep(1)
                else:raise TimeoutError('Owned hardware namespace cleanup not confirmed')
            report['ownedNamespaceRemoved']=True
        except Exception as error:report['cleanupFailureType']=type(error).__name__;code=1
        try:
            if priority_uid:
                priority=call(['get','priorityclass',namespace,'--ignore-not-found','-o','json'])
                if priority:
                    if priority['metadata']['uid']!=priority_uid or any(priority['metadata']['labels'].get(k)!=v for k,v in labels.items()):
                        raise RuntimeError('Hardware priority class ownership changed')
                    call(['delete','--raw','/apis/scheduling.k8s.io/v1/priorityclasses/'+namespace,'-f','-'],
                        {'apiVersion':'v1','kind':'DeleteOptions','preconditions':{'uid':priority_uid}})
                    assert call(['get','priorityclass',namespace,'--ignore-not-found','-o','json']) is None
            report['ownedPriorityClassRemoved']=True
        except Exception as error:report['priorityCleanupFailureType']=type(error).__name__;code=1
        report['status']='PASS' if code==0 else 'BLOCKED' if code==2 else 'FAIL'
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2)+'\n')
    return code


if __name__=='__main__':raise SystemExit(main())
