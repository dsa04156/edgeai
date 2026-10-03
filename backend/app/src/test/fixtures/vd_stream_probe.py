"""Real Device SDK for Java-owned VD supervisors/child Runners; no synthetic Runner receipts."""
from contextlib import ExitStack
import json
from pathlib import Path
import sys
import time

repo = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(repo / 'runner'))
from edgeai_runner.stream_assignment import AssignmentError, BindingClient
from edgeai_runner.stream_device_run import DeviceRunSource
from edgeai_runner.stream_journal import Emission
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_source import SourceError

folder = Path(sys.argv[1])
config = json.loads((folder / 'request.json').read_bytes())
phase = 'CONNECT'


def wait(predicate, step=lambda: None):
    end = time.monotonic() + 60
    while not predicate():
        assert time.monotonic() < end, 'VD stream driver deadline'
        step()
        time.sleep(.01)


def main():
    global phase
    with ExitStack() as stack:
        sources, route_ids = {}, {}
        for name, value in config['sources'].items():
            client = BindingClient(config['origin'], Producer('DEVICE_SESSION', value['sessionId'], value['epoch'], value['deviceId']),
                                   folder / (name + '.token'))
            def active():
                page = client.device_routes(config['runId'])
                assert page['runState'] == 'RUNNING' and page['nextOffset'] is None
                if len(page['items']) != (2 if config['shared'] else 1):
                    return False
                if not all(r['generation'] and r['generation']['state'] == 'ACTIVE' for r in page['items']):
                    return False
                route_ids[name] = [r['routeId'] for r in page['items']]
                return True
            wait(active)
            target = folder / ('source-' + name)
            target.mkdir(mode=0o700)
            sources[name] = stack.enter_context(DeviceRunSource(client, config['runId'], route_ids[name], target, create=True, timeout=120))

        def step():
            for source in sources.values():
                source.step()

        def emit(a, b):
            for name, value in (('a', a), ('b', b)):
                sources[name].emit([Emission(route, str(value).encode(), 'application/json') for route in route_ids[name]], str(value).encode())

        phase = 'FIRST'
        wait(lambda: all(s.ready for s in sources.values()), step)
        emit(4, 5)
        (folder / 'first-sent').touch()
        if config['mode'] == 'cancel':
            phase = 'CANCEL'
            wait(lambda: (folder / 'cancelled').exists(), step)
            def cancelled_step():
                for source in sources.values():
                    if not source.closed:
                        try:
                            source.step()
                        except (AssignmentError, SourceError):
                            pass
                    assert not source.completed
            wait(lambda: all(s.closed for s in sources.values()), cancelled_step)
            print('VD_STREAM_CANCELLED')
            return
        if config['mode'] == 'recover':
            phase = 'RECOVER'
            owners = dict(sources)
            cursors = {name: s.checkpoint() for name, s in sources.items()}
            wait(lambda: (folder / 'fault-injected').exists(), step)
            wait(lambda: (folder / 'restored').exists() and all(s.ready and s.connections == 2 for s in sources.values()), step)
            for name, source in sources.items():
                assert source is owners[name] and source.checkpoint() == cursors[name]
        else:
            wait(lambda: (folder / 'first-verified').exists(), step)
        phase = 'SECOND'
        emit(2, 3)
        wait(lambda: (folder / 'second-verified').exists(), step)
        phase = 'COMPLETE'
        for name, source in sources.items():
            source.emit([Emission(route, b'', None, 'END') for route in route_ids[name]])
        wait(lambda: all(s.completed for s in sources.values()), step)
        print('VD_STREAM_RECOVERED' if config['mode'] == 'recover' else 'VD_STREAM_PASS')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import traceback
        locations = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        print('VD_STREAM_FAILED ' + phase + ' ' + type(error).__name__ + ' ' + locations, flush=True)
        sys.exit(1)
