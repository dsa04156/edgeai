"""Actual independent Runners: two Devices -> stream root -> stream sink -> BATCH.

Java supplies only Pod provisioning/identity and public Run creation/cancellation.
Each real Runner claims, discovers routes, computes, checkpoints and commits itself.
"""
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from datetime import datetime, timezone

repo = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(repo / 'runner'))
from edgeai_runner.stream_assignment import AssignmentError, BindingClient
from edgeai_runner.stream_checkpoint_client import CheckpointClient, CheckpointUnavailable
from edgeai_runner.stream_journal import Emission
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_source import SourceError
from edgeai_runner.stream_device_run import DeviceRunSource

folder = Path(sys.argv[1])
config = json.loads((folder / 'request.json').read_bytes())
children = {}
phase = 'START'
round_number = 1
recovering = False
retired = []

def directory(name):
    return folder / (name if round_number == 1 or name == 'report' else name + '-' + str(round_number))


def launch():
    for name in ('root', 'sink', 'report'):
        target = directory(name)
        descriptor = target / 'launch.json'
        if name in children or not descriptor.exists():
            continue
        value = json.loads(descriptor.read_bytes())
        work = target / 'work'
        work.mkdir(mode=0o700)
        work.chmod(0o2700)  # Reproduce the setgid directory inherited from Kubernetes fsGroup.
        env = os.environ.copy()
        env.update(EDGEAI_ATTEMPT_ID=value['attemptId'], EDGEAI_ATTEMPT_EPOCH=str(value['epoch']),
                   EDGEAI_POD_UID=value['podUid'], EDGEAI_CONTROL_PLANE_URL=config['origin'],
                   EDGEAI_CLAIM_FILE=str(target / 'claim'), EDGEAI_POD_TOKEN_FILE=str(target / 'pod'),
                   EDGEAI_WORK_DIR=str(work))
        for filename in ('runner.log', 'runner-error.log'):
            (target / filename).touch(mode=0o600)
        with (target / 'runner.log').open('wb') as out, (target / 'runner-error.log').open('wb') as err:
            children[name] = subprocess.Popen([sys.executable, '-W', 'error::ResourceWarning', str(repo / 'runner' / 'runner.py')],
                                               env=env, stdout=out, stderr=err)


def wait(predicate, tick=lambda: None):
    end = time.monotonic() + 40
    while not predicate():
        assert time.monotonic() < end, 'Actual stream DAG deadline exceeded'
        launch()
        if not (folder / 'cancel-request').exists() and not recovering:
            assert all(p.poll() in (None, 0) for p in children.values()), 'Actual Runner failed'
        tick()
        time.sleep(.01)


def main():
    global phase, round_number, recovering
    phase = 'ROUTING'
    wait(lambda: (folder / 'routing.json').exists())
    routing = json.loads((folder / 'routing.json').read_bytes())
    with ExitStack() as stack:
        sources = {}
        discovered_routes = {}
        checkpoints = {}

        def connect_sources():
            for name, value in config['sources'].items():
                client = BindingClient(config['origin'], Producer('DEVICE_SESSION', value['sessionId'], value['epoch'], value['deviceId']),
                                       folder / (name + '.token'))
                discovered = client.device_routes(config['runId'])
                assert discovered['runState'] == 'RUNNING' and discovered['nextOffset'] is None
                assert len(discovered['items']) == 1
                route = discovered['items'][0]
                discovered_routes[name] = route
                assert route['routeId'] == routing['sources'][name]['routeId']
                assert route['generation']['id'] == routing['sources'][name]['generationId']
                assert route['generation']['state'] == 'ACTIVE' and route['sourceMode'] == 'SYNTHETIC'
                target = folder / ('source-' + name)
                target.mkdir(mode=0o700)
                sources[name] = stack.enter_context(DeviceRunSource(client, config['runId'], [route['routeId']],
                                                                    target, create=True, timeout=100))

        def connect_checkpoints():
            for name in ('root', 'sink'):
                target = directory(name)
                value = json.loads((target / 'launch.json').read_bytes())
                client = BindingClient(config['origin'], Producer('TASK_ATTEMPT', value['attemptId'], value['epoch']),
                                       target / 'claim', pod_uid=value['podUid'], pod_token_file=target / 'pod')
                checkpoints[name] = CheckpointClient(client, config['runId'], routing['tasks'][name])

        connect_sources()
        connect_checkpoints()

        def step():
            for source in sources.values():
                source.step()

        def checkpointed(expected):
            for name, state in expected.items():
                try:
                    value = checkpoints[name].latest()['checkpoint']
                except CheckpointUnavailable:
                    return False  # Poll again within wait()'s existing deadline; rejection/fencing still fails.
                if value is None or value['summary']['stateSha256'] != hashlib.sha256(str(state).encode()).hexdigest():
                    return False
            return True

        def emit(a, b):
            for name, number in (('a', a), ('b', b)):
                sources[name].emit([Emission(discovered_routes[name]['routeId'], str(number).encode(), 'application/json')], str(number).encode())

        phase = 'FIRST_CHECKPOINT'
        wait(lambda: all(s.ready for s in sources.values()), step)
        emit(4, 5)
        wait(lambda: checkpointed({'root': 9, 'sink': 9}), step)
        assert len(children) == 2 and all(p.poll() is None for p in children.values())
        if config['mode'] == 'recover':
            phase = 'GROUP_RECOVERY'
            recovering = True
            owners = dict(sources)
            source_checkpoints = {name:s.checkpoint() for name,s in sources.items()}
            (folder / 'recovery-request').touch()
            metric_sequence = 0

            def fenced_sources():
                nonlocal metric_sequence
                if config.get('automatic'):
                    # Controlled workload latency input, independently read and sent by the real Runner.
                    # This acceptance covers transport/decision/state handover; real cgroup placement is a Kubernetes gate.
                    metric_sequence += 1
                    target = directory('root') / 'work'
                    metric = target / 'telemetry.tmp'
                    metric.write_text(json.dumps({'sequence': metric_sequence, 'observedAt': datetime.now(timezone.utc).isoformat(), 'latencyMicros': 1200}))
                    metric.replace(target / 'telemetry.json')
                for source in sources.values():
                    source.step()
                    assert not source.completed and not source.closed

            wait(lambda: all(p.poll() is not None for p in children.values()) and all(not s.ready for s in sources.values()), fenced_sources)
            assert all(p.wait() != 0 for p in children.values())
            assert all(not (directory(name) / 'work' / 'outputs' / 'result').exists() for name in children)
            retired.extend(children.values())
            children.clear()
            round_number = 2
            recovering = False
            (folder / 'producers-stopped').touch()
            wait(lambda: (folder / 'routing-2.json').exists(), step)
            routing = json.loads((folder / 'routing-2.json').read_bytes())
            connect_checkpoints()
            wait(lambda: (folder / 'restored').exists() and all(s.ready for s in sources.values()), step)
            for name, source in sources.items():
                assert source is owners[name] and source.connections == 2
                assert source.checkpoint() == source_checkpoints[name]
                assert set(source.source.assignments) == {routing['sources'][name]['generationId']}
            assert all(directory(name).name.endswith('-2') for name in ('root', 'sink'))
        if config['mode'] == 'cancel':
            phase = 'CANCEL'
            (folder / 'cancel-request').touch()
            wait(lambda: (folder / 'cancelled').exists())

            def cancelled_step():
                for source in sources.values():
                    if source.closed:
                        continue
                    try:
                        source.step()
                    except (AssignmentError, SourceError):
                        pass
                    assert not source.completed

            wait(lambda: all(p.poll() is not None for p in children.values()) and all(s.closed for s in sources.values()), cancelled_step)
            assert set(children) == {'root', 'sink'}
            for name, child in children.items():
                assert child.returncode == 1
                lines = (directory(name) / 'runner.log').read_text().splitlines()
                assert len(lines) == 2 and lines[0] == 'RUNNER_WORKLOAD_START' and re.fullmatch('RUNNER_FAILED [A-Z_]+', lines[1])
                assert not (directory(name) / 'work' / 'outputs' / 'result').exists()
            print('STREAM_DAG_CANCELLED')
        else:
            phase = 'SECOND_CHECKPOINT'
            emit(2, 3)
            # Root emits cumulative totals 9 and 14. Sink sums those to 23.
            wait(lambda: checkpointed({'root': 14, 'sink': 23}), step)
            for name, source in sources.items():
                source.emit([Emission(discovered_routes[name]['routeId'], b'', None, 'END')])
            phase = 'RESULTS'
            wait(lambda: set(children) == {'root', 'sink', 'report'} and all(p.poll() == 0 for p in children.values())
                 and all(s.completed for s in sources.values()), step)
            for name, expected in (('root', 14), ('sink', 23)):
                work = directory(name) / 'work'
                assert (work / 'stream-state').read_bytes() == str(expected).encode()
                assert json.loads((work / 'outputs' / 'result').read_bytes()) == {'sum': expected}
                assert (work / 'stream').is_dir()
            work = folder / 'report' / 'work'
            assert json.loads((work / 'inputs' / 'root').read_bytes()) == {'sum': 14}
            assert json.loads((work / 'inputs' / 'sink').read_bytes()) == {'sum': 23}
            assert json.loads((work / 'outputs' / 'report').read_bytes()) == {'sourceMode': 'SYNTHETIC', 'sum': 37, 'inputs': {'root': 14, 'sink': 23}}
            for name in children:
                assert (directory(name) / 'runner.log').read_bytes() == b'RUNNER_WORKLOAD_START\nRUNNER_RESULT_COMMITTED\n'
            print('STREAM_DAG_RECOVERED' if config['mode'] == 'recover' else 'STREAM_DAG_PASS')
        assert all((directory(name) / 'runner-error.log').stat().st_size == 0 for name in children)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        locations = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        print('STREAM_DAG_FAILED ' + phase + ' ' + type(error).__name__ + ' ' + locations, flush=True)
        for name in children:
            for line in (directory(name) / 'runner.log').read_text().splitlines():
                if re.fullmatch('RUNNER_FAILED [A-Z_]+', line):
                    print(name + ' ' + line, flush=True)
        sys.exit(1)
    finally:
        for child in list(children.values()) + retired:
            if child.poll() is None:
                child.terminate()
        for child in list(children.values()) + retired:
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
