"""Synthetic multi-device STREAM/BATCH driver running inside an owned Kubernetes Pod.

Only public management HTTP and real DeviceSource SDK calls are made here. The
outside owner observes actual Pods/checkpoints and releases the named barriers.
"""
from contextlib import ExitStack
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import signal
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, '/opt/edgeai')
from edgeai_runner.stream_assignment import AssignmentError, BindingClient
from edgeai_runner.stream_journal import Emission
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_source import DeviceSource

work = Path('/work')
config = json.loads(Path('/scenario/config.json').read_bytes())
origin = config['origin']
auth = 'Basic ' + base64.b64encode((os.environ['EDGEAI_API_USER'] + ':' + os.environ['EDGEAI_API_PASSWORD']).encode()).decode()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
                                   urllib.request.HTTPSHandler(context=ssl.create_default_context()))
csrf = None
active = None
phase = 'START'
report = {'scope': 'real-kubernetes-stream-dag', 'sourceMode': 'SYNTHETIC', 'cases': []}


def save(name, value):
    temporary = work / (name + '.tmp')
    temporary.write_text(json.dumps(value))
    temporary.chmod(0o600)
    temporary.replace(work / name)


def request(path, method='GET', body=None, expected=200, key=None):
    global csrf
    deadline = time.monotonic() + 30
    while True:
        headers = {'Authorization': auth}
        if csrf:
            headers['X-CSRF-TOKEN'] = csrf
        if key:
            headers['Idempotency-Key'] = key
        if body is not None:
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(origin + '/api/v1/' + path, data=None if body is None else json.dumps(body).encode(), method=method, headers=headers)
        try:
            response = client.open(req, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        except OSError:
            if method == 'GET' and time.monotonic() < deadline:
                time.sleep(.2)
                continue
            raise AssertionError('Management connection unavailable') from None
        with response:
            status = response.status
            payload = response.read(1048577)
        if method == 'GET' and status in (502, 503, 504) and time.monotonic() < deadline:
            time.sleep(.2)
            continue
        if status == 403 and method != 'GET' and time.monotonic() < deadline:
            csrf = request('csrf')['token']
            continue
        assert status == expected, 'Unexpected management HTTP status ' + str(status)
        assert len(payload) <= 1048576
        return json.loads(payload)


def wait(predicate, tick=lambda: None, seconds=180):
    deadline = time.monotonic() + seconds
    while True:
        result = predicate()
        if result:
            return result
        assert time.monotonic() < deadline, 'Stream acceptance boundary deadline'
        tick()
        time.sleep(.02)


def run_case(name, placement, cancel=False):
    global active, phase, csrf
    csrf = request('csrf')['token']
    prefix = 'stream-demo-' + uuid.uuid4().hex

    def publish(kind, suffix, spec):
        return request('profiles/' + kind, 'POST', {'key': prefix + '-' + suffix, 'version': '1.0.0', 'spec': spec}, 201)['id']

    profiles = {}
    for task in ('root', 'sink', 'report'):
        spec = json.loads(json.dumps(config['batchSpec' if task == 'report' else 'streamSpec']))
        spec.update(image=config['runnerImage'], timeoutSeconds=600, platform={'os': 'linux', 'architectures': ['amd64']})
        if task == 'sink':
            spec['stream']['inputs'] = {'input': {'mediaType': 'application/json', 'maxPayloadBytes': 262144}}
            spec['stream']['outputs'] = {}
        elif task == 'report':
            spec['command'] = ['python3', '-c', config['reportCommand']]
            spec['inputs'] = {key: {'mediaType': 'application/json', 'maxBytes': 1048576, 'required': True} for key in ('root', 'sink')}
            spec['outputs'] = {'report': {'mediaType': 'application/json', 'maxBytes': 1048576}}
        profiles[task] = publish('SERVICE', task, spec)
    sources = {}
    inputs = []
    for port in ('a', 'b'):
        profile = publish('DEVICE', port, {'protocol': 'mqtt'})
        device = request('devices', 'POST', {'key': prefix + '-' + port, 'displayName': 'Synthetic stream acceptance',
                         'profileVersionId': profile, 'sourceMode': 'SYNTHETIC'}, 201)
        session = request('devices/' + device['id'] + '/sessions', 'POST', {'bootId': str(uuid.uuid4())}, 201)
        token = request('devices/' + device['id'] + '/sessions/' + session['id'] + '/stream-token', 'POST', {})
        token_file = work / (name + '-' + port + '.token')
        token_file.write_text(token['token'])
        token_file.chmod(0o600)
        sources[port] = (device, session, token_file)
        inputs.append({'deviceId': device['id'], 'sourcePort': 'samples', 'toTask': 'root', 'toPort': port, 'maxPayloadBytes': 4096})
    workflow = request('workflows', 'POST', {'key': prefix, 'displayName': 'Actual multi-device stream DAG'}, 201)
    tasks = [{'key': key, 'serviceProfileVersionId': value, 'parameters': {'mode': 'zip'} if key == 'root' else {}} for key, value in profiles.items()]
    edges = [{'fromTask': f, 'toTask': t, 'fromPort': fp, 'toPort': tp, 'mode': mode} for f, t, fp, tp, mode in (
        ('root', 'sink', 'sum', 'input', 'STREAM'), ('root', 'report', 'result', 'root', 'BATCH'), ('sink', 'report', 'result', 'sink', 'BATCH'))]
    version = request('workflows/' + workflow['id'] + '/versions', 'POST', {'version': '1.0.0', 'tasks': tasks, 'dependencies': edges}, 201)
    run = request('workflow-runs', 'POST', {'workflowVersionId': version['id'], 'execution': placement, 'parameters': {}, 'streamInputs': inputs}, 201, str(uuid.uuid4()))
    active = run['id']
    detail = request('workflow-runs/' + active)
    task_ids = {t['key']: t['id'] for t in detail['tasks']}
    current = {'case': name, 'runId': active, 'tasks': task_ids, 'placement': placement}

    def routes():
        values = request('workflow-runs/' + active + '/streams')['items']
        assert request('workflow-runs/' + active)['run']['state'] not in ('FAILED', 'CANCELLED'), 'Run failed before routes became ready'
        return values if len(values) == 3 and all(r['generation'] and r['generation']['state'] == 'ACTIVE' for r in values) else None

    phase = name + '-routing'
    save('phase.json', {**current, 'phase': phase})
    allocated = wait(routes)
    assert len({r['componentId'] for r in allocated}) == 1
    device_routes = {r['consumerPort']: r for r in allocated if r['sourceDeviceId']}
    current['routes'] = allocated
    with ExitStack() as stack:
        owners = {}
        for port, (device, session, token_file) in sources.items():
            route = device_routes[port]
            assert route['sourceMode'] == 'SYNTHETIC' and route['sourceSessionId'] == session['id']
            binding = BindingClient(origin, Producer('DEVICE_SESSION', session['id'], session['epoch'], device['id']), token_file)
            directory = work / (name + '-source-' + port)
            directory.mkdir(mode=0o700)
            directory.chmod(0o700)  # The driver owns this new directory on an fsGroup volume.
            owners[port] = stack.enter_context(DeviceSource(binding, active, [route['generation']['id']], directory, create=True, timeout=600))

        def tick():
            for owner in owners.values():
                owner.step()

        def emit(a, b):
            for port, number in (('a', a), ('b', b)):
                owners[port].emit([Emission(device_routes[port]['id'], str(number).encode(), 'application/json')])

        emit(4, 5)
        phase = name + '-first'
        save('phase.json', {**current, 'phase': phase, 'expectedStates': {'root': 9, 'sink': 9}})
        wait(lambda: (work / (phase + '.continue')).exists(), tick)
        if cancel:
            request('tasks/' + task_ids['sink'] + '/cancel', 'POST', {})

            def tick_cancel():
                for owner in owners.values():
                    if owner.closed:
                        continue
                    try:
                        owner.step()
                    except AssignmentError:
                        pass
                    assert not owner.completed

            wait(lambda: all(request('tasks/' + t)['task']['state'] in ('SKIPPED', 'CANCELLED') for t in task_ids.values()), tick_cancel)
            assert request('tasks/' + task_ids['sink'])['task']['state'] == 'CANCELLED'
            assert not request('tasks/' + task_ids['report'])['attempts']
            assert all(not request('tasks/' + t + '/results')['items'] for t in task_ids.values())
            current.update(cancelled=True, results=[])
        else:
            emit(2, 3)
            phase = name + '-second'
            save('phase.json', {**current, 'phase': phase, 'expectedStates': {'root': 14, 'sink': 23}})
            wait(lambda: (work / (phase + '.continue')).exists(), tick)
            for port, owner in owners.items():
                owner.emit([Emission(device_routes[port]['id'], b'', None, 'END')])
            phase = name + '-results'
            save('phase.json', {**current, 'phase': phase})

            def finished():
                state = request('workflow-runs/' + active)['run']['state']
                assert state not in ('FAILED', 'CANCELLED'), 'Actual stream DAG failed'
                return state == 'SUCCEEDED' and all(o.completed for o in owners.values())

            wait(finished, tick)
            results = []
            for key, identity in task_ids.items():
                attempts = request('tasks/' + identity)['attempts']
                values = request('tasks/' + identity + '/results')['items']
                assert len(attempts) == 1 and attempts[0]['state'] == 'SUCCEEDED' and len(values) == 1
                value = values[0]
                assert value['attemptId'] == attempts[0]['id'] and value['producerPodUid'] and len(value['artifacts']) == 1
                expected = {'sourceMode': 'SYNTHETIC', 'sum': 37, 'inputs': {'root': 14, 'sink': 23}} if key == 'report' else {'sum': 14 if key == 'root' else 23}
                results.append({'task': key, 'result': value, 'expected': expected})
            current.update(cancelled=False, results=results)
    wait(lambda: all(r['generation']['closedAt'] for r in request('workflow-runs/' + active + '/streams')['items']))
    current['closedRoutes'] = request('workflow-runs/' + active + '/streams')['items']
    report['cases'].append(current)
    save('report.json', report)
    active = None
    phase = name + '-done'
    save('phase.json', {**current, 'phase': phase})
    wait(lambda: (work / (phase + '.continue')).exists())
    print('STREAM_KUBERNETES_CASE_PASS ' + name, flush=True)


def main():
    run_case('auto', {'mode': 'AUTO'})
    run_case('node', {'mode': 'NODE', 'nodeId': config['nodeId']})
    run_case('cancel', {'mode': 'AUTO'}, cancel=True)
    save('done.json', report)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    try:
        main()
    except Exception as error:
        if active:
            try:
                request('workflow-runs/' + active + '/cancel', 'POST', {})
            except Exception:
                pass
        import traceback
        locations = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        save('failure.json', {'phase': phase, 'type': type(error).__name__, 'locations': locations})
        print('STREAM_KUBERNETES_FAILED ' + phase + ' ' + type(error).__name__ + ' ' + locations, flush=True)
    while True:
        time.sleep(1)
