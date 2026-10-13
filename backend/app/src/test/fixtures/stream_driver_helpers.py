"""Real Device SDK helpers shared by stream driver regressions."""
import time
from edgeai_runner.stream_assignment import AssignmentUnavailable
from edgeai_runner.stream_journal import Backpressure

def wait(predicate, step=lambda: None, *, timeout=60):
    end = time.monotonic() + timeout
    while True:
        assert time.monotonic() < end, 'Stream driver deadline'
        result = predicate()
        assert time.monotonic() < end, 'Stream driver deadline'
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
