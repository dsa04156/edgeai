"""Public API and real Device SDK scenarios for Kubernetes VD stream acceptance."""
from contextlib import ExitStack
import json
import uuid

CASES = ('vd-shared', 'vd-distinct', 'vd-mixed', 'vd-shared-recover', 'vd-distinct-recover', 'vd-shared-cancel',
         'vd-shared-replace', 'vd-distinct-pod-recover', 'vd-finalizer',
         'vd-shared-offload', 'vd-distinct-offload', 'vd-shared-offload-cancel', 'vd-shared-offload-pending-cancel')


def run_case(c, name):
    assert name in CASES
    c.csrf = c.request('csrf')['token']
    shared, recover, cancel = name.startswith('vd-shared'), name.endswith('-recover') or name.endswith('-replace'), name.endswith('-cancel')
    replace, pod_fault, finalize = name.endswith('-replace'), name.endswith('-pod-recover'), name == 'vd-finalizer'
    offload = '-offload' in name
    pending_cancel = name.endswith('-pending-cancel')
    hold_offload = name == 'vd-distinct-offload' or pending_cancel
    recover = recover or offload
    prefix = 'vd-stream-' + uuid.uuid4().hex
    request, wait = c.request, c.wait

    def publish(kind, suffix, spec):
        return request('profiles/' + kind, 'POST', {'key': prefix + '-' + suffix, 'version': '1.0.0', 'spec': spec}, 201)['id']

    profiles = {}
    for task in ('root', 'sink', 'report'):
        if shared and task == 'sink':
            profiles[task] = profiles['root']
            continue
        spec = json.loads(json.dumps(c.config['batchSpec' if task == 'report' else 'streamSpec']))
        spec.update(image=c.config['runnerImage'], timeoutSeconds=600, platform={'os': 'linux', 'architectures': ['amd64']})
        if task == 'report':
            spec['command'] = ['python3', '-c', c.config['reportCommand']]
            spec['inputs'] = {key: {'mediaType': 'application/json', 'maxBytes': 1048576, 'required': True} for key in ('root', 'sink')}
            spec['outputs'] = {'report': {'mediaType': 'application/json', 'maxBytes': 1048576}}
        elif shared:
            spec['stream']['outputs'] = {}
        elif task == 'sink':
            spec['stream']['inputs'] = {'input': {'mediaType': 'application/json', 'maxPayloadBytes': 262144}}
            spec['stream']['outputs'] = {}
            if finalize:
                spec['command'] = ['python3', '-c', c.config['finalizerCommand']]
        profiles[task] = publish('SERVICE', task, spec)
    targets, vds = {}, {}
    for task in ('root', 'sink', 'report'):
        if name == 'vd-mixed' and task == 'sink':
            targets[task] = {'mode': 'NODE', 'nodeId': c.config['nodeId']}
            continue
        if shared and task == 'sink':
            targets[task] = targets['root']
            continue
        profile = publish('VD', task, {'apiVersion': 'edgeai.vd/v1', 'type': 'emulation', 'serviceProfileVersionId': profiles[task],
            'sources': {}, 'state': {'mode': 'STATELESS'}, 'runtime': {'maxConcurrentTasks': 2 if shared and task == 'root' else 1,
            'startupTimeoutSeconds': 180, 'drainTimeoutSeconds': 30}})
        vd = request('virtual-devices', 'POST', {'key': prefix + '-' + task, 'displayName': 'Actual VD stream acceptance',
                     'profileVersionId': profile, 'sources': [], 'placement': {'mode': 'NODE', 'nodeId': c.config['nodeId']} if offload else {'mode': 'AUTO'}}, 201)
        operation = request('virtual-devices/' + vd['id'] + '/provision', 'POST', {'revision': vd['revision']}, 202, str(uuid.uuid4()))

        def ready():
            op = request('operations/' + operation['id'])
            assert op['state'] not in ('FAILED', 'SUPERSEDED')
            runtime = request('virtual-devices/' + vd['id'] + '/execution')['current']
            return runtime if runtime and runtime['ready'] and op['state'] == 'SUCCEEDED' else None

        vds[vd['id']] = wait(ready)
        targets[task] = {'mode': 'VD', 'vdId': vd['id']}
    sources, inputs = {}, []
    for port in ('a', 'b'):
        profile = publish('DEVICE', port, {'protocol': 'mqtt'})
        device = request('devices', 'POST', {'key': prefix + '-' + port, 'displayName': 'Synthetic VD stream source',
                         'profileVersionId': profile, 'sourceMode': 'SYNTHETIC'}, 201)
        session = request('devices/' + device['id'] + '/sessions', 'POST', {'bootId': str(uuid.uuid4())}, 201)
        token = request('devices/' + device['id'] + '/sessions/' + session['id'] + '/stream-token', 'POST', {})
        path = c.work / (name + '-' + port + '.token')
        path.write_text(token['token']); path.chmod(0o600)
        sources[port] = (device, session, path)
        for task in ('root', 'sink') if shared else ('root',):
            inputs.append({'deviceId': device['id'], 'sourcePort': 'samples', 'toTask': task, 'toPort': port, 'maxPayloadBytes': 4096})
    workflow = request('workflows', 'POST', {'key': prefix, 'displayName': 'Actual VD stream DAG'}, 201)
    tasks = [{'key': key, 'serviceProfileVersionId': value, 'parameters': {'mode': 'zip'} if key == 'root' or shared and key == 'sink' else {}}
             for key, value in profiles.items()]
    edges = [('root', 'report', 'result', 'root', 'BATCH'), ('sink', 'report', 'result', 'sink', 'BATCH')]
    if not shared:
        edges.append(('root', 'sink', 'sum', 'input', 'STREAM'))
    version = request('workflows/' + workflow['id'] + '/versions', 'POST', {'version': '1.0.0', 'tasks': tasks,
        'dependencies': [{'fromTask': f, 'toTask': t, 'fromPort': fp, 'toPort': tp, 'mode': m} for f, t, fp, tp, m in edges]}, 201)
    body = {'workflowVersionId': version['id'], 'execution': targets['root'], 'taskExecutions': {k: v for k, v in targets.items() if k != 'root'},
            'parameters': {}, 'streamInputs': inputs}
    if recover and not offload or finalize:
        body['retry'] = {'maxAttempts': 2, 'backoffSeconds': 1, 'maxElapsedSeconds': 600,
                         'retryOn': ['WORKLOAD_FAILED', 'RUNTIME_LOST'] if replace or pod_fault else ['WORKLOAD_FAILED']}
    run = request('workflow-runs', 'POST', body, 201, str(uuid.uuid4()))
    c.active = run['id']
    task_ids = {t['key']: t['id'] for t in request('workflow-runs/' + c.active)['tasks']}
    current = {'case': name, 'runId': c.active, 'tasks': task_ids, 'placement': targets['root'], 'taskExecutions': targets, 'vdTargets': vds}
    initial_targets = json.loads(json.dumps(targets))
    current['initialTaskExecutions'] = initial_targets

    def barrier(suffix, tick=lambda: None, **values):
        c.phase = name + '-' + suffix
        c.save('phase.json', {**current, 'phase': c.phase, **values})
        wait(lambda: (c.work / (c.phase + '.continue')).exists(), tick)

    c.phase = name + '-routing'
    c.save('phase.json', {**current, 'phase': c.phase})

    def routes():
        detail = request('workflow-runs/' + c.active)
        assert detail['run']['state'] not in ('FAILED', 'CANCELLED')
        rows = request('workflow-runs/' + c.active + '/streams')['items']
        return rows if len(rows) == (4 if shared else 3) and all(r['generation'] and r['generation']['state'] == 'ACTIVE' for r in rows) else None

    allocated = wait(routes)
    current['routes'] = allocated
    assert len({r['componentId'] for r in allocated}) == 1
    with ExitStack() as stack:
        owners, route_ids = {}, {}
        for port, (device, session, token) in sources.items():
            client = c.BindingClient(c.origin, c.Producer('DEVICE_SESSION', session['id'], session['epoch'], device['id']), token)
            page = client.device_routes(c.active)
            assert page['runState'] == 'RUNNING' and page['nextOffset'] is None and len(page['items']) == (2 if shared else 1)
            assert all(r['consumerPort'] == port and r['sourceMode'] == 'SYNTHETIC' and r['generation']['state'] == 'ACTIVE' for r in page['items'])
            route_ids[port] = [r['routeId'] for r in page['items']]
            folder = c.work / (name + '-source-' + port)
            folder.mkdir(mode=0o700); folder.chmod(0o700)
            owners[port] = stack.enter_context(c.DeviceRunSource(client, c.active, route_ids[port], folder, create=True, timeout=600))

        def tick():
            for owner in owners.values():
                owner.step()

        def emit(a, b):
            wait(lambda: all(o.ready for o in owners.values()), tick)
            for port, number in (('a', a), ('b', b)):
                owners[port].emit([c.Emission(route, str(number).encode(), 'application/json') for route in route_ids[port]], str(number).encode())

        emit(4, 5)
        checkpoints = {key: value.checkpoint() for key, value in owners.items()}
        assert not request('tasks/' + task_ids['report'])['attempts']
        barrier('first', tick, expectedStates={'root': 9, 'sink': 9})
        if recover:
            if offload:
                source = request('tasks/' + task_ids['sink'])['attempts'][0]
                target_node = c.config['targetNodeId']
                assert target_node != vds[targets['sink']['vdId']]['nodeUid']
                command = {'sourceAttemptId': source['id'], 'targetNodeId': target_node, 'drainTimeoutSeconds': 120, 'startTimeoutSeconds': 120}
                key = str(uuid.uuid4())
                operation = request('tasks/' + task_ids['sink'] + '/offload', 'POST', command, 202, key)
                assert {m['taskId'] for m in operation['members']} == {task_ids['root'], task_ids['sink']}
                peer = next(m for m in operation['members'] if m['taskId'] == task_ids['root'])
                assert peer['targetVdId'] == targets['root']['vdId'] and peer['targetNodeId'] is None
                if hold_offload:
                    current['offload'] = operation
                    barrier('offload-draining')
                    c.csrf = request('csrf')['token']
                    replay = request('tasks/' + task_ids['sink'] + '/offload', 'POST', command, 200, key)
                    assert replay['id'] == operation['id'] and replay['state'] == 'DRAINING' and replay['members'] == operation['members']
                    current['offloadReplayPreserved'] = True
                    if pending_cancel:
                        request('workflow-runs/' + c.active + '/cancel', 'POST', {})
                        current['offload'] = request('operations/' + operation['id'])
                        assert current['offload']['state'] == 'CANCELLING'
                    barrier('offload-cancelling' if pending_cancel else 'offload-releasing')

                def moved():
                    value = request('operations/' + operation['id'])
                    assert value['state'] not in ('FAILED', 'CANCELLED')
                    return value if value['state'] == 'SUCCEEDED' else None

                if not pending_cancel:
                    current['offload'] = wait(moved, tick)
                    replay = request('tasks/' + task_ids['sink'] + '/offload', 'POST', command, 200, key)
                    assert replay == current['offload']
                    current['offloadReplayPreserved'] = True
                    targets['sink'] = {'mode': 'NODE', 'nodeId': target_node}
            if replace or pod_fault:
                identity = targets['sink']['vdId']
                before = dict(vds[identity])
                if pod_fault:
                    wait(lambda: request('virtual-devices/' + identity + '/execution')['current'] is None, tick)
                action = 'replace' if replace else 'provision'
                vd = request('virtual-devices/' + identity)['vd']
                operation = request('virtual-devices/' + identity + '/' + action, 'POST', {'revision': vd['revision']}, 202, str(uuid.uuid4()))

                def replaced():
                    op = request('operations/' + operation['id'])
                    assert op['state'] not in ('FAILED', 'SUPERSEDED')
                    runtime = request('virtual-devices/' + identity + '/execution')['current']
                    return runtime if runtime and runtime['ready'] and op['state'] == 'SUCCEEDED' else None

                after = wait(replaced, tick)
                assert after['generation'] == before['generation'] + 1 and after['podUid'] != before['podUid'] and after['id'] != before['id']
                current['replacement'] = {'operation': operation['id'], 'action': action, 'before': before, 'after': after}
                vds[identity] = after
            if not pending_cancel:
                wait(lambda: all(o.ready and o.connections == 2 for o in owners.values()), tick)
                assert all(value.checkpoint() == checkpoints[key] for key, value in owners.items())
                attempts = {}
                for key in ('root', 'sink'):
                    old, new = sorted(request('tasks/' + task_ids[key])['attempts'], key=lambda a: a['number'])
                    assert old['state'] == ('OFFLOADED' if offload else 'FAILED') and new['state'] == 'RUNNING' and new['epoch'] == old['epoch'] + 1
                    assert old['vdId'] == initial_targets[key]['vdId']
                    assert new['mode'] == targets[key]['mode'] and new.get('vdId') == targets[key].get('vdId') and new.get('nodeId') == targets[key].get('nodeId')
                    assert new['cause'] == ('OFFLOAD' if offload else 'RETRY')
                    attempts[key] = new['id']
                current['recovery'] = {'sameDeviceOwners': True, 'sensorCursorsPreserved': True, 'attempts': attempts}
                barrier('recovered', tick, expectedStates={'root': 9, 'sink': 9}, expectedAttempts=attempts)
        if cancel:
            request('workflow-runs/' + c.active + '/cancel', 'POST', {})

            def cancelled_tick():
                for owner in owners.values():
                    if not owner.closed:
                        try:
                            owner.step()
                        except (c.AssignmentError, c.SourceError):
                            pass
                    assert not owner.completed

            wait(lambda: request('workflow-runs/' + c.active)['run']['state'] == 'CANCELLED' and all(o.closed for o in owners.values()), cancelled_tick)
            assert not request('tasks/' + task_ids['report'])['attempts']
            assert all(not request('tasks/' + t + '/results')['items'] for t in task_ids.values())
            if offload:
                assert all(len(request('tasks/' + task_ids[k])['attempts']) == (1 if pending_cancel else 2) for k in ('root', 'sink'))
                expected = 'CANCELLED' if pending_cancel else 'SUCCEEDED'
                wait(lambda: request('operations/' + operation['id'])['state'] == expected, cancelled_tick)
                current['offload'] = request('operations/' + operation['id'])
            current.update(cancelled=True, results=[])
        else:
            emit(2, 3)
            barrier('second', tick, expectedStates={'root': 14, 'sink': 14 if shared else 23})
            for port, owner in owners.items():
                owner.emit([c.Emission(route, b'', None, 'END') for route in route_ids[port]])
            if finalize:
                wait(lambda: request('tasks/' + task_ids['root'] + '/results')['items'] and all(o.completed for o in owners.values()), tick)
                current['preservedResult'] = request('tasks/' + task_ids['root'] + '/results')['items'][0]
                barrier('finalizer-granted', tick)
                barrier('finalizer-restoring', tick)

            def finished():
                state = request('workflow-runs/' + c.active)['run']['state']
                assert state not in ('FAILED', 'CANCELLED')
                return state == 'SUCCEEDED' and all(o.completed for o in owners.values())

            wait(finished, tick)
            results = []
            for key, task in task_ids.items():
                attempts = sorted(request('tasks/' + task)['attempts'], key=lambda a: a['number'])
                retried = recover and key != 'report' or finalize and key == 'sink'
                assert len(attempts) == (2 if retried else 1) and attempts[-1]['state'] == 'SUCCEEDED'
                if retried:
                    assert attempts[0]['state'] == ('OFFLOADED' if offload else 'FAILED') and attempts[-1]['epoch'] == attempts[0]['epoch'] + 1
                target = targets[key]
                for a in attempts:
                    expected_target = initial_targets[key] if a['number'] == 1 else target
                    assert a['mode'] == expected_target['mode'] and a.get('vdId') == expected_target.get('vdId') and a.get('nodeId') == expected_target.get('nodeId')
                values = request('tasks/' + task + '/results')['items']
                assert len(values) == 1
                result = values[0]
                assert result['attemptId'] == attempts[-1]['id'] and len(result['artifacts']) == 1
                if target['mode'] == 'VD':
                    runtime = vds[target['vdId']]
                    assert result['vdRuntimeId'] == runtime['id'] and result['producerPodUid'] == runtime['podUid']
                if finalize and key == 'root':
                    assert result == current['preservedResult']
                expected = {'sourceMode': 'SYNTHETIC', 'sum': 28 if shared else 37, 'inputs': {'root': 14, 'sink': 14 if shared else 23}} if key == 'report' else {'sum': 14 if key == 'root' or shared else 23}
                results.append({'task': key, 'result': result, 'expected': expected})
            current.update(cancelled=False, results=results)
        current['deviceConnections'] = {key: value.connections for key, value in owners.items()}
        assert all(value.connections == (2 if recover and not pending_cancel else 1) for value in owners.values())
    wait(lambda: all(r['generation']['closedAt'] for r in request('workflow-runs/' + c.active + '/streams')['items']))
    # The owner verifies exited child processes/closed allocations while VD Pods remain alive.
    barrier('children-exited')
    for identity in vds:
        vd = request('virtual-devices/' + identity)['vd']
        operation = request('virtual-devices/' + identity + '/drain', 'POST', {'revision': vd['revision']}, 202, str(uuid.uuid4()))
        wait(lambda: request('operations/' + operation['id'])['state'] == 'SUCCEEDED')
    barrier('done')
    current['resourcesRemaining'] = 0
    c.report['cases'].append(current)
    c.save('report.json', c.report)
    c.active = None
    print('STREAM_KUBERNETES_CASE_PASS ' + name, flush=True)
