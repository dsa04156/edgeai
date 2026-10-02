"""Real Kubernetes Runner + Control Plane + versioned S3 + Result acceptance; all workload data is SYNTHETIC."""
import argparse
import base64
import copy
import http.cookiejar
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--context', required=True)
parser.add_argument('--report', default='.tools/runtime-smoke.json')
parser.add_argument('--faults', action='store_true', help='Also restart the API and inject protocol faults; dedicated test cluster only')
args = parser.parse_args()
api = os.environ['EDGEAI_SMOKE_API_URL'].rstrip('/')
origin = urllib.parse.urlsplit(api)
assert origin.scheme in ('http','https') and origin.hostname and not origin.username and not origin.password and not origin.path and not origin.query and not origin.fragment
auth = 'Basic '+base64.b64encode((os.environ['EDGEAI_API_USER']+':'+os.environ['EDGEAI_API_PASSWORD']).encode()).decode()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = None
kubectl = ['kubectl','--context',args.context,'--request-timeout=15s']
namespace = 'edgeai-runtimes'

def kube(arguments):
    result = subprocess.run(kubectl+arguments,capture_output=True,text=True,timeout=20)
    if result.returncode:
        raise AssertionError('Kubernetes read failed; response suppressed')
    return json.loads(result.stdout)

for name in ['edgeai',namespace]:
    meta = kube(['get','namespace',name,'-o','json'])['metadata']
    assert meta.get('labels',{}).get('app.kubernetes.io/part-of')=='edgeai'
    assert meta.get('labels',{}).get('app.kubernetes.io/managed-by')=='edgeai-bootstrap' and 'deletionTimestamp' not in meta
    if args.faults and name=='edgeai':
        cluster=meta.get('labels',{}).get('edgeai.io/test-cluster','')
        assert cluster.startswith('edgeai-ci-') and args.context=='kind-'+cluster,'Fault injection is restricted to an owned disposable kind cluster'

def request(path,method='GET',body=None,key=None,expected=200):
    headers={'Authorization':auth}
    if csrf:headers['X-CSRF-TOKEN']=csrf
    if key:headers['Idempotency-Key']=key
    if body is not None:headers['Content-Type']='application/json'
    proxy=os.environ.get('EDGEAI_SMOKE_PROXY_URL')
    url=(proxy.rstrip('/')+'/api/control-plane/' if proxy and path!='platform' else api+'/api/v1/')+path
    req=urllib.request.Request(url,headers=headers,method=method,data=None if body is None else json.dumps(body).encode())
    try:response=client.open(req,timeout=10)
    except urllib.error.HTTPError as error:response=error
    with response:
        payload=response.read(1048577)
        if response.status!=expected:raise AssertionError(f'{method} {path} returned HTTP {response.status}, expected {expected}')
        assert len(payload)<=1048576
        return json.loads(payload)

csrf=request('csrf')['token']
assert 'results' in request('platform')['capabilities'],'Deploy the Result API before the full runtime smoke'
release=json.loads(Path('deploy/kubernetes/overlays/dev/release.json').read_text())
digest=os.environ.get('EDGEAI_RUNNER_DIGEST',release['runnerDigest']);assert re.fullmatch(r'sha256:[0-9a-f]{64}',digest)
base=json.loads(Path('contracts/profiles/service-execution.example.json').read_text())
base.update(image='ghcr.io/dsa04156/edgeai-runner@'+digest,platform={'os':'linux','architectures':['amd64']},timeoutSeconds=120)
# Give the independent observer time to record actual Pod/Node identity before normal cleanup.
base['command']=['python3','-c',"import time,runpy; time.sleep(2); runpy.run_path('/opt/edgeai/examples/linear.py',run_name='__main__')"]
prefix='runtime-smoke-'+uuid.uuid4().hex
report={'sourceMode':'SYNTHETIC','runnerImage':base['image'],'runs':[]}
artifacts=[]

def profile(suffix,spec):
    return request('profiles/SERVICE','POST',{'key':prefix+'-'+suffix,'version':'1.0.0','spec':spec},expected=201)['id']

def workflow(suffix,root,child=None):
    w=request('workflows','POST',{'key':prefix+'-'+suffix,'displayName':'Synthetic runtime acceptance'},expected=201)
    tasks=[{'key':'root','serviceProfileVersionId':root,'parameters':{'features':[2,1],'weights':[0.5,-1],'bias':0.25}}]
    edges=[]
    if child:
        # Child input must come from the parent's stored artifact, not these intentionally wrong features.
        tasks.append({'key':'child','serviceProfileVersionId':child,'parameters':{'features':[99,99],'weights':[2,3],'bias':1}})
        edges=[{'fromTask':'root','toTask':'child','fromPort':'output','toPort':'input','mode':'BATCH'}]
    return request('workflows/'+w['id']+'/versions','POST',{'version':'1.0.0','tasks':tasks,'dependencies':edges},expected=201)['id']

def resources(run):
    return kube(['-n',namespace,'get','jobs,pods,secrets','-l','edgeai.io/run-id='+run,'-o','json'])['items']

def wait(check,seconds,description):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        value=check()
        if value:return value
        time.sleep(0.4)
    raise AssertionError(description)

def create(version,policy):
    key=str(uuid.uuid4());body={'workflowVersionId':version,'parameters':{'serial':9007199254740993},'execution':policy}
    run=request('workflow-runs','POST',body,key=key,expected=201)
    assert run['state']=='RUNNING','Runtime execution must be enabled'
    assert request('workflow-runs','POST',body,key=key)['id']==run['id']
    return run

def cleaned(run):
    wait(lambda:not resources(run),60,'Owned Job/Pod/Secret resources did not terminate for Run '+run)

def cancel(run):
    request('workflow-runs/'+run+'/cancel','POST',{})
    wait(lambda:request('workflow-runs/'+run)['run']['state']=='CANCELLED',60,'Cancellation did not reach confirmed physical termination')
    cleaned(run)

def producer_credentials(pod):
    # Private subprocess pipe only; never print or persist these credentials.
    code="import sys; sys.path.insert(0,'/opt/edgeai'); from edgeai_runner.main import Runner; import json; r=Runner(); print(json.dumps({'attemptId':r.attempt,'podUid':r.pod,'epoch':r.epoch,'claim':r.token,'podToken':r.pod_token_file.read_text().strip()}))"
    result=subprocess.run(kubectl+['-n',namespace,'exec',pod['metadata']['name'],'--','python3','-c',code],capture_output=True,text=True,timeout=20)
    if result.returncode or len(result.stdout)>32768:raise AssertionError('Producer snapshot failed; private output suppressed')
    try:return json.loads(result.stdout)
    except Exception:raise AssertionError('Producer snapshot invalid; private output suppressed') from None

def fenced_request(credentials,operation,payload):
    # A fresh forward remains valid after the explicit API restart in this disposable cluster.
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    forward=subprocess.Popen(kubectl+['-n','edgeai','port-forward','--address','127.0.0.1','svc/edgeai-api',f'{port}:18080'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        url=f'http://127.0.0.1:{port}'
        def ready():
            assert forward.poll() is None,'Internal test forward terminated'
            try:
                with urllib.request.urlopen(url+'/actuator/health/readiness',timeout=2) as response:return response.status==200
            except OSError:return False
        wait(ready,15,'Internal test forward did not become ready')
        req=urllib.request.Request(url+'/internal/v1/attempts/'+credentials['attemptId']+'/'+operation,method='POST',data=json.dumps(payload).encode(),
            headers={'Authorization':'Bearer '+credentials['claim'],'X-EdgeAI-Pod-Token':credentials['podToken'],'Content-Type':'application/json'})
        try:response=urllib.request.urlopen(req,timeout=15)
        except urllib.error.HTTPError as error:response=error
        with response:status=response.status
        assert status in (401,409),'Stale or mismatched producer was not fenced'
        return status
    finally:
        forward.terminate()
        try:forward.wait(timeout=5)
        except subprocess.TimeoutExpired:forward.kill();forward.wait()

def execute(version,policy,restart=False):
    global csrf
    run=create(version,policy);run_id=run['id'];seen={};restart_proof={}
    print('Running real BATCH '+policy['mode']+' Run '+run_id,flush=True)
    try:
        def completed():
            global csrf
            for resource in resources(run_id):
                if resource['kind']=='Pod' and resource['spec'].get('nodeName'):
                    entry=seen.setdefault(resource['metadata']['uid'],{'nodeName':resource['spec']['nodeName'],'attemptId':resource['metadata']['labels']['edgeai.io/attempt-id'],'runnerEvents':[]})
                    logs=subprocess.run(kubectl+['-n',namespace,'logs',resource['metadata']['name'],'--tail=10'],capture_output=True,text=True,timeout=20)
                    if logs.returncode==0:
                        entry['runnerEvents']=list(dict.fromkeys(entry['runnerEvents']+[line for line in logs.stdout.splitlines() if re.fullmatch(r'RUNNER_(WORKLOAD_START|RESULT_COMMITTED|FAILED [A-Z_]+)',line)]))
            detail=request('workflow-runs/'+run_id)
            if restart and not restart_proof and seen:
                root=next(t for t in detail['tasks'] if t['key']=='root')
                attempt=request('tasks/'+root['id'])['attempts'][0]
                if attempt['state']=='RUNNING':
                    before=[r for r in resources(run_id) if r['kind']=='Job']
                    assert len(before)==1
                    job_uid=before[0]['metadata']['uid']
                    for command in [['rollout','restart','deployment/edgeai-api'],['rollout','status','deployment/edgeai-api','--timeout=180s']]:
                        result=subprocess.run(kubectl+['-n','edgeai']+command,capture_output=True,text=True,timeout=190)
                        assert result.returncode==0,'Dedicated API restart failed; details suppressed'
                    after=[r for r in resources(run_id) if r['kind']=='Job']
                    assert len(after)==1 and after[0]['metadata']['uid']==job_uid,'Restart replaced/duplicated the existing Job'
                    assert len(request('tasks/'+root['id'])['attempts'])==1
                    csrf=request('csrf')['token']
                    restart_proof.update(jobUid=job_uid,attemptId=attempt['id'],preserved=True)
                    print('PASS: API replacement retained the same active Job and Attempt',flush=True)
            if detail['run']['state'] in ('FAILED','CANCELLED'):
                print('Runner diagnostics: '+json.dumps(seen),flush=True)
                raise AssertionError('Actual runtime failed for Run '+run_id)
            return detail if detail['run']['state']=='SUCCEEDED' else None
        detail=wait(completed,300,'Actual Runner BATCH did not finish for Run '+run_id)
        assert len(detail['tasks'])==2 and all(t['state']=='SUCCEEDED' for t in detail['tasks'])
        results=[]
        for task in detail['tasks']:
            attempt=request('tasks/'+task['id'])['attempts'];assert len(attempt)==1 and attempt[0]['state']=='SUCCEEDED'
            items=request('tasks/'+task['id']+'/results')['items'];assert len(items)==1
            result=items[0];assert result['attemptId']==attempt[0]['id'] and result['producerPodUid'] in seen
            assert seen[result['producerPodUid']]['attemptId']==result['attemptId']
            if policy['mode']=='NODE':assert seen[result['producerPodUid']]['nodeName']==node['metadata']['name']
            assert len(result['artifacts'])==1
            artifacts.append({'artifact':result['artifacts'][0],'expected':{'sourceMode':'SYNTHETIC','features':[2,1],'score':0.25 if task['key']=='root' else 8.0,'prediction':1}})
            results.append(result)
        cleaned(run_id)
        if restart:assert restart_proof.get('preserved')
        report['runs'].append({'id':run_id,'policy':policy,'results':results,'observedPods':seen,'restart':restart_proof})
        print('PASS: real '+policy['mode']+' scheduler → Runner → S3 → sealed Result → downstream Runner, with resource cleanup',flush=True)
    finally:
        state=request('workflow-runs/'+run_id)['run']['state']
        if state not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

root=profile('root',base)
child_spec=copy.deepcopy(base);child_spec['inputs']={'input':{'required':True,'mediaType':'application/json','maxBytes':1048576}}
child=profile('child',child_spec);version=workflow('batch',root,child)
execute(version,{'mode':'AUTO'})
node=next(n for n in kube(['get','nodes','-o','json'])['items'] if n['metadata']['labels'].get('kubernetes.io/arch')=='amd64'
          and not n['spec'].get('unschedulable') and not any(t.get('effect') in ('NoSchedule','NoExecute') for t in n['spec'].get('taints',[]))
          and any(c['type']=='Ready' and c['status']=='True' for c in n['status']['conditions']))
request('nodes/'+node['metadata']['uid'])
execute(version,{'mode':'NODE','nodeId':node['metadata']['uid']})

if args.faults:
    restart_spec=copy.deepcopy(base)
    restart_spec['timeoutSeconds']=240
    restart_spec['command']=['python3','-c',"import time,runpy; time.sleep(90); runpy.run_path('/opt/edgeai/examples/linear.py',run_name='__main__')"]
    restart_version=workflow('restart',profile('restart',restart_spec),child)
    execute(restart_version,{'mode':'AUTO'},restart=True)
    probe_spec=copy.deepcopy(base);probe_spec['command']=['python3','-c','import time; time.sleep(110)']
    probe_run=create(workflow('artifact-faults',profile('artifact-faults',probe_spec)),{'mode':'AUTO'});probe_id=probe_run['id']
    try:
        def claimed_pod():
            task=request('workflow-runs/'+probe_id)['tasks'][0]
            if request('tasks/'+task['id'])['attempts'][0]['state']!='RUNNING':return None
            return next((r for r in resources(probe_id) if r['kind']=='Pod' and r['status'].get('phase')=='Running'),None)
        pod=wait(claimed_pod,90,'Fault probe Runner was not claimed')
        probe=subprocess.run(kubectl+['-n',namespace,'exec','-i',pod['metadata']['name'],'--','python3','-'],
            input=Path('deploy/kind/probe-runner.py').read_text(),text=True,capture_output=True,timeout=50)
        if probe.returncode and re.fullmatch(r'\{"failedPhase": "[a-z-]+"\}\s*',probe.stdout):print(probe.stdout.strip(),flush=True)
        assert probe.returncode==0,'Real Runner protocol fault probe failed; details suppressed'
        proof=json.loads(probe.stdout)
        detail=wait(lambda:(d if (d:=request('workflow-runs/'+probe_id))['run']['state']=='SUCCEEDED' else None),30,'Concurrent commit did not finish Run')
        task=detail['tasks'][0];items=request('tasks/'+task['id']+'/results')['items']
        assert len(items)==1 and items[0]['id']==proof['resultId']
        assert items[0]['producerPodUid']==pod['metadata']['uid'] and len(request('tasks/'+task['id'])['attempts'])==1
        artifacts.append({'artifact':items[0]['artifacts'][0],'expected':{'sourceMode':'SYNTHETIC','features':[2,1],'score':0.25,'prediction':1}})
        cleaned(probe_id)
        report['runs'].append({'id':probe_id,'case':'artifact-and-concurrent-commit-faults','proof':proof,'results':items})
        print('PASS: real S3 missing version/wrong size/wrong hash rejected; concurrent commits sealed exactly one Result',flush=True)
    finally:
        if request('workflow-runs/'+probe_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(probe_id)

for case in ['cancel-running','unsatisfiable-affinity','insufficient-cpu']:
    spec=copy.deepcopy(base);spec['command']=['python3','-c','import time; time.sleep(110)']
    if case=='unsatisfiable-affinity':spec['nodeSelector']={'edgeai.io/test-constraint':uuid.uuid4().hex}
    if case=='insufficient-cpu':
        # Valid resource contract, deliberately greater than any available node's capacity.
        spec['resources']['requests']['cpu']='1000000'
        spec['resources']['limits']['cpu']='1000000'
    run=create(workflow(case,profile(case,spec)),{'mode':'AUTO'});run_id=run['id']
    captured=None;fence_proof={}
    try:
        def observed():
            pods=[r for r in resources(run_id) if r['kind']=='Pod']
            if case=='cancel-running':
                task=request('workflow-runs/'+run_id)['tasks'][0]
                return any(p['status'].get('phase')=='Running' for p in pods) and request('tasks/'+task['id'])['attempts'][0]['state']=='RUNNING'
            return any(not p['spec'].get('nodeName') and any(c.get('reason')=='Unschedulable' and
                (case!='insufficient-cpu' or 'Insufficient cpu' in c.get('message','')) for c in p['status'].get('conditions',[])) for p in pods)
        wait(observed,60,'Did not observe real '+case)
        if args.faults and case=='cancel-running':
            pod=next(r for r in resources(run_id) if r['kind']=='Pod' and r['status'].get('phase')=='Running')
            captured=producer_credentials(pod)
            other=create(workflow('other-producer',profile('other-producer',spec)),{'mode':'AUTO'})['id']
            try:
                def other_claimed():
                    task=request('workflow-runs/'+other)['tasks'][0]
                    if request('tasks/'+task['id'])['attempts'][0]['state']!='RUNNING':return None
                    return next((r for r in resources(other) if r['kind']=='Pod' and r['status'].get('phase')=='Running'),None)
                other_credentials=producer_credentials(wait(other_claimed,60,'Other real producer not running'))
                mixed={**captured,'podToken':other_credentials['podToken']}
                fence_proof['differentPodClaimStatus']=fenced_request(mixed,'claim',{'epoch':captured['epoch'],'podUid':other_credentials['podUid']})
                assert request('workflow-runs/'+run_id)['run']['state']=='RUNNING'
            finally:cancel(other)
    finally:cancel(run_id)
    if captured:
        fence_proof['lateCommitStatus']=fenced_request(captured,'commit',{'epoch':captured['epoch'],'podUid':captured['podUid'],'outputs':[]})
        del captured
    for task in request('workflow-runs/'+run_id)['tasks']:assert request('tasks/'+task['id']+'/results')['items']==[]
    report['runs'].append({'id':run_id,'case':case,'state':'CANCELLED','resourcesRemaining':0,'fencing':fence_proof})
    print('PASS: real '+case+'; producer stopped, empty results, no remaining resources',flush=True)

for case,command in [('missing-output','pass'),('workload-failure','raise SystemExit(7)')]:
    spec=copy.deepcopy(base);spec['command']=['python3','-c',command]
    run=create(workflow(case,profile(case,spec),child),{'mode':'AUTO'});run_id=run['id']
    try:
        def failed():
            detail=request('workflow-runs/'+run_id)
            assert detail['run']['state'] not in ('SUCCEEDED','CANCELLED'),'Invalid workload was reported as successful/cancelled'
            return detail if detail['run']['state']=='FAILED' else None
        detail=wait(failed,90,'Invalid workload did not fail')
        tasks={t['key']:t for t in detail['tasks']}
        assert tasks['root']['state']=='FAILED' and tasks['child']['state']=='SKIPPED'
        for task in detail['tasks']:assert request('tasks/'+task['id']+'/results')['items']==[]
        assert request('tasks/'+tasks['child']['id'])['attempts']==[],'Failed parent released a downstream Attempt'
        cleaned(run_id)
        report['runs'].append({'id':run_id,'case':case,'state':'FAILED','downstream':'SKIPPED','resourcesRemaining':0})
        print('PASS: real '+case+'; no Result, downstream skipped, no remaining resources',flush=True)
    finally:
        if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

verification=subprocess.run(['node','scripts/verify-runtime-artifacts.mjs'],input=json.dumps(artifacts),text=True,capture_output=True,timeout=90)
if verification.returncode:raise AssertionError('Actual S3 output verification failed; response suppressed')
print(verification.stdout.strip(),flush=True)
location=Path(args.report);location.parent.mkdir(parents=True,exist_ok=True);location.write_text(json.dumps(report,indent=2)+'\n')
print('PASS: runtime acceptance report saved; SYNTHETIC workload, not hardware/model acceptance',flush=True)
