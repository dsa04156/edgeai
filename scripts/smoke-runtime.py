"""Real Kubernetes Runner + Control Plane + versioned S3 + Result acceptance; all workload data is SYNTHETIC."""
import argparse
import base64
import copy
import http.cookiejar
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--context', required=True)
parser.add_argument('--report', default='.tools/runtime-smoke.json')
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

def request(path,method='GET',body=None,key=None,expected=200):
    headers={'Authorization':auth}
    if csrf:headers['X-CSRF-TOKEN']=csrf
    if key:headers['Idempotency-Key']=key
    if body is not None:headers['Content-Type']='application/json'
    req=urllib.request.Request(api+'/api/v1/'+path,headers=headers,method=method,data=None if body is None else json.dumps(body).encode())
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
digest=release['runnerDigest'];assert re.fullmatch(r'sha256:[0-9a-f]{64}',digest)
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

def execute(version,policy):
    run=create(version,policy);run_id=run['id'];seen={}
    print('Running real BATCH '+policy['mode']+' Run '+run_id,flush=True)
    try:
        def completed():
            for resource in resources(run_id):
                if resource['kind']=='Pod' and resource['spec'].get('nodeName'):
                    seen[resource['metadata']['uid']]={'nodeName':resource['spec']['nodeName'],'attemptId':resource['metadata']['labels']['edgeai.io/attempt-id']}
            detail=request('workflow-runs/'+run_id)
            if detail['run']['state'] in ('FAILED','CANCELLED'):raise AssertionError('Actual runtime failed for Run '+run_id)
            return detail if detail['run']['state']=='SUCCEEDED' else None
        detail=wait(completed,180,'Actual Runner BATCH did not finish for Run '+run_id)
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
        report['runs'].append({'id':run_id,'policy':policy,'results':results,'observedPods':seen})
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

for case in ['cancel-running','unsatisfiable-affinity']:
    spec=copy.deepcopy(base);spec['command']=['python3','-c','import time; time.sleep(110)']
    if case=='unsatisfiable-affinity':spec['nodeSelector']={'edgeai.io/test-constraint':uuid.uuid4().hex}
    run=create(workflow(case,profile(case,spec)),{'mode':'AUTO'});run_id=run['id']
    try:
        def observed():
            pods=[r for r in resources(run_id) if r['kind']=='Pod']
            if case=='cancel-running':
                task=request('workflow-runs/'+run_id)['tasks'][0]
                return any(p['status'].get('phase')=='Running' for p in pods) and request('tasks/'+task['id'])['attempts'][0]['state']=='RUNNING'
            return any(not p['spec'].get('nodeName') and any(c.get('reason')=='Unschedulable' for c in p['status'].get('conditions',[])) for p in pods)
        wait(observed,60,'Did not observe real '+case)
    finally:cancel(run_id)
    for task in request('workflow-runs/'+run_id)['tasks']:assert request('tasks/'+task['id']+'/results')['items']==[]
    report['runs'].append({'id':run_id,'case':case,'state':'CANCELLED','resourcesRemaining':0})
    print('PASS: real '+case+'; producer stopped, empty results, no remaining resources',flush=True)

verification=subprocess.run(['node','scripts/verify-runtime-artifacts.mjs'],input=json.dumps(artifacts),text=True,capture_output=True,timeout=90)
if verification.returncode:raise AssertionError('Actual S3 output verification failed; response suppressed')
print(verification.stdout.strip(),flush=True)
location=Path(args.report);location.parent.mkdir(parents=True,exist_ok=True);location.write_text(json.dumps(report,indent=2)+'\n')
print('PASS: runtime acceptance report saved; SYNTHETIC workload, not hardware/model acceptance',flush=True)
