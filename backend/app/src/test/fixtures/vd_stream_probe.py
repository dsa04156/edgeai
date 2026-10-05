"""Real Device SDK for Java-owned VD supervisors/child Runners; no synthetic Runner receipts."""
from contextlib import ExitStack
import json
from pathlib import Path
import sys
import time

repo = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(repo / 'runner'))
from edgeai_runner.stream_assignment import AssignmentError, AssignmentUnavailable, BindingClient
from edgeai_runner.stream_device_run import DeviceRunSource
from edgeai_runner.stream_journal import Backpressure, Emission
from edgeai_runner.stream_mqtt import MqttError
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_source import SourceError

phase = 'CONNECT'


def wait(predicate, step=lambda: None, *, timeout=60):
    end = time.monotonic() + timeout
    while True:
        assert time.monotonic() < end, 'VD stream driver deadline'
        result = predicate()
        assert time.monotonic() < end, 'VD stream driver deadline'
        if result:
            return result
        step()
        time.sleep(.01)


def discover_routes(client, run_id, count, step):
    def active():
        try:
            page = client.device_routes(run_id, timeout=1)
        except AssignmentUnavailable:
            return False
        assert page['runState'] == 'RUNNING' and page['nextOffset'] is None
        if len(page['items']) != count or not all(r['generation'] and r['generation']['state'] == 'ACTIVE' for r in page['items']):
            return False
        return [r['routeId'] for r in page['items']]
    return wait(active, step)


def emit_samples(sources, samples, step):
    # Each successful emit has already committed. A later peer's backpressure
    # must never replay that source's sample or advance its adapter cursor twice.
    pending = dict(samples)
    def emit_pending():
        for name, (emissions, state) in list(pending.items()):
            try:
                sources[name].emit(emissions, state)
            except Backpressure:
                continue
            del pending[name]
        return not pending
    wait(emit_pending, step)


def main(folder):
    global phase
    config = json.loads((folder / 'request.json').read_bytes())
    with ExitStack() as stack:
        sources, route_ids = {}, {}
        def step():
            for source in sources.values():
                source.step()

        for name, value in config['sources'].items():
            client = BindingClient(config['origin'], Producer('DEVICE_SESSION', value['sessionId'], value['epoch'], value['deviceId']),
                                   folder / (name + '.token'))
            route_ids[name] = discover_routes(client, config['runId'], 2 if config['shared'] else 1, step)
            target = folder / ('source-' + name)
            target.mkdir(mode=0o700)
            sources[name] = stack.enter_context(DeviceRunSource(client, config['runId'], route_ids[name], target, create=True, timeout=120))

        def emit(a, b):
            emit_samples(sources, {name: ([Emission(route, str(value).encode(), 'application/json') for route in route_ids[name]], str(value).encode())
                                   for name, value in (('a', a), ('b', b))}, step)

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
        emit_samples(sources, {name: ([Emission(route, b'', None, 'END') for route in route_ids[name]], None)
                               for name in sources}, step)
        wait(lambda: all(s.completed for s in sources.values()), step)
        print('VD_STREAM_RECOVERED' if config['mode'] == 'recover' else 'VD_STREAM_PASS')


if __name__ == '__main__':
    try:
        main(Path(sys.argv[1]))
    except Exception as error:
        import traceback
        locations = ','.join(Path(f.filename).name + ':' + str(f.lineno) for f in traceback.extract_tb(error.__traceback__))
        mqtt_reason = {
            'MQTT connection rejected': 'MQTT_CONNECT_REJECTED',
            'MQTT subscription rejected': 'MQTT_SUBSCRIBE_REJECTED',
            'MQTT publication rejected': 'MQTT_PUBLISH_REJECTED',
            'MQTT subscription failed': 'MQTT_SUBSCRIBE_FAILED',
            'MQTT TLS verification failed': 'MQTT_TLS_FAILED',
        }.get(str(error), 'MQTT_OTHER') if isinstance(error, MqttError) else 'NONE'
        print('VD_STREAM_FAILED ' + phase + ' ' + type(error).__name__ + ' ' + mqtt_reason + ' ' + locations, flush=True)
        sys.exit(1)
