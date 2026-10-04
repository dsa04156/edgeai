"""Bounded, open-loop management traffic; no credentials or response bodies in metrics."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
import http.client
import json
import math
import threading
import time


@dataclass(frozen=True)
class Sample:
    operation: str
    scheduled: float
    started: float
    ended: float
    status: int
    ok: bool
    error: str | None = None

    def public(self, origin):
        return {'operation': self.operation, 'scheduledSeconds': self.scheduled-origin,
                'dispatchDelayMs': max(0, (self.started-self.scheduled)*1000),
                'requestMs': (self.ended-self.started)*1000,
                'scheduledLatencyMs': (self.ended-self.scheduled)*1000,
                'status': self.status, 'ok': self.ok, 'error': self.error}


def percentile(values, q):
    if not values:
        return None
    values = sorted(values)
    return values[max(0, math.ceil(q*len(values))-1)]


def summarize(samples, start, window):
    sent = [s for s in samples if s.error != 'QUEUE_FULL']
    return {'offered': len(samples), 'sent': len(sent), 'succeeded': sum(s.ok for s in sent),
            'unexpected': sum(not s.ok for s in sent), 'dropped': len(samples)-len(sent),
            'offeredRps': len(samples)/window,
            'completedRps': sum(s.ok for s in sent)/max(window, max((s.ended-start for s in sent), default=window)),
            'requestMs': {k: percentile([s.ended*1000-s.started*1000 for s in sent], q)
                          for k, q in [('p50', .5), ('p95', .95), ('p99', .99)]},
            'scheduledLatencyMs': {k: percentile([(s.ended-s.scheduled)*1000 for s in sent], q)
                                   for k, q in [('p50', .5), ('p95', .95), ('p99', .99)]},
            'maxDispatchDelayMs': max((max(0, (s.started-s.scheduled)*1000) for s in sent), default=None)}


class Client:
    def __init__(self, port, authorization, csrf='', cookie='', timeout=5):
        self.port, self.authorization, self.csrf, self.cookie, self.timeout = port, authorization, csrf, cookie, timeout
        self.local = threading.local()
        self.connections, self.lock = [], threading.Lock()

    def request(self, method, path, body=None):
        # Recycle idle connections before sending. This never replays a failed request.
        if hasattr(self.local, 'connection') and time.monotonic()-self.local.last_used >= 5:
            self.local.connection.close()
            del self.local.connection
        if not hasattr(self.local, 'connection'):
            self.local.connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=self.timeout)
            self.local.last_used = time.monotonic()
            with self.lock:
                self.connections.append(self.local.connection)
        headers = {'Authorization': self.authorization}
        if self.csrf:
            headers['X-CSRF-TOKEN'] = self.csrf
        if self.cookie:
            headers['Cookie'] = self.cookie
        data = None if body is None else json.dumps(body, separators=(',', ':')).encode()
        if data is not None:
            headers['Content-Type'] = 'application/json'
        try:
            self.local.connection.request(method, '/api/v1/'+path, data, headers)
            response = self.local.connection.getresponse()
            raw = response.read(1048577)
            assert len(raw) <= 1048576, 'Management response exceeds fixture limit'
            self.local.last_used = time.monotonic()
            return response.status, json.loads(raw), response.getheaders()
        except Exception:
            self.local.connection.close()
            del self.local.connection
            raise

    def expect(self, method, path, body=None, status=200):
        actual, value, headers = self.request(method, path, body)
        if actual != status:
            raise AssertionError('Management HTTP status '+str(actual)+'; expected '+str(status))
        return value

    def close(self):
        for connection in self.connections:
            connection.close()


def observation(session, sequence):
    return {'sessionId': session, 'sequence': sequence,
            'observedAt': datetime.now(timezone.utc).isoformat(), 'status': 'ONLINE',
            'attributes': {'source': 'management-load', 'sequence': sequence}}


def run_window(client, devices, seconds, interval, workers=32):
    """Fixed deadlines include queue/dispatch delay; a full queue is a failed offered request."""
    cycles = round(seconds/interval)
    assert cycles >= 1 and math.isclose(cycles*interval, seconds)
    capacity = threading.BoundedSemaphore(workers*2)
    samples, futures = [], []
    start = time.monotonic()+.1
    bases = [d['sequence'] for d in devices]

    def send(operation, scheduled, device, sequence):
        began = time.monotonic()
        status, ok, error = 0, False, None
        try:
            path = 'devices/'+device['id']
            if operation == 'observation':
                body = observation(device['session']['id'], sequence)
                status, value, _ = client.request('POST', path+'/observations', body)
                ok = status == 201 and value.get('sequence') == sequence and value.get('sessionId') == device['session']['id']
                if ok:
                    device['sequence'], device['lastBody'] = sequence, body
                    device['observationCount'] += 1
            else:
                status, value, _ = client.request('GET', path)
                ok = status == 200 and value.get('device', {}).get('id') == device['id']
        except (OSError, http.client.HTTPException, ValueError, AssertionError) as exc:
            error = type(exc).__name__
        finally:
            ended = time.monotonic()
            capacity.release()
        return Sample(operation, scheduled, began, ended, status, ok, error)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        for cycle in range(cycles):
            for index, device in enumerate(devices):
                scheduled = start+cycle*interval+index*interval/len(devices)
                delay = scheduled-time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                for operation in (('observation', 'detail') if index % 10 == 0 else ('observation',)):
                    if capacity.acquire(blocking=False):
                        futures.append(executor.submit(send, operation, scheduled, device, bases[index]+cycle+1))
                    else:
                        now = time.monotonic()
                        samples.append(Sample(operation, scheduled, now, now, 0, False, 'QUEUE_FULL'))
        delay = start+seconds-time.monotonic()
        if delay > 0:
            time.sleep(delay)
        samples.extend(f.result() for f in futures)
    samples.sort(key=lambda s: (s.scheduled, s.operation))
    return {'windowSeconds': seconds, 'devices': len(devices), 'observationIntervalSeconds': interval,
            'workerLimit': workers, 'pendingLimit': workers*2,
            'summary': summarize(samples, start, seconds),
            'operations': {name: summarize([s for s in samples if s.operation == name], start, seconds)
                           for name in ('observation', 'detail')},
            'samples': [s.public(start) for s in samples]}
