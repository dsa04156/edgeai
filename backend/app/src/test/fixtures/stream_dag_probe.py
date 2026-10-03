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

repo = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(repo / 'runner'))
from edgeai_runner.stream_assignment import AssignmentError, BindingClient
from edgeai_runner.stream_checkpoint_client import CheckpointClient
from edgeai_runner.stream_journal import Emission
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_source import DeviceSource

folder = Path(sys.argv[1])
config = json.loads((folder / 'request.json').read_bytes())
children = {}
phase = 'START'


def launch():
    for name in ('root', 'sink', 'report'):
        directory = folder / name
        descriptor = directory / 'launch.json'
        if name in children or not descriptor.exists():
            continue
        value = json.loads(descriptor.read_bytes())
        work = directory / 'work'
        work.mkdir(mode=0o700)
        work.chmod(0o2700)  # Reproduce the setgid directory inherited from Kubernetes fsGroup.
        env = os.environ.copy()
        env.update(EDGEAI_ATTEMPT_ID=value['attemptId'], EDGEAI_ATTEMPT_EPOCH=str(value['epoch']),
                   EDGEAI_POD_UID=value['podUid'], EDGEAI_CONTROL_PLANE_URL=config['origin'],
                   EDGEAI_CLAIM_FILE=str(directory / 'claim'), EDGEAI_POD_TOKEN_FILE=str(directory / 'pod'),
                   EDGEAI_WORK_DIR=str(work))
        for filename in ('runner.log', 'runner-error.log'):
            (directory / filename).touch(mode=0o600)
        with (directory / 'runner.log').open('wb') as out, (directory / 'runner-error.log').open('wb') as err:
            children[name] = subprocess.Popen([sys.executable, '-W', 'error::ResourceWarning', str(repo / 'runner' / 'runner.py')],
                                               env=env, stdout=out, stderr=err)


def wait(predicate, tick=lambda: None):
    end = time.monotonic() + 40
    while not predicate():
        assert time.monotonic() < end, 'Actual stream DAG deadline exceeded'
        launch()
        if not (folder / 'cancel-request').exists():
            assert all(p.poll() in (None, 0) for p in children.values()), 'Actual Runner failed'
        tick()
        time.sleep(.01)


def main():
    global phase
    phase = 'ROUTING'
    wait(lambda: (folder / 'routing.json').exists())
    routing = json.loads((folder / 'routing.json').read_bytes())
    with ExitStack() as stack:
        sources = {}
        for name, value in config['sources'].items():
            client = BindingClient(config['origin'], Producer('DEVICE_SESSION', value['sessionId'], value['epoch'], value['deviceId']),
                                   folder / (name + '.token'))
            directory = folder / ('source-' + name)
            directory.mkdir(mode=0o700)
            sources[name] = stack.enter_context(DeviceSource(client, config['runId'], [routing['sources'][name]['generationId']],
                                                             directory, create=True, timeout=100))
        checkpoints = {}
        for name in ('root', 'sink'):
            directory = folder / name
            value = json.loads((directory / 'launch.json').read_bytes())
            client = BindingClient(config['origin'], Producer('TASK_ATTEMPT', value['attemptId'], value['epoch']),
                                   directory / 'claim', pod_uid=value['podUid'], pod_token_file=directory / 'pod')
            checkpoints[name] = CheckpointClient(client, config['runId'], routing['tasks'][name])

        def step():
            for source in sources.values():
                source.step()

        def checkpointed(expected):
            for name, state in expected.items():
                value = checkpoints[name].latest()['checkpoint']
                if value is None or value['summary']['stateSha256'] != hashlib.sha256(str(state).encode()).hexdigest():
                    return False
            return True

        def emit(a, b):
            for name, number in (('a', a), ('b', b)):
                sources[name].emit([Emission(routing['sources'][name]['routeId'], str(number).encode(), 'application/json')])

        phase = 'FIRST_CHECKPOINT'
        emit(4, 5)
        wait(lambda: checkpointed({'root': 9, 'sink': 9}), step)
        assert len(children) == 2 and all(p.poll() is None for p in children.values())
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
                    except AssignmentError:
                        pass
                    assert not source.completed

            wait(lambda: all(p.poll() is not None for p in children.values()) and all(s.closed for s in sources.values()), cancelled_step)
            assert set(children) == {'root', 'sink'}
            for name, child in children.items():
                assert child.returncode == 1
                lines = (folder / name / 'runner.log').read_text().splitlines()
                assert len(lines) == 2 and lines[0] == 'RUNNER_WORKLOAD_START' and re.fullmatch('RUNNER_FAILED [A-Z_]+', lines[1])
                assert not (folder / name / 'work' / 'outputs' / 'result').exists()
            print('STREAM_DAG_CANCELLED')
        else:
            phase = 'SECOND_CHECKPOINT'
            emit(2, 3)
            # Root emits cumulative totals 9 and 14. Sink sums those to 23.
            wait(lambda: checkpointed({'root': 14, 'sink': 23}), step)
            for name, source in sources.items():
                source.emit([Emission(routing['sources'][name]['routeId'], b'', None, 'END')])
            phase = 'RESULTS'
            wait(lambda: set(children) == {'root', 'sink', 'report'} and all(p.poll() == 0 for p in children.values())
                 and all(s.completed for s in sources.values()), step)
            for name, expected in (('root', 14), ('sink', 23)):
                work = folder / name / 'work'
                assert (work / 'stream-state').read_bytes() == str(expected).encode()
                assert json.loads((work / 'outputs' / 'result').read_bytes()) == {'sum': expected}
                assert (work / 'stream').is_dir()
            work = folder / 'report' / 'work'
            assert json.loads((work / 'inputs' / 'root').read_bytes()) == {'sum': 14}
            assert json.loads((work / 'inputs' / 'sink').read_bytes()) == {'sum': 23}
            assert json.loads((work / 'outputs' / 'report').read_bytes()) == {'sourceMode': 'SYNTHETIC', 'sum': 37, 'inputs': {'root': 14, 'sink': 23}}
            for name in children:
                assert (folder / name / 'runner.log').read_bytes() == b'RUNNER_WORKLOAD_START\nRUNNER_RESULT_COMMITTED\n'
            print('STREAM_DAG_PASS')
        assert all((folder / name / 'runner-error.log').stat().st_size == 0 for name in children)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        locations = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        print('STREAM_DAG_FAILED ' + phase + ' ' + type(error).__name__ + ' ' + locations, flush=True)
        for name in children:
            for line in (folder / name / 'runner.log').read_text().splitlines():
                if re.fullmatch('RUNNER_FAILED [A-Z_]+', line):
                    print(name + ' ' + line, flush=True)
        sys.exit(1)
    finally:
        for child in children.values():
            if child.poll() is None:
                child.terminate()
        for child in children.values():
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
