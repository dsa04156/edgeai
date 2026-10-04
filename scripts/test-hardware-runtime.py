"""Public Profile/Workflow/Run to native hardware and fixed S3 Result; synthetic, not model acceptance."""
import argparse
import base64
import http.cookiejar
import json
import os
import re
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

from image_identity import verify_image_id,pinned,manifest,platforms

ROOT=Path(__file__).resolve().parents[1]
CASES={'cuda-amd64':('amd64','cuda','nvidia.com/gpu'),
    'cuda-arm64':('arm64','cuda','nvidia.com/gpu'),
    'cuda-edge-arm64':('arm64','cuda','nvidia.com/gpu.shared'),
    'aries-access':('amd64','aries','mobilint.com/npu')}


def wait(check,seconds=180):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        value=check()
        if value:return value
        time.sleep(.3)
    raise TimeoutError('Owned hardware runtime boundary timed out')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context',required=True)
    parser.add_argument('--cases',nargs='+',choices=CASES,default=['cuda-amd64','aries-access'])
    parser.add_argument('--runner-image')
    parser.add_argument('--runner-source')
    parser.add_argument('--cuda-arm64-runtime-class')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/hardware-runtime.json')
    args=parser.parse_args();assert bool(args.runner_image)==bool(args.runner_source)
    if args.runner_source:assert re.fullmatch(r'[0-9a-f]{40}',args.runner_source)
    release=json.loads((ROOT/'deploy/kubernetes/overlays/dev/release.json').read_text())
    image=args.runner_image or 'ghcr.io/dsa04156/edgeai-runner@'+release['runnerDigest']
    assert pinned(image)[0]=='ghcr.io/dsa04156/edgeai-runner'
    if any(CASES[c][0]=='arm64' for c in args.cases):assert 'linux/arm64' in platforms(manifest(image))
    k=['kubectl','--context',args.context,'--request-timeout=20s']
    def call(arguments):
        r=subprocess.run(k+arguments,capture_output=True,timeout=30)
        if r.returncode:raise RuntimeError('Kubernetes operation failed; private response suppressed')
        return r.stdout
    def read(arguments):return json.loads(call(arguments))
    for name in ('edgeai','edgeai-runtimes'):
        n=read(['get','namespace',name,'-o','json'])
        assert not n['metadata'].get('deletionTimestamp') and n['metadata']['labels']['app.kubernetes.io/part-of']=='edgeai'
        assert n['metadata']['labels']['app.kubernetes.io/managed-by']=='edgeai-bootstrap'
    token='hardware-runtime-'+uuid.uuid4().hex[:12]
    keys={c:str(uuid.uuid4()) for c in args.cases};runs=set();forwards=[];request=None
    report={'scope':'public-api-hardware-runtime','sourceMode':'SYNTHETIC','fullHardwareAcceptance':False,
        'runnerImage':image,'runnerSourceRevision':args.runner_source or release['sourceRevision'],
        'cases':[],'ownedRuntimeResourcesRemoved':False}
    def own_runs():
        sql='SELECT id FROM edgeai.workflow_run WHERE idempotency_key IN('+','.join("'"+key+"'" for key in keys.values())+')'
        rows=call(['-n','edgeai','exec','edgeai-postgres-0','--','env','PGOPTIONS=-c default_transaction_read_only=on',
            'psql','-U','edgeai','-d','edgeai','-X','-A','-t','-v','ON_ERROR_STOP=1','-c',sql]).decode().splitlines()
        return {str(uuid.UUID(r)) for r in rows}
    def resources(run):
        assert run in runs
        return read(['-n','edgeai-runtimes','get','pods,jobs,secrets','-l','edgeai.io/run-id='+run,'-o','json'])['items']
    code=1;stage='tls-setup'
    with tempfile.TemporaryDirectory(prefix='hardware-runtime-',dir=ROOT/'.tools') as work:
        ca=Path(work)/'ca.crt';ca.write_text(read(['-n','edgeai','get','configmap','edgeai-runtime-ca-v1','-o','json'])['data']['ca.crt'])
        tls=ssl.create_default_context(cafile=str(ca))
        def forward(service,port,health):
            with socket.socket() as sock:sock.bind(('127.0.0.1',0));local=sock.getsockname()[1]
            p=subprocess.Popen(k+['-n','edgeai','port-forward','--address','127.0.0.1','svc/'+service,str(local)+':'+str(port)],
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);forwards.append(p)
            origin='https://localhost:'+str(local)
            def ready():
                assert p.poll() is None
                try:
                    with urllib.request.urlopen(origin+health,context=tls,timeout=2) as r:return r.status==200
                except OSError:return False
            wait(ready,30);return origin
        try:
            origin=forward('edgeai-api',18443,'/actuator/health/readiness')
            storage=forward('edgeai-minio',9000,'/minio/health/ready')
            config=read(['-n','edgeai','get','configmap','edgeai-config','-o','json'])['data']
            secret=read(['-n','edgeai','get','secret','edgeai-runtime','-o','json'])['data']
            auth='Basic '+base64.b64encode((config['EDGEAI_API_USER']+':'+base64.b64decode(secret['EDGEAI_API_PASSWORD']).decode()).encode()).decode()
            client=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),urllib.request.HTTPSHandler(context=tls))
            csrf=None
            def request(path,method='GET',body=None,key=None,expected=200):
                nonlocal csrf
                headers={'Authorization':auth}
                if method!='GET':
                    csrf=request('csrf')['token'];headers.update({'X-CSRF-TOKEN':csrf,'Content-Type':'application/json'})
                if key:headers['Idempotency-Key']=key
                req=urllib.request.Request(origin+'/api/v1/'+path,method=method,headers=headers,data=None if body is None else json.dumps(body).encode())
                try:response=client.open(req,timeout=15)
                except urllib.error.HTTPError as error:response=error
                with response:
                    payload=response.read(1048577);assert len(payload)<=1048576
                    if response.status!=expected:
                        report['unexpectedHttpStatus']=response.status
                        try:
                            value=json.loads(payload).get('code','')
                            if isinstance(value,str) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,79}',value):report['unexpectedApiCode']=value
                        except (ValueError,AttributeError):pass
                        raise AssertionError('Hardware management request failed')
                    return json.loads(payload)
            for case in args.cases:
                stage='select-'+case;architecture,probe,resource=CASES[case]
                nodes=read(['get','nodes','-o','json'])['items']
                def healthy(n):
                    cond={c['type']:c['status'] for c in n['status']['conditions']}
                    return (n['status']['nodeInfo']['architecture']==architecture and not n['spec'].get('unschedulable') and
                        not any(t['effect'] in ('NoSchedule','NoExecute') for t in n['spec'].get('taints',[])) and cond.get('Ready')=='True' and
                        all(cond.get(c)=='False' for c in ('DiskPressure','MemoryPressure','PIDPressure')) and int(n['status']['allocatable'].get(resource,'0'))>=1)
                candidates=[n for n in nodes if healthy(n)]
                if '-edge-' in case:candidates=[n for n in candidates if 'node-role.kubernetes.io/edge' in n['metadata']['labels']]
                runtime_class=None
                if case=='cuda-arm64' and args.cuda_arm64_runtime_class:
                    runtime_class=read(['get','runtimeclass',args.cuda_arm64_runtime_class,'-o','json'])
                    selector=runtime_class.get('scheduling',{}).get('nodeSelector',{})
                    candidates=[n for n in candidates if all(n['metadata']['labels'].get(k)==v for k,v in selector.items())]
                busy=set()
                for pod in read(['get','pods','--all-namespaces','-o','json'])['items']:
                    if pod.get('status',{}).get('phase') in ('Succeeded','Failed'):continue
                    if any(int(c.get('resources',{}).get('requests',{}).get(resource,c.get('resources',{}).get('limits',{}).get(resource,'0')))>0
                        for c in pod['spec'].get('containers',[])+pod['spec'].get('initContainers',[])):busy.add(pod['spec'].get('nodeName'))
                candidates=[n for n in candidates if n['metadata']['name'] not in busy]
                candidates.sort(key=lambda n:(max(c.get('lastTransitionTime','') for c in n['status']['conditions'] if c['type'] in ('Ready','DiskPressure','MemoryPressure','PIDPressure')),n['metadata']['name']))
                if not candidates:report['cases'].append({'case':case,'status':'BLOCKED','reason':'NO_AVAILABLE_HEALTHY_NODE'});continue
                node=candidates[0];node_id=node['metadata']['uid'];request('nodes/'+node_id)
                expected={'sourceMode':'SYNTHETIC','probe':probe,'machine':{'amd64':'x86_64','arm64':'aarch64'}[architecture],
                    'uid':10001,'modelAcceptanceVerified':False}
                if probe=='cuda':expected['output']=[5,9,33,257]
                else:expected.update(nonRootOpenCloseVerified=True,inferenceVerified=False)
                workload=(ROOT/'scripts/hardware_probe_workload.py').read_text()
                command="import os,json,platform,time; from pathlib import Path; time.sleep(8)\n"+"scope={'__name__':'hardware_probe'}; exec("+repr(workload)+",scope)\n"
                command+="value=scope["+repr(probe)+"](); result="+repr({k:v for k,v in expected.items() if k not in ('machine','uid')})+"\n"
                command+="result.update(machine=platform.machine(),uid=os.getuid())\n"
                if probe=='cuda':command+="result['output']=value['output']\n"
                else:command+="result.update(nonRootOpenCloseVerified=value['nonRootOpenCloseVerified'],inferenceVerified=value['inferenceVerified'])\n"
                command+="(Path(os.environ['EDGEAI_OUTPUT_DIR'])/'output').write_text(json.dumps(result))\n"
                spec=json.loads((ROOT/'contracts/profiles/service-execution.example.json').read_text())
                spec.update(image=image,command=['python3','-c',"import sys;exec(''.join(sys.argv[1:]))"],
                    args=[command[i:i+3000] for i in range(0,len(command),3000)],
                    platform={'os':'linux','architectures':[architecture]},timeoutSeconds=120)
                spec['resources']['requests'][resource]='1';spec['resources']['limits'][resource]='1'
                if runtime_class:spec['runtimeClassName']=runtime_class['metadata']['name']
                stage='profile-'+case
                profile=request('profiles/SERVICE','POST',{'key':token+'-'+case,'version':'1.0.0','spec':spec},expected=201)['id']
                workflow=request('workflows','POST',{'key':token+'-'+case,'displayName':'Synthetic hardware runtime verification'},expected=201)['id']
                version=request('workflows/'+workflow+'/versions','POST',{'version':'1.0.0','tasks':[{'key':'probe','serviceProfileVersionId':profile,'parameters':{}}],'dependencies':[]},expected=201)['id']
                row={'case':case,'status':'RUNNING','nodeName':node['metadata']['name'],'nodeUid':node_id,'resource':resource,'runtimeClass':spec.get('runtimeClassName'),'observedPods':{}}
                report['cases'].append(row);stage='run-'+case
                run=request('workflow-runs','POST',{'workflowVersionId':version,'parameters':{},'execution':{'mode':'NODE','nodeId':node_id}},key=keys[case],expected=201)['id']
                runs.add(run);row['runId']=run
                def done():
                    for pod in resources(run):
                        if pod['kind']!='Pod' or not pod['spec'].get('nodeName'):continue
                        assert pod['metadata']['labels']['app.kubernetes.io/managed-by']=='edgeai-runtime-controller'
                        assert pod['spec']['nodeName']==node['metadata']['name']
                        container,=pod['spec']['containers']
                        assert container['resources']['requests'][resource]==container['resources']['limits'][resource]=='1'
                        assert pod['spec'].get('runtimeClassName')==spec.get('runtimeClassName')
                        statuses=pod.get('status',{}).get('containerStatuses',[])
                        if not statuses or not statuses[0].get('imageID'):continue
                        verify_image_id(image,statuses[0]['imageID'],'linux/'+architecture)
                        row['observedPods'][pod['metadata']['uid']]={'attemptId':pod['metadata']['labels']['edgeai.io/attempt-id'],'imageID':statuses[0]['imageID'],'nodeUid':node_id}
                    detail=request('workflow-runs/'+run)
                    if detail['run']['state'] in ('FAILED','CANCELLED'):
                        row['taskStates']=[t['state'] for t in detail['tasks']];raise AssertionError('Hardware Run failed')
                    return detail if detail['run']['state']=='SUCCEEDED' else None
                stage='execution-'+case;detail=wait(done,240);task,=detail['tasks'];assert task['state']=='SUCCEEDED'
                attempts=request('tasks/'+task['id'])['attempts'];assert len(attempts)==1 and attempts[0]['state']=='SUCCEEDED'
                result,=request('tasks/'+task['id']+'/results')['items']
                assert result['attemptId']==attempts[0]['id'] and row['observedPods'][result['producerPodUid']]['attemptId']==result['attemptId']
                artifact,=result['artifacts'];stage='artifact-'+case
                storage_secret=read(['-n','edgeai','get','secret','edgeai-artifact-storage','-o','json'])['data']
                env={**os.environ,'EDGEAI_STORAGE_URL':storage,'NODE_EXTRA_CA_CERTS':str(ca),
                    **{name:base64.b64decode(storage_secret[name]).decode() for name in ('EDGEAI_MINIO_USER','EDGEAI_MINIO_PASSWORD')}}
                verified=subprocess.run(['node','scripts/verify-runtime-artifacts.mjs'],input=json.dumps([{'artifact':artifact,'expected':expected}]).encode(),env=env,capture_output=True,timeout=60)
                assert verified.returncode==0,'Hardware artifact bytes/checksum/calculation differ'
                wait(lambda:not resources(run),90)
                row.update(status='PASS',result=result,expected=expected,resourcesRemoved=True)
                print('PASS: public Profile/Run -> '+case+' -> fixed S3 Result; owned runtime removed',flush=True)
            code=2 if any(c['status']=='BLOCKED' for c in report['cases']) else 0
        except Exception as error:
            report.update(failureStage=stage,failureType=type(error).__name__);code=1
            for row in report['cases']:
                if row['status']=='RUNNING':row['status']='FAIL'
        finally:
            try:
                if request:
                    runs.update(own_runs())
                    for run in runs:
                        if request('workflow-runs/'+run)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):
                            request('workflow-runs/'+run+'/cancel','POST',{})
                        wait(lambda:not resources(run),90)
                        wait(lambda:request('workflow-runs/'+run)['run']['state'] in ('SUCCEEDED','FAILED','CANCELLED'),90)
                report['ownedRuntimeResourcesRemoved']=True
            except Exception as error:report['cleanupFailureType']=type(error).__name__;code=1
            for process in forwards:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            report['status']='PASS' if code==0 else 'BLOCKED' if code==2 else 'FAIL'
            args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2)+'\n')
    return code


if __name__=='__main__':raise SystemExit(main())
