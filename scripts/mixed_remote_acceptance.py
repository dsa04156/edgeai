"""Actual initial Node/Remote BATCH placement using an owned TLS reference provider.

The caller owns the API restart callback and provider Pod lookup. No node scheduling state is changed.
Only synthetic identities, immutable artifact metadata and projected runtime evidence leave private pipes.
"""
import json
import subprocess
import uuid
from vd_acceptance import ROOT, wait


def run_mixed_remote(scenario, provider_pod, restart):
    s = scenario
    report = {'scope': 'real-kubernetes-mixed-remote-initial-placement', 'sourceMode': 'SYNTHETIC',
              'runnerImage': s.image, 'cases': [], 'status': 'RUNNING'}
    phase = 'setup'

    def python(pod, namespace, code, arguments=()):
        result = subprocess.run(s.kubectl + ['-n', namespace, 'exec', '-i', pod, '--', 'python3', '-', *arguments],
                                input=code, text=True, capture_output=True, env=s.env, timeout=25)
        assert result.returncode == 0 and len(result.stdout) <= 262144, 'Mixed Remote private proof failed; output suppressed'
        try: return json.loads(result.stdout)
        except (ValueError, TypeError): raise AssertionError('Mixed Remote proof is invalid; output suppressed') from None

    def allocations(run_id):
        uuid.UUID(run_id)
        return python(provider_pod()['metadata']['name'], 'edgeai', """import json,sqlite3,sys
db=sqlite3.connect('file:/data/provider/allocations.sqlite?mode=ro',uri=True)
proof=[]
for row in db.execute('SELECT id,identity,state,executions,work FROM allocations'):
    identity=json.loads(row[1])
    if identity['runId']==sys.argv[1]:
        work=json.loads(row[4]) if row[4] else {}
        inputs=[{k:v[k] for k in ('port','bytes','sha256','mediaType')} for v in work.get('inputs',[])]
        proof.append({'id':row[0],'attemptId':identity['attemptId'],'state':row[2],'executions':row[3],'inputs':inputs})
print(json.dumps(proof))
""", [run_id])

    def resources(run_id):
        return s.kube(['-n', s.namespace, 'get', 'pods,jobs,secrets', '-l', 'edgeai.io/run-id=' + run_id, '-o', 'json'])['items']

    def target_node():
        for node in s.request('nodes?limit=100')['items']:
            if node['status'] != 'READY' or node['architecture'] != 'amd64': continue
            actual = s.kube(['get', 'node', node['name'], '-o', 'json'])
            if actual['spec'].get('unschedulable') or any(t['effect'] in ('NoSchedule', 'NoExecute') for t in actual['spec'].get('taints', [])): continue
            assert actual['metadata']['uid'] == node['id']
            return node
        return None

    node = wait(target_node, 45, 'No ready real node for mixed Remote acceptance')
    node_policy = {'mode': 'NODE', 'nodeId': node['id']}
    remote_policy = {'mode': 'REMOTE', 'providerKey': 'reference'}
    spec = json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_text())
    spec.update(image=s.image, command=['python3', '/opt/edgeai/examples/linear.py'],
                platform={'os': 'linux', 'architectures': ['amd64']}, timeoutSeconds=240, recovery={'mode': 'RESTART'},
                inputs={'input': {'mediaType': 'application/json', 'maxBytes': 1048576, 'required': False}})
    service = s.publish('SERVICE', 'mixed-remote', spec)
    cases = [
        ('node-remote-node', {'mode': 'AUTO'}, {'root': node_policy, 'bridge': remote_policy, 'sink': node_policy}, ['root', 'bridge', 'sink'], 'bridge', False),
        ('remote-auto', remote_policy, {'sink': {'mode': 'AUTO'}}, ['root', 'sink'], 'root', False),
        ('remote-cancel', node_policy, {'root': remote_policy}, ['root', 'sink'], 'root', True),
    ]
    try:
        for name, default, overrides, keys, remote_key, cancel in cases:
            phase = name + ':create'
            workflow = s.request('workflows', 'POST', {'key': s.prefix + '-' + name, 'displayName': 'Synthetic mixed Remote placement'}, expected=201)
            tasks = [{'key': key, 'serviceProfileVersionId': service, 'parameters': {
                'features': [2, 1] if key == 'root' else [99, 99], 'weights': [2, 3], 'bias': 1,
                'simulationDelayMillis': (60000 if name != 'remote-auto' else 6000) if key == remote_key else 5000}} for key in keys]
            edges = [{'fromTask': a, 'toTask': b, 'fromPort': 'output', 'toPort': 'input', 'mode': 'BATCH'} for a, b in zip(keys, keys[1:])]
            version = s.request('workflows/' + workflow['id'] + '/versions', 'POST', {'version': '1.0.0', 'tasks': tasks, 'dependencies': edges}, expected=201)
            body = {'workflowVersionId': version['id'], 'execution': default, 'taskExecutions': overrides, 'parameters': {}}
            key = str(uuid.uuid4()); run = s.request('workflow-runs', 'POST', body, key=key, expected=201)
            run_id = run['id']; seen = {}; observed_inputs = {}; restart_proof = None
            try:
                detail = s.request('workflow-runs/' + run_id)
                task_ids = {task['key']: task['id'] for task in detail['tasks']}
                initial = {task['key']: {field: task[field] for field in ('id', 'initialMode', 'initialNodeId', 'initialVdId', 'initialRemoteTarget')} for task in detail['tasks']}
                for task in detail['tasks']:
                    target = overrides.get(task['key'], default)
                    assert task['initialMode'] == target['mode'] and task['initialNodeId'] == target.get('nodeId') and task['initialVdId'] is None
                    if target['mode'] == 'REMOTE': assert task['initialRemoteTarget']['providerKey'] == 'reference'
                    else: assert task['initialRemoteTarget'] is None
                    if task['key'] != 'root':
                        assert task['state'] == 'WAITING' and not s.request('tasks/' + task['id'])['attempts']

                def observe():
                    for pod in resources(run_id):
                        if pod['kind'] != 'Pod' or not pod['spec'].get('nodeName'): continue
                        labels = pod['metadata']['labels']; task_id = labels['edgeai.io/task-id']
                        assert task_id in task_ids.values() and labels['edgeai.io/run-id'] == run_id
                        task_key = next(k for k, v in task_ids.items() if v == task_id)
                        target = overrides.get(task_key, default)
                        assert target['mode'] in ('AUTO', 'NODE')
                        actual = s.kube(['get', 'node', pod['spec']['nodeName'], '-o', 'json'])
                        if target['mode'] == 'NODE': assert actual['metadata']['uid'] == target['nodeId']
                        containers = pod.get('status', {}).get('containerStatuses', [])
                        if not containers or not containers[0].get('imageID'): continue
                        assert containers[0]['imageID'].endswith(s.image.split('@')[1])
                        seen[task_key] = {'podUid': pod['metadata']['uid'], 'attemptId': labels['edgeai.io/attempt-id'], 'nodeUid': actual['metadata']['uid']}
                        current = s.request('tasks/' + task_id)
                        if task_key != 'root' and task_key not in observed_inputs and current['attempts'][0]['state'] == 'RUNNING':
                            observed_inputs[task_key] = python(pod['metadata']['name'], s.namespace, """import json,sys,urllib.parse
sys.path.insert(0,'/opt/edgeai')
from edgeai_runner.main import Runner
r=Runner();inputs=r.api('claim',r.identity)['inputs']
print(json.dumps({k:{'bytes':v['bytes'],'sha256':v['sha256'],'objectVersion':urllib.parse.parse_qs(urllib.parse.urlsplit(v['url']).query)['versionId'][0]} for k,v in inputs.items()}))
""")

                def remote_running():
                    observe()
                    task = s.request('tasks/' + task_ids[remote_key])
                    assert task['task']['state'] not in ('FAILED', 'CANCELLED', 'SKIPPED'), 'Mixed Remote task failed before observation'
                    if not task['attempts'] or task['attempts'][0]['state'] != 'RUNNING': return None
                    rows = allocations(run_id)
                    assert len(rows) == 1 and rows[0]['state'] == 'RUNNING' and rows[0]['executions'] == 1
                    assert rows[0]['attemptId'] == task['attempts'][0]['id']
                    return rows[0]

                phase = name + ':remote-running'
                active = wait(remote_running, 100, 'Initial Remote target did not start from its pinned Task')
                provider_uid = provider_pod()['metadata']['uid']
                if cancel:
                    phase = name + ':cancel'
                    s.request('workflow-runs/' + run_id + '/cancel', 'POST', {})
                if name != 'remote-auto':
                    phase = name + ':api-restart'
                    restart_proof = restart(); s.csrf = s.request('csrf')['token']
                    assert provider_pod()['metadata']['uid'] == provider_uid, 'API restart replaced the independent provider'
                    rows = allocations(run_id)
                    assert len(rows) == 1 and rows[0]['id'] == active['id'] and rows[0]['executions'] == 1
                assert s.request('workflow-runs', 'POST', body, key=key)['id'] == run_id

                def terminal():
                    observe()
                    current = s.request('workflow-runs/' + run_id)
                    assert current['run']['state'] != 'FAILED', 'Mixed Remote run failed'
                    if not cancel: assert current['run']['state'] != 'CANCELLED'
                    return current if current['run']['state'] == ('CANCELLED' if cancel else 'SUCCEEDED') else None

                phase = name + ':complete'
                done = wait(terminal, 180, 'Mixed Remote run did not reach its confirmed terminal state')
                assert {task['key']: {field: task[field] for field in initial[task['key']]} for task in done['tasks']} == initial
                rows = allocations(run_id)
                assert len(rows) == 1 and rows[0]['id'] == active['id'] and rows[0]['executions'] == 1
                assert rows[0]['state'] == ('CANCELLED' if cancel else 'SUCCEEDED')
                results, artifacts = [], []
                by_key = {}
                for task_key in keys:
                    task_id = task_ids[task_key]; current = s.request('tasks/' + task_id)
                    items = s.request('tasks/' + task_id + '/results')['items']
                    if cancel:
                        assert not items
                        if task_key != 'root': assert not current['attempts']
                        continue
                    assert len(items) == 1 and len(items[0]['artifacts']) == 1 and len(current['attempts']) == 1
                    result = items[0]; attempt = current['attempts'][0]; target = overrides.get(task_key, default)
                    assert attempt['state'] == 'SUCCEEDED' and attempt['cause'] == 'INITIAL' and attempt['number'] == 1 and attempt['epoch'] == 1
                    assert attempt['mode'] == target['mode'] and attempt['nodeId'] == target.get('nodeId') and attempt['vdId'] is None
                    assert result['attemptId'] == attempt['id'] and result['vdRuntimeId'] is None
                    if target['mode'] == 'REMOTE':
                        assert attempt['remoteTarget'] == initial[task_key]['initialRemoteTarget']
                        assert result['remoteAllocationId'] == rows[0]['id'] and result['producerPodUid'] is None and result['remoteSourceMode'] == 'SYNTHETIC'
                    else:
                        assert result['producerPodUid'] == seen[task_key]['podUid'] and result['attemptId'] == seen[task_key]['attemptId']
                        assert result['remoteAllocationId'] is None and attempt['remoteTarget'] is None
                    by_key[task_key] = result
                    artifacts.append({'artifact': result['artifacts'][0], 'expected': {'sourceMode': 'SYNTHETIC', 'features': [2, 1], 'score': 8, 'prediction': 1}})
                    results.append({'task': task_key, 'result': result})
                if not cancel:
                    for before, after in zip(keys, keys[1:]):
                        artifact = by_key[before]['artifacts'][0]
                        if overrides.get(after, default)['mode'] == 'REMOTE':
                            assert rows[0]['inputs'] == [{'port': 'input', **{k: artifact[k] for k in ('bytes', 'sha256', 'mediaType')}}]
                        else:
                            assert observed_inputs[after]['input'] == {k: artifact[k] for k in ('bytes', 'sha256', 'objectVersion')}
                    verified = subprocess.run(['node', 'scripts/verify-runtime-artifacts.mjs'], input=json.dumps(artifacts), text=True, capture_output=True, env=s.env, timeout=60)
                    assert verified.returncode == 0, 'Mixed Remote fixed S3 result content differs; output suppressed'
                phase = name + ':cleanup'
                wait(lambda: not resources(run_id), 90, 'Mixed Remote owned runtime resources did not terminate')
                report['cases'].append({'case': name, 'runId': run_id, 'execution': default, 'taskExecutions': overrides,
                    'initialTasks': initial, 'providerPodUid': provider_uid, 'allocations': rows, 'observedPods': seen,
                    'fixedNodeInputs': observed_inputs, 'results': results, 'restart': restart_proof,
                    'verifiedArtifacts': len(artifacts), 'resourcesRemaining': 0})
                print('PASS: actual initial ' + name + ', pinned Task targets, provider computation count and physical cleanup', flush=True)
            finally:
                state = s.request('workflow-runs/' + run_id)['run']['state']
                if state not in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
                    s.csrf = s.request('csrf')['token']
                    s.request('workflow-runs/' + run_id + '/cancel', 'POST', {})
                    wait(lambda: s.request('workflow-runs/' + run_id)['run']['state'] == 'CANCELLED', 90, 'Mixed Remote failure cleanup did not finish')
                wait(lambda: not resources(run_id), 90, 'Mixed Remote failure cleanup left owned runtime resources')
        report['status'] = 'PASS'
    except BaseException as error:
        report.update(status='FAIL', failedPhase=phase, errorType=type(error).__name__)
        raise
    finally:
        s.report_path.parent.mkdir(exist_ok=True, parents=True)
        s.report_path.write_text(json.dumps(report, indent=2) + '\n')
    return report
