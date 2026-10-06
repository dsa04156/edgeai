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
parser.add_argument('--remote', action='store_true', help='Verify the independent TLS Remote provider and real Kubernetes transfers; requires --faults')
args = parser.parse_args()
assert not args.remote or args.faults,'Remote acceptance requires the owned disposable-cluster fault guard'
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

def request(path,method='GET',body=None,key=None,expected=200,recovery_seconds=0):
    headers={'Authorization':auth}
    if csrf:headers['X-CSRF-TOKEN']=csrf
    if key:headers['Idempotency-Key']=key
    if body is not None:headers['Content-Type']='application/json'
    proxy=os.environ.get('EDGEAI_SMOKE_PROXY_URL')
    url=(proxy.rstrip('/')+'/api/control-plane/' if proxy and path!='platform' else api+'/api/v1/')+path
    req=urllib.request.Request(url,headers=headers,method=method,data=None if body is None else json.dumps(body).encode())
    deadline=time.monotonic()+recovery_seconds
    while True:
        try:response=client.open(req,timeout=10)
        except urllib.error.HTTPError as error:response=error
        except OSError:
            if method=='GET' and time.monotonic()<deadline:
                time.sleep(.2);continue
            raise AssertionError('HTTP connection unavailable; response suppressed') from None
        with response:
            payload=response.read(1048577);status=response.status
        assert len(payload)<=1048576
        if method=='GET' and status in (502,503,504) and time.monotonic()<deadline:
            time.sleep(.2);continue
        if status!=expected:raise AssertionError(f'{method} {path} returned HTTP {status}, expected {expected}')
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

def workflow(suffix,root,child=None,root_delay=0,child_delay=0):
    w=request('workflows','POST',{'key':prefix+'-'+suffix,'displayName':'Synthetic runtime acceptance'},expected=201)
    tasks=[{'key':'root','serviceProfileVersionId':root,'parameters':{'features':[2,1],'weights':[0.5,-1],'bias':0.25}}]
    if root_delay:tasks[0]['parameters']['simulationDelayMillis']=root_delay
    edges=[]
    if child:
        # Child input must come from the parent's stored artifact, not these intentionally wrong features.
        tasks.append({'key':'child','serviceProfileVersionId':child,'parameters':{'features':[99,99],'weights':[2,3],'bias':1}})
        if child_delay:tasks[1]['parameters']['simulationDelayMillis']=child_delay
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

def create(version,policy,retry=None,offload=None):
    key=str(uuid.uuid4());body={'workflowVersionId':version,'parameters':{'serial':9007199254740993},'execution':policy}
    if retry is not None:body['retry']=retry
    if offload is not None:body['offload']=offload
    run=request('workflow-runs','POST',body,key=key,expected=201)
    assert run['state']=='RUNNING','Runtime execution must be enabled'
    assert request('workflow-runs','POST',body,key=key)['id']==run['id']
    return run

def cleaned(run):
    wait(lambda:not resources(run),60,'Owned Job/Pod/Secret resources did not terminate for Run '+run)

def cancel(run):
    global csrf
    csrf=request('csrf',recovery_seconds=30)['token']
    request('workflow-runs/'+run+'/cancel','POST',{})
    wait(lambda:request('workflow-runs/'+run)['run']['state']=='CANCELLED',60,'Cancellation did not reach confirmed physical termination')
    cleaned(run)

def restart_api():
    global csrf
    old={p['metadata']['uid'] for p in kube(['-n','edgeai','get','pods','-l','app=edgeai-api','-o','json'])['items']}
    for command in [['rollout','restart','deployment/edgeai-api'],['rollout','status','deployment/edgeai-api','--timeout=180s']]:
        result=subprocess.run(kubectl+['-n','edgeai']+command,capture_output=True,text=True,timeout=190)
        assert result.returncode==0,'Dedicated API restart failed; details suppressed'
    # Rollout completion can precede deletion/connection drain of the old session owner.
    wait(lambda:old.isdisjoint({p['metadata']['uid'] for p in kube(['-n','edgeai','get','pods','-l','app=edgeai-api','-o','json'])['items']}),90,'Previous API Pod did not drain')
    csrf=request('csrf',recovery_seconds=30)['token']

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
                    restart_api()
                    after=[r for r in resources(run_id) if r['kind']=='Job']
                    assert len(after)==1 and after[0]['metadata']['uid']==job_uid,'Restart replaced/duplicated the existing Job'
                    assert len(request('tasks/'+root['id'],recovery_seconds=30)['attempts'])==1
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

if args.faults:
    # Real workload process failure -> persisted retry -> API replacement -> new producer -> one sealed Result.
    retry_spec=copy.deepcopy(base)
    retry_spec['command']=['python3','-c',"import time,runpy; time.sleep(30); runpy.run_path('/opt/edgeai/examples/linear.py',run_name='__main__')"]
    retry_version=workflow('retry-recovery',profile('retry-recovery',retry_spec),child)
    retry_policy={'maxAttempts':2,'backoffSeconds':30,'maxElapsedSeconds':600,'retryOn':['WORKLOAD_FAILED','JOB_FAILED']}
    run_id=create(retry_version,{'mode':'AUTO'},retry_policy)['id'];seen={}
    try:
        task=next(t for t in request('workflow-runs/'+run_id)['tasks'] if t['key']=='root');task_id=task['id']
        first=request('tasks/'+task_id)['attempts'][0]
        def first_claimed():
            if request('tasks/'+task_id)['attempts'][0]['state']!='RUNNING':return None
            return next((p for p in resources(run_id) if p['kind']=='Pod' and p['status'].get('phase')=='Running'),None)
        old_pod=wait(first_claimed,90,'Retry source did not claim');captured=producer_credentials(old_pod)
        # Kill only the owned fixture's workload child. Runner stays alive to report the real nonzero exit.
        fault_code="""import os,signal,time
from pathlib import Path
for _ in range(50):
    matches=[]
    for path in Path('/proc').iterdir():
        if not path.name.isdigit() or int(path.name)==os.getpid():continue
        try:cmd=(path/'cmdline').read_bytes()
        except OSError:continue
        if b"runpy.run_path('/opt/edgeai/examples/linear.py'" in cmd:matches.append(int(path.name))
    if matches:break
    time.sleep(.1)
assert len(matches)==1
os.kill(matches[0],signal.SIGKILL)
"""
        killed=subprocess.run(kubectl+['-n',namespace,'exec','-i',old_pod['metadata']['name'],'--','python3','-'],input=fault_code,capture_output=True,text=True,timeout=20)
        assert killed.returncode==0,'Owned retry workload fault failed; response suppressed'
        wait(lambda:request('tasks/'+task_id)['task']['state']=='RETRY_WAIT',30,'Real workload failure did not schedule retry')
        pending=request('workflow-runs/'+run_id)
        assert next(t for t in pending['tasks'] if t['key']=='child')['state']=='WAITING'
        assert request('tasks/'+task_id+'/results')['items']==[]
        restart_api()
        def retried():
            for resource in resources(run_id):
                if resource['kind']=='Pod' and resource['spec'].get('nodeName'):
                    seen[resource['metadata']['uid']]={'attemptId':resource['metadata']['labels']['edgeai.io/attempt-id'],'nodeName':resource['spec']['nodeName']}
            detail=request('workflow-runs/'+run_id)
            assert detail['run']['state'] not in ('FAILED','CANCELLED'),'Retried real execution failed'
            return detail if detail['run']['state']=='SUCCEEDED' else None
        detail=wait(retried,240,'Persisted retry did not recover after API restart')
        history=request('tasks/'+task_id)['attempts']
        assert len(history)==2 and history[1]['id']==first['id'] and history[1]['state']=='FAILED'
        assert history[0]['state']=='SUCCEEDED' and history[0]['number']==2 and history[0]['epoch']==2
        results=[]
        for task in detail['tasks']:
            assert task['state']=='SUCCEEDED'
            items=request('tasks/'+task['id']+'/results')['items'];assert len(items)==1
            result=items[0];assert result['producerPodUid'] in seen and result['producerPodUid']!=old_pod['metadata']['uid']
            assert seen[result['producerPodUid']]['attemptId']==result['attemptId']
            if task['key']=='root':assert task['id']==task_id and result['attemptId']==history[0]['id']
            else:assert len(request('tasks/'+task['id'])['attempts'])==1
            artifacts.append({'artifact':result['artifacts'][0],'expected':{'sourceMode':'SYNTHETIC','features':[2,1],'score':0.25 if task['key']=='root' else 8.0,'prediction':1}})
            results.append(result)
        cleaned(run_id)
        late=fenced_request(captured,'commit',{'epoch':captured['epoch'],'podUid':captured['podUid'],'outputs':[]});del captured
        report['runs'].append({'id':run_id,'case':'retry-restart-recovery','taskId':task_id,'attempts':history,'results':results,'observedPods':seen,'oldPodUid':old_pod['metadata']['uid'],'lateCommitStatus':late,'resourcesRemaining':0})
        print('PASS: real workload failure and API restart retained Task, created Attempt/epoch 2, fenced old producer, sealed one Result and released child',flush=True)
    finally:
        if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)
    for case in ['retry-exhaustion','cancel-retry-wait']:
        spec=copy.deepcopy(base);spec['command']=['python3','-c','raise SystemExit(7)']
        policy={'maxAttempts':2,'backoffSeconds':2 if case=='retry-exhaustion' else 30,'maxElapsedSeconds':600,'retryOn':['WORKLOAD_FAILED','JOB_FAILED']}
        run_id=create(workflow(case,profile(case,spec),child),{'mode':'AUTO'},policy)['id']
        try:
            def retry_terminal():
                detail=request('workflow-runs/'+run_id);root=next(t for t in detail['tasks'] if t['key']=='root')
                target='FAILED' if case=='retry-exhaustion' else 'RETRY_WAIT'
                return detail if root['state']==target else None
            detail=wait(retry_terminal,120,'Retry exhaustion/wait was not observed')
            root=next(t for t in detail['tasks'] if t['key']=='root')
            if case=='cancel-retry-wait':cancel(run_id)
            else:cleaned(run_id)
            detail=request('workflow-runs/'+run_id);history=request('tasks/'+root['id'])['attempts']
            assert len(history)==(2 if case=='retry-exhaustion' else 1) and all(a['state']=='FAILED' for a in history)
            assert detail['run']['state']==('FAILED' if case=='retry-exhaustion' else 'CANCELLED')
            for task in detail['tasks']:
                assert request('tasks/'+task['id']+'/results')['items']==[]
                if task['key']=='child':assert request('tasks/'+task['id'])['attempts']==[]
            report['runs'].append({'id':run_id,'case':case,'attempts':history,'state':detail['run']['state'],'resourcesRemaining':0})
            print('PASS: real '+case+'; bounded attempts, no child execution or results, resources cleaned',flush=True)
        finally:
            if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

if args.faults:
    # Cordon/restart is restricted by the owned disposable-cluster check above.
    candidates=[n for n in kube(['get','nodes','-o','json'])['items']
        if n['metadata']['labels'].get('kubernetes.io/arch')=='amd64' and not n['spec'].get('unschedulable')
        and not any(t.get('effect') in ('NoSchedule','NoExecute') for t in n['spec'].get('taints',[]))
        and any(c['type']=='Ready' and c['status']=='True' for c in n['status']['conditions'])]
    assert len(candidates)>=2,'Running offload acceptance requires two schedulable nodes'
    source_node,target_node=candidates[:2]
    for n in [source_node,target_node]:
        assert n['metadata']['name'].startswith(cluster+'-')
        request('nodes/'+n['metadata']['uid'])
    def scheduling(enabled):
        action='uncordon' if enabled else 'cordon'
        result=subprocess.run(kubectl+[action,target_node['metadata']['name']],capture_output=True,text=True,timeout=20)
        assert result.returncode==0,'Owned target scheduling change failed; output suppressed'

    offload_spec=copy.deepcopy(base);offload_spec['recovery']={'mode':'RESTART'}
    offload_spec['command']=['python3','-c',"""import datetime,json,os,pathlib,time,runpy
path=pathlib.Path(os.environ['EDGEAI_TELEMETRY_FILE'])
for sequence in range(1,91):
    begin=time.perf_counter_ns();sum(i*i for i in range(5000));latency=(time.perf_counter_ns()-begin)//1000
    temporary=path.with_suffix('.tmp')
    temporary.write_text(json.dumps({'sequence':sequence,'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'latencyMicros':latency}))
    temporary.replace(path);time.sleep(.5)
runpy.run_path('/opt/edgeai/examples/linear.py',run_name='__main__')
"""]
    offload_child=copy.deepcopy(offload_spec);offload_child['inputs']=child_spec['inputs']
    offload_version=workflow('offload-batch',profile('offload-root',offload_spec),profile('offload-child',offload_child))
    run_id=create(offload_version,{'mode':'NODE','nodeId':source_node['metadata']['uid']})['id']
    proofs=[]
    try:
        # Root proves pending-operation recovery; child proves reuse of sealed BATCH inputs.
        for task_key in ['root','child']:
            task=next(t for t in request('workflow-runs/'+run_id)['tasks'] if t['key']==task_key);task_id=task['id']
            def running_source():
                detail=request('tasks/'+task_id)
                assert detail['task']['state'] not in ('FAILED','CANCELLED','SKIPPED')
                if not detail['attempts'] or detail['attempts'][0]['state']!='RUNNING':return None
                attempt=detail['attempts'][0]
                return next((p for p in resources(run_id) if p['kind']=='Pod' and p['metadata']['labels']['edgeai.io/attempt-id']==attempt['id'] and p['status'].get('phase')=='Running'),None)
            old_pod=wait(running_source,150,'Offload source did not claim')
            assert old_pod['spec']['nodeName']==source_node['metadata']['name']
            captured=producer_credentials(old_pod);first=request('tasks/'+task_id)['attempts'][0]
            def measured(attempt):
                value=request('tasks/'+task_id).get('telemetry')
                return value if value and value['attemptId']==attempt and value['cpuUsageMicros'] is not None and value['latencyMicros'] is not None else None
            old_sample=wait(lambda:measured(first['id']),20,'Source Runner did not report actual measurements')
            assert old_sample['resourceSource']=='CGROUP_V2' and old_sample['cpuLimitMillicores']>0 and old_sample['memoryBytes']>0
            if task_key=='root':scheduling(False)
            body={'sourceAttemptId':first['id'],'targetNodeId':target_node['metadata']['uid'],'drainTimeoutSeconds':60,'startTimeoutSeconds':180}
            key=str(uuid.uuid4());operation=request('tasks/'+task_id+'/offload','POST',body,key=key,expected=202)
            assert operation['state']=='DRAINING'
            assert request('tasks/'+task_id+'/offload','POST',body,key=key)['id']==operation['id']
            assert request('tasks/'+task_id)['attempts'][-1]['state']=='OFFLOADED'
            def phase(allowed):
                value=request('operations/'+operation['id'])
                assert value['state'] not in ('FAILED','CANCELLED','CANCELLING'),'Running transfer failed'
                return value if value['state'] in allowed else None
            if task_key=='root':
                started=wait(lambda:phase({'STARTING'}),90,'Offload did not drain the source')
                assert not any(p['metadata']['uid']==old_pod['metadata']['uid'] for p in resources(run_id))
                restart_api()
                recovered=phase({'STARTING'})
                assert recovered and recovered['targetAttemptId']==started['targetAttemptId'],'Restart lost/duplicated pending offload'
                assert len(request('tasks/'+task_id)['attempts'])==2
                scheduling(True)
            transferred=wait(lambda:phase({'SUCCEEDED'}),120,'Target producer did not claim')
            current=request('tasks/'+task_id);history=current['attempts']
            assert current['task']['id']==task_id and current['task']['state']=='RUNNING'
            assert len(history)==2 and history[1]['id']==first['id'] and history[1]['state']=='OFFLOADED'
            assert history[0]['id']==transferred['targetAttemptId'] and history[0]['number']==2 and history[0]['epoch']==2
            assert history[0]['cause']=='OFFLOAD' and history[0]['mode']=='NODE' and history[0]['nodeId']==target_node['metadata']['uid']
            live=resources(run_id)
            assert not any(p['metadata']['uid']==old_pod['metadata']['uid'] for p in live),'Source still exists after target claim'
            target_pod=next(p for p in live if p['kind']=='Pod' and p['metadata']['labels']['edgeai.io/attempt-id']==history[0]['id'])
            assert target_pod['spec']['nodeName']==target_node['metadata']['name'] and target_pod['metadata']['uid']!=old_pod['metadata']['uid']
            assert request('tasks/'+task_id+'/results')['items']==[],'Transfer success incorrectly completed the Task'
            late=fenced_request(captured,'commit',{'epoch':captured['epoch'],'podUid':captured['podUid'],'outputs':[]})
            reported={k:old_sample[k] for k in ['sequence','observedAt','intervalMillis','cpuUsageMicros','cpuLimitMillicores','memoryBytes','memoryLimitBytes','latencyMicros','latencyObservedAt']}
            late_telemetry=fenced_request(captured,'telemetry',{'epoch':captured['epoch'],'podUid':captured['podUid'],**reported});del captured
            target_sample=wait(lambda:measured(history[0]['id']),20,'Target Runner did not report its own measurements')
            assert target_sample['attemptId']!=old_sample['attemptId'] and target_sample['resourceSource']=='CGROUP_V2' and target_sample['latencySource']=='WORKLOAD'
            proofs.append({'taskId':task_id,'taskKey':task_key,'operationId':operation['id'],'attempts':history,
                'sourcePodUid':old_pod['metadata']['uid'],'targetPodUid':target_pod['metadata']['uid'],'targetNode':target_pod['spec']['nodeName'],
                'lateCommitStatus':late,'lateTelemetryStatus':late_telemetry,'sourceMeasurement':old_sample,'targetMeasurement':target_sample,'pendingOperationRecovered':task_key=='root'})
        detail=wait(lambda:(d if (d:=request('workflow-runs/'+run_id))['run']['state']=='SUCCEEDED' else None),150,'Transferred BATCH did not finish')
        results=[]
        for task in detail['tasks']:
            proof=next(p for p in proofs if p['taskId']==task['id']);items=request('tasks/'+task['id']+'/results')['items']
            assert task['state']=='SUCCEEDED' and len(items)==1 and len(request('tasks/'+task['id'])['attempts'])==2
            assert items[0]['attemptId']==proof['attempts'][0]['id'] and items[0]['producerPodUid']==proof['targetPodUid']
            artifacts.append({'artifact':items[0]['artifacts'][0],'expected':{'sourceMode':'SYNTHETIC','features':[2,1],'score':0.25 if task['key']=='root' else 8.0,'prediction':1}})
            results.extend(items)
        cleaned(run_id)
        report['runs'].append({'id':run_id,'case':'running-offload-restart-and-batch-input','transfers':proofs,'results':results,'resourcesRemaining':0})
        print('PASS: running NODE transfers retain Task, recover pending Operation, fence old producer, preserve BATCH inputs, seal one Result each and clean resources',flush=True)
    finally:
        scheduling(True)
        if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

    for case in ['cancel-offload-starting','offload-start-timeout']:
        run_id=create(offload_version,{'mode':'NODE','nodeId':source_node['metadata']['uid']})['id']
        try:
            task_id=next(t for t in request('workflow-runs/'+run_id)['tasks'] if t['key']=='root')['id']
            first=wait(lambda:(a if (a:=request('tasks/'+task_id)['attempts'][0])['state']=='RUNNING' else None),90,'Transfer fault source not claimed')
            scheduling(False)
            body={'sourceAttemptId':first['id'],'targetNodeId':target_node['metadata']['uid'],'drainTimeoutSeconds':60,'startTimeoutSeconds':120 if case=='cancel-offload-starting' else 3}
            operation=request('tasks/'+task_id+'/offload','POST',body,key=str(uuid.uuid4()),expected=202)
            if case=='cancel-offload-starting':
                wait(lambda:request('operations/'+operation['id'])['state']=='STARTING',90,'Transfer did not start')
                cancel(run_id);terminal='CANCELLED'
            else:terminal='FAILED'
            end=wait(lambda:(o if (o:=request('operations/'+operation['id']))['state']==terminal else None),90,'Transfer fault did not reach terminal state')
            if terminal=='FAILED':assert end['failureReason']=='TARGET_START_TIMEOUT'
            detail=request('workflow-runs/'+run_id);assert detail['run']['state']==terminal
            history=request('tasks/'+task_id)['attempts'];assert len(history)==2 and history[0]['state']==terminal and history[1]['state']=='OFFLOADED'
            for task in detail['tasks']:
                assert request('tasks/'+task['id']+'/results')['items']==[]
                if task['key']=='child':assert request('tasks/'+task['id'])['attempts']==[]
            cleaned(run_id)
            report['runs'].append({'id':run_id,'case':case,'operation':end,'attempts':history,'state':terminal,'resourcesRemaining':0})
            print('PASS: real '+case+'; no Result, no downstream execution and no remaining resources',flush=True)
        finally:
            scheduling(True)
            if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

if args.faults:
    # Thresholds deliberately exercise control flow with actual synthetic-workload measurements.
    # They are test configuration, not recommended production tuning or performance acceptance.
    automatic_version=workflow('automatic',profile('automatic',offload_spec))
    cpu_spec=copy.deepcopy(offload_spec)
    cpu_spec['command'][2]=cpu_spec['command'][2].replace('for sequence in range(1,91):','until=time.monotonic()+45;sequence=0\nwhile time.monotonic()<until:\n    sequence+=1').replace('range(5000)','range(500000)').replace('time.sleep(.5)','time.sleep(.1)')
    cpu_version=workflow('automatic-cpu',profile('automatic-cpu',cpu_spec))
    for metric in ['MEMORY','LATENCY','CPU']:
        policy={'cpuPercent':1 if metric=='CPU' else None,'memoryPercent':1 if metric=='MEMORY' else None,'latencyMicros':1 if metric=='LATENCY' else None,
            'consecutiveSamples':2,'maxSampleAgeSeconds':30,'maxGapSeconds':10,'minRunningSeconds':10,'cooldownSeconds':20,
            'maxTransfers':1,'drainTimeoutSeconds':60,'startTimeoutSeconds':180}
        run_id=create(cpu_version if metric=='CPU' else automatic_version,{'mode':'NODE','nodeId':source_node['metadata']['uid']},offload=policy)['id']
        try:
            task_id=request('workflow-runs/'+run_id)['tasks'][0]['id']
            first=wait(lambda:(a if (a:=request('tasks/'+task_id)['attempts'][0])['state']=='RUNNING' else None),90,'Automatic source not claimed')
            old_pod=next(p for p in resources(run_id) if p['kind']=='Pod' and p['metadata']['labels']['edgeai.io/attempt-id']==first['id'])
            captured=producer_credentials(old_pod)
            if metric=='MEMORY':scheduling(False)
            def decided():
                d=request('tasks/'+task_id)
                assert d['task']['state'] not in ('FAILED','CANCELLED','SUCCEEDED'),'Automatic decision missed its live workload'
                return d['offloads'][0] if d['offloads'] else None
            operation=wait(decided,35,'Fresh measured thresholds did not trigger automatic transfer')
            assert operation['trigger']==metric and operation['targetNodeId'] is None
            assert operation['excludedNodeNames']==[source_node['metadata']['name']]
            evidence=operation['decision'];assert evidence['policy']==policy and len(evidence['samples'])==2
            samples=evidence['samples'];assert samples[0]['sequence']==samples[1]['sequence']+1
            assert all(v['attemptId']==first['id'] and v['resourceSource']=='CGROUP_V2' for v in samples)
            if metric=='MEMORY':assert all(v['memoryBytes']*100>=v['memoryLimitBytes'] for v in samples)
            elif metric=='LATENCY':assert all(v['latencySource']=='WORKLOAD' and v['latencyMicros']>=1 for v in samples)
            else:assert all(v['cpuUsageMicros']*100>=v['intervalMillis']*v['cpuLimitMillicores'] for v in samples)
            if metric=='MEMORY':
                wait(lambda:request('operations/'+operation['id'])['state']=='STARTING',90,'Automatic drain did not complete')
                target_attempt=request('operations/'+operation['id'])['targetAttemptId'];restart_api()
                recovered=request('operations/'+operation['id'])
                assert recovered['targetAttemptId']==target_attempt and recovered['decision']==evidence,'Restart changed persisted automatic evidence/identity'
                scheduling(True)
            end=wait(lambda:(o if (o:=request('operations/'+operation['id']))['state']=='SUCCEEDED' else None),120,'Automatic target did not claim')
            detail=request('tasks/'+task_id);attempts=detail['attempts'];assert len(attempts)==2 and attempts[0]['epoch']==2 and attempts[0]['mode']=='AUTO'
            assert attempts[0]['nodeId'] is None and attempts[0]['excludedNodeNames']==[source_node['metadata']['name']]
            live=resources(run_id);assert not any(p['metadata']['uid']==old_pod['metadata']['uid'] for p in live)
            pod=next(p for p in live if p['kind']=='Pod' and p['metadata']['labels']['edgeai.io/attempt-id']==end['targetAttemptId'])
            job=next(p for p in live if p['kind']=='Job' and p['metadata']['labels']['edgeai.io/attempt-id']==end['targetAttemptId'])
            pod_spec=job['spec']['template']['spec'];assert 'nodeName' not in pod_spec
            terms=pod_spec['affinity']['nodeAffinity']['requiredDuringSchedulingIgnoredDuringExecution']['nodeSelectorTerms']
            assert len(terms)==1 and terms[0]['matchFields']==[{'key':'metadata.name','operator':'NotIn','values':[source_node['metadata']['name']]}]
            assert pod['spec']['nodeName']!=old_pod['spec']['nodeName'] and pod['metadata']['uid']!=old_pod['metadata']['uid']
            late=fenced_request(captured,'commit',{'epoch':captured['epoch'],'podUid':captured['podUid'],'outputs':[]});del captured
            wait(lambda:request('workflow-runs/'+run_id)['run']['state']=='SUCCEEDED',100,'Automatic target did not finish')
            final=request('tasks/'+task_id);items=request('tasks/'+task_id+'/results')['items']
            assert len(final['attempts'])==2 and len(final['offloads'])==1,'Automatic transfer budget failed'
            assert len(items)==1 and items[0]['attemptId']==end['targetAttemptId'] and items[0]['producerPodUid']==pod['metadata']['uid']
            assert final['offloads'][0]['decision']==evidence
            artifacts.append({'artifact':items[0]['artifacts'][0],'expected':{'sourceMode':'SYNTHETIC','features':[2,1],'score':0.25,'prediction':1}})
            cleaned(run_id)
            report['runs'].append({'id':run_id,'case':'automatic-'+metric.lower(),'operation':final['offloads'][0],'attempts':final['attempts'],
                'sourcePodUid':old_pod['metadata']['uid'],'targetPodUid':pod['metadata']['uid'],'targetNode':pod['spec']['nodeName'],
                'lateCommitStatus':late,'result':items[0],'pendingOperationRecovered':metric=='MEMORY','resourcesRemaining':0})
            print('PASS: automatic '+metric+' from actual measurements, scheduler exclusion, immutable decision, one Result and bounded transfer',flush=True)
        finally:
            scheduling(True)
            if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

if args.remote:
    # This provider is a real separate TLS Pod/PVC. Its SQLite is inspected through a private pipe,
    # projecting only owned synthetic identities/state/counts and fixed artifact metadata.
    provider_deployment=kube(['-n','edgeai','get','deployment','edgeai-remote','-o','json'])
    assert provider_deployment['metadata']['labels'].get('edgeai.io/test-cluster')==cluster
    def provider_pod():
        pods=kube(['-n','edgeai','get','pods','-l','app=edgeai-remote','-o','json'])['items']
        assert len(pods)==1 and pods[0]['metadata']['labels'].get('edgeai.io/test-cluster')==cluster
        return pods[0]
    def private_exec(pod,code,arguments=(),scope='edgeai'):
        result=subprocess.run(kubectl+['-n',scope,'exec','-i',pod,'--','python3','-',*arguments],input=code,text=True,capture_output=True,timeout=20)
        assert result.returncode==0 and len(result.stdout)<=262144,'Owned Remote proof failed; private output suppressed'
        try:return json.loads(result.stdout)
        except Exception:raise AssertionError('Owned Remote proof invalid; private output suppressed') from None
    def remote_rows(run_id):
        uuid.UUID(run_id)
        return private_exec(provider_pod()['metadata']['name'],"""import json,sqlite3,sys
db=sqlite3.connect('file:/data/provider/allocations.sqlite?mode=ro',uri=True)
proof=[]
for row in db.execute('SELECT id,identity,digest,state,executions,work,outputs FROM allocations'):
    identity=json.loads(row[1])
    if identity['runId']==sys.argv[1]:
        work=json.loads(row[5]) if row[5] else {}
        proof.append({'id':row[0],'identity':identity,'requestDigest':row[2],'state':row[3],'executions':row[4],
                      'inputs':work.get('inputs',[]),'outputs':json.loads(row[6])})
print(json.dumps(proof))
""",[run_id])
    def running_task(run_id,task_key):
        task=next(t for t in request('workflow-runs/'+run_id)['tasks'] if t['key']==task_key)
        detail=request('tasks/'+task['id'])
        assert detail['task']['state'] not in ('FAILED','CANCELLED','SKIPPED'),'Remote acceptance task failed before transfer'
        return detail if detail['attempts'] and detail['attempts'][0]['state']=='RUNNING' else None
    def succeeded(run_id):
        detail=request('workflow-runs/'+run_id)
        assert detail['run']['state'] not in ('FAILED','CANCELLED'),'Remote acceptance run failed'
        return detail if detail['run']['state']=='SUCCEEDED' else None
    def result_files(detail):
        results=[]
        for task in detail['tasks']:
            values=request('tasks/'+task['id']+'/results')['items'];assert len(values)==1 and len(values[0]['artifacts'])==1
            result=values[0];latest=request('tasks/'+task['id'])['attempts'][0]
            assert latest['state']=='SUCCEEDED' and result['attemptId']==latest['id']
            if latest['mode']=='REMOTE':assert result['producerPodUid'] is None and result['remoteAllocationId'] and result['remoteSourceMode']=='SYNTHETIC'
            else:assert result['producerPodUid'] and result['remoteAllocationId'] is None
            artifacts.append({'artifact':result['artifacts'][0],'expected':{'sourceMode':'SYNTHETIC','features':[2,1],'score':0.25 if task['key']=='root' else 8.0,'prediction':1}})
            results.append(result)
        return results
    remote_spec=copy.deepcopy(base);remote_spec.update(command=['python3','/opt/edgeai/examples/linear.py'],recovery={'mode':'RESTART'},timeoutSeconds=240)
    remote_child=copy.deepcopy(remote_spec);remote_child['inputs']=child_spec['inputs']
    root_profile=profile('remote-root',remote_spec);child_profile=profile('remote-child',remote_child)
    remote_policy={'mode':'REMOTE','providerKey':'reference'}
    remote_version=workflow('remote-restart',root_profile,child_profile,root_delay=60000)
    run=create(remote_version,remote_policy);run_id=run['id']
    try:
        active=wait(lambda:running_task(run_id,'root'),60,'Remote root did not start through the scheduled worker')
        before=remote_rows(run_id);assert len(before)==1 and before[0]['state']=='RUNNING' and before[0]['executions']==1
        provider_uid=provider_pod()['metadata']['uid'];assert not resources(run_id),'Remote-only work created Kubernetes runtime resources'
        restart_api()
        after=remote_rows(run_id)
        assert len(after)==1 and after[0]['id']==before[0]['id'] and after[0]['executions']==1
        assert provider_pod()['metadata']['uid']==provider_uid,'API restart replaced the independent provider'
        history=request('tasks/'+active['task']['id'])['attempts'];assert len(history)==1 and history[0]['id']==active['attempts'][0]['id']
        detail=wait(lambda:succeeded(run_id),180,'Remote BATCH did not recover after API replacement')
        results=result_files(detail);rows=remote_rows(run_id)
        assert len(rows)==2 and all(r['state']=='SUCCEEDED' and r['executions']==1 for r in rows)
        assert {r['id'] for r in rows}=={r['remoteAllocationId'] for r in results}
        for task in detail['tasks']:
            attempts=request('tasks/'+task['id'])['attempts'];assert len(attempts)==1 and attempts[0]['remoteTarget']==run['remoteTarget']
        cleaned(run_id)
        report['runs'].append({'id':run_id,'case':'remote-batch-api-restart','providerPodUid':provider_uid,'allocations':rows,'results':results,'attemptPreserved':True,'resourcesRemaining':0})
        print('PASS: real TLS Remote BATCH survives API Pod replacement with the same allocation/Attempt and exactly one computation',flush=True)
    finally:
        if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

    for direction in ('node-to-remote','remote-to-node'):
        run=create(workflow(direction,root_profile,child_profile,child_delay=60000),
                   {'mode':'NODE','nodeId':source_node['metadata']['uid']} if direction=='node-to-remote' else remote_policy)
        run_id=run['id'];captured=None
        try:
            current=wait(lambda:running_task(run_id,'child'),120,'BATCH child did not reach actual source execution')
            task_id=current['task']['id'];first=current['attempts'][0]
            parent=next(t for t in request('workflow-runs/'+run_id)['tasks'] if t['key']=='root')
            parent_artifact=request('tasks/'+parent['id']+'/results')['items'][0]['artifacts'][0]
            source_proof={}
            if direction=='node-to-remote':
                old_pod=next(p for p in resources(run_id) if p['kind']=='Pod' and p['metadata']['labels']['edgeai.io/attempt-id']==first['id'])
                captured=producer_credentials(old_pod);source_proof={'podUid':old_pod['metadata']['uid'],'nodeName':old_pod['spec']['nodeName']}
                target={'targetProviderKey':'reference'}
            else:
                old_allocation=next(r for r in remote_rows(run_id) if r['identity']['attemptId']==first['id'])
                assert old_allocation['state']=='RUNNING';source_proof={'remoteAllocationId':old_allocation['id']}
                target={'targetNodeId':target_node['metadata']['uid']};scheduling(False)
            body={'sourceAttemptId':first['id'],**target,'drainTimeoutSeconds':90,'startTimeoutSeconds':180};key=str(uuid.uuid4())
            operation=request('tasks/'+task_id+'/offload','POST',body,key=key,expected=202)
            assert request('tasks/'+task_id+'/offload','POST',body,key=key)['id']==operation['id']
            def transferred(states):
                value=request('operations/'+operation['id'])
                assert value['state'] not in ('FAILED','CANCELLED','CANCELLING'),'Real Remote transfer failed'
                return value if value['state'] in states else None
            if direction=='remote-to-node':
                pending=wait(lambda:transferred({'STARTING'}),100,'Remote source did not physically stop')
                assert next(r for r in remote_rows(run_id) if r['id']==old_allocation['id'])['state']=='CANCELLED'
                restart_api()
                recovered=transferred({'STARTING'});assert recovered and recovered['targetAttemptId']==pending['targetAttemptId']
                scheduling(True)
            operation=wait(lambda:transferred({'SUCCEEDED'}),120,'Real transfer target did not start')
            history=request('tasks/'+task_id)['attempts']
            assert len(history)==2 and history[1]['id']==first['id'] and history[1]['state']=='OFFLOADED'
            assert history[0]['id']==operation['targetAttemptId'] and history[0]['number']==2 and history[0]['epoch']==2
            target_proof={}
            if direction=='node-to-remote':
                assert not resources(run_id),'Old Kubernetes producer survived Remote target start'
                remote=next(r for r in remote_rows(run_id) if r['identity']['attemptId']==history[0]['id'])
                assert remote['executions']==1 and remote['inputs'][0]['sha256']==parent_artifact['sha256']
                target_proof={'remoteAllocationId':remote['id']}
                target_proof['lateCommitStatus']=fenced_request(captured,'commit',{'epoch':captured['epoch'],'podUid':captured['podUid'],'outputs':[]});captured=None
            else:
                pod=next(p for p in resources(run_id) if p['kind']=='Pod' and p['metadata']['labels']['edgeai.io/attempt-id']==history[0]['id'])
                assert pod['spec']['nodeName']==target_node['metadata']['name']
                inputs=private_exec(pod['metadata']['name'],"""import json,sys,urllib.parse
sys.path.insert(0,'/opt/edgeai')
from edgeai_runner.main import Runner
r=Runner();inputs=r.api('claim',r.identity)['inputs']
print(json.dumps({k:{'bytes':v['bytes'],'sha256':v['sha256'],'objectVersion':urllib.parse.parse_qs(urllib.parse.urlsplit(v['url']).query)['versionId'][0]} for k,v in inputs.items()}))
""",scope=namespace)
                assert inputs['input']['objectVersion']==parent_artifact['objectVersion'] and inputs['input']['sha256']==parent_artifact['sha256']
                target_proof={'podUid':pod['metadata']['uid'],'nodeName':pod['spec']['nodeName'],'fixedInput':inputs['input']}
            detail=wait(lambda:succeeded(run_id),180,'Transferred actual workload did not produce a verified result')
            assert detail['run']['mode']==run['mode'] and detail['run']['remoteTarget']==run['remoteTarget']
            results=result_files(detail);child_result=next(r for r in results if r['taskId']==task_id)
            if direction=='node-to-remote':assert child_result['remoteAllocationId']==target_proof['remoteAllocationId']
            else:assert child_result['producerPodUid']==target_proof['podUid']
            rows=remote_rows(run_id);assert all(r['state'] in ('SUCCEEDED','CANCELLED') and r['executions']==1 for r in rows)
            cleaned(run_id)
            report['runs'].append({'id':run_id,'case':direction,'taskId':task_id,'source':source_proof,'target':target_proof,'operation':operation,
                'attempts':request('tasks/'+task_id)['attempts'],'allocations':rows,'results':results,'pendingOperationRecovered':direction=='remote-to-node','resourcesRemaining':0})
            print('PASS: real '+direction+' preserves Task/input, confirms source termination, starts a new producer and commits one verified Result',flush=True)
        finally:
            scheduling(True)
            if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

    run_id=create(remote_version,remote_policy)['id']
    try:
        active=wait(lambda:running_task(run_id,'root'),60,'Remote cancellation source did not start')
        request('workflow-runs/'+run_id+'/cancel','POST',{})
        restart_api()
        wait(lambda:request('workflow-runs/'+run_id)['run']['state']=='CANCELLED',90,'Remote cancellation did not recover after API replacement')
        rows=remote_rows(run_id);assert len(rows)==1 and rows[0]['state']=='CANCELLED' and rows[0]['executions']==1
        for task in request('workflow-runs/'+run_id)['tasks']:
            assert request('tasks/'+task['id']+'/results')['items']==[]
            if task['key']=='child':assert request('tasks/'+task['id'])['attempts']==[]
        cleaned(run_id)
        report['runs'].append({'id':run_id,'case':'remote-cancel-api-restart','allocations':rows,'resourcesRemaining':0})
        print('PASS: Remote cancellation persists across API replacement, confirms actual provider termination and releases no child/Result',flush=True)
    finally:
        if request('workflow-runs/'+run_id)['run']['state'] not in ('SUCCEEDED','FAILED','CANCELLED'):cancel(run_id)

verification=subprocess.run(['node','scripts/internal/verify-runtime-artifacts.mjs'],input=json.dumps(artifacts),text=True,capture_output=True,timeout=90)
if verification.returncode:raise AssertionError('Actual S3 output verification failed; response suppressed')
print(verification.stdout.strip(),flush=True)
location=Path(args.report);location.parent.mkdir(parents=True,exist_ok=True);location.write_text(json.dumps(report,indent=2)+'\n')
print('PASS: runtime acceptance report saved; SYNTHETIC workload, not hardware/model acceptance',flush=True)
