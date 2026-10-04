"""Real TLS/process recovery fencing, late accepted bodies, durable history and CLI verification."""
from datetime import datetime, timedelta, timezone
import hashlib
import http.client
import json
import os
import secrets
import socket
import sqlite3
import ssl
import subprocess
import sys
import threading
import time
import unittest
import uuid

import test_tls_fixture as fixture
sys.path.insert(0, str(fixture.ROOT / 'simulator'))
import remote_server


class RemoteRecoveryTest(unittest.TestCase):
    setUp = fixture.TlsFixtureTest.setUp
    start = fixture.TlsFixtureTest.start
    stop = fixture.TlsFixtureTest.stop
    request = fixture.TlsFixtureTest.request

    def provider_options(self):
        if not hasattr(self, 'operator'):
            self.operator = secrets.token_urlsafe(32)
            (self.root / 'operator').write_text(self.operator)
            (self.root / 'operator').chmod(0o600)
        return ['--recovery-token-file', str(self.root / 'operator')]

    def rpc(self, path='/reference/v1/recovery', method='GET', value=None, headers=None, credential=None):
        body = value if isinstance(value, bytes) else json.dumps(value).encode() if value is not None else None
        connection = http.client.HTTPSConnection('127.0.0.1', self.port, context=self.tls, timeout=3)
        try:
            connection.request(method, path, body=body, headers={
                'Authorization': 'Bearer ' + (credential or self.operator), 'Content-Type': 'application/json', **(headers or {})})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def work(self, delay=0, inputs=False, bad=False):
        identity = {name: str(uuid.uuid4()) for name in ('allocationId', 'runId', 'taskId', 'attemptId')}
        identity['epoch'] = 1
        data = b'{"features":[2,3]}'
        value = {'apiVersion': 'edgeai.remote.reference/v1', 'identity': identity,
                 'serviceSpec': {'command': ['python3', '/opt/edgeai/examples/linear.py'],
                                 'outputs': {'output': {'mediaType': 'application/json', 'maxBytes': 4096}},
                                 'inputs': {'input': {'mediaType': 'application/json', 'maxBytes': 4096, 'required': True}} if inputs else {}},
                 'parameters': {'features': [] if bad else [2, 3], 'weights': [4, 5], 'simulationDelayMillis': delay},
                 'inputs': [{'port': 'input', 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'mediaType': 'application/json'}] if inputs else [],
                 'expiresAt': (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat()}
        raw = json.dumps(value).encode()
        headers = {'X-EdgeAI-Run-Id': identity['runId'], 'X-EdgeAI-Task-Id': identity['taskId'],
                   'X-EdgeAI-Attempt-Id': identity['attemptId'], 'X-EdgeAI-Epoch': '1',
                   'X-EdgeAI-Request-Digest': 'sha256:' + hashlib.sha256(b'edgeai-reference-allocation-v1\n' + raw).hexdigest()}
        return '/reference/v1/allocations/' + identity['allocationId'], headers, raw, data

    def allocate(self, delay=0, inputs=False, start=False, bad=False):
        path, headers, raw, data = self.work(delay, inputs, bad)
        self.assertEqual(201, self.rpc(path, 'PUT', raw, headers, self.token)[0])
        if inputs:
            self.assertEqual(200, self.rpc(path + '/inputs/input', 'PUT', data, headers, self.token)[0])
        if start:
            self.assertEqual(200, self.rpc(path + '/start', 'POST', None, headers, self.token)[0])
        return path, headers, raw, data

    def binding(self):
        status, before = self.rpc()
        self.assertEqual(200, status)
        return {'providerId': before['providerId'], 'recoveryId': str(uuid.uuid4())}

    def rows(self):
        with sqlite3.connect('file:' + str(self.root / 'state/allocations.sqlite') + '?mode=ro', uri=True) as db:
            return db.execute('SELECT id,identity,digest,work,state,revision,failure,outputs,executions FROM allocations ORDER BY id').fetchall()

    def wait_quiescent(self):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            status, value = self.rpc()
            self.assertEqual(200, status)
            if value['quiescent']:
                return value
            time.sleep(.01)
        self.fail('Provider did not become quiescent')

    def cli(self, binding=None, extra=(), expected=0):
        output = self.root / ('report-' + uuid.uuid4().hex)
        pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((self.root / 'cert.pem').read_text())).hexdigest()
        command = [sys.executable, str(fixture.ROOT / 'scripts/recovery_remote_fence.py'),
                   '--endpoint', 'https://127.0.0.1:' + str(self.port), '--ca-file', str(self.root / 'cert.pem'),
                   '--certificate-sha256', pin, '--recovery-token-file', str(self.root / 'operator'), '--output', str(output)]
        if binding:
            command += ['--provider-id', binding['providerId'], '--recovery-id', binding['recoveryId'],
                        '--controller-token-file', str(self.root / 'token')]
        else:
            command += ['--inspect']
        completed = subprocess.run(command + list(extra), capture_output=True, timeout=15)
        self.assertEqual(expected, completed.returncode, 'CLI outcome differed; private output suppressed')
        self.assertNotIn(self.token.encode(), completed.stdout + completed.stderr)
        self.assertNotIn(self.operator.encode(), completed.stdout + completed.stderr)
        report_file = output / 'fence-report.json'
        self.assertEqual(0o600, report_file.stat().st_mode & 0o777)
        report = json.loads(report_file.read_text())
        self.assertFalse(report['activated'])
        self.assertFalse(report['globalQuiescenceProven'])
        return report

    def test_separate_credentials_identity_tls_pin_and_inspection(self):
        binding = self.binding()
        self.assertEqual(401, self.rpc(credential=self.token)[0])
        self.assertEqual(401, self.rpc('/', credential=self.operator)[0])
        self.assertEqual('SOURCE_REMOTE_INSPECTED', self.cli()['status'])
        wrong = {**binding, 'providerId': str(uuid.uuid4())}
        self.assertEqual(409, self.rpc(method='PUT', value=wrong)[0])
        self.cli(wrong, expected=2)
        self.cli(binding, ['--certificate-sha256', '0' * 64], expected=2)
        self.cli(binding, ['--ca-file', ssl.get_default_verify_paths().cafile], expected=2)
        self.cli(binding, ['--controller-token-file', str(self.root / 'operator')], expected=1)
        self.assertFalse(self.rpc()[1]['fenced'])
        self.assertEqual([], self.rows())

    def test_all_allocations_stop_and_completed_files_survive_sigkill_restart(self):
        done = self.allocate(start=True)
        deadline = time.monotonic() + 3
        while self.rpc(done[0], headers=done[1], credential=self.token)[1]['state'] != 'SUCCEEDED':
            self.assertLess(time.monotonic(), deadline)
            time.sleep(.01)
        completed_row = next(row for row in self.rows() if row[0] == done[0].split('/')[-1])
        output = self.root / 'state' / completed_row[0] / 'outputs/output'
        saved = output.read_bytes()
        failed = self.allocate(start=True, bad=True)
        deadline = time.monotonic() + 3
        while self.rpc(failed[0], headers=failed[1], credential=self.token)[1]['state'] != 'FAILED':
            self.assertLess(time.monotonic(), deadline); time.sleep(.01)
        failed_row = next(row for row in self.rows() if row[0] == failed[0].split('/')[-1])
        running = self.allocate(delay=60000, inputs=True, start=True)
        allocated = self.allocate()
        unknown = self.work()
        self.assertEqual(200, self.rpc(unknown[0] + '/cancel', 'POST', headers=unknown[1], credential=self.token)[0])
        self.assertEqual(1, self.rpc()[1]['activeWorkers'])
        binding = self.binding()
        report = self.cli(binding)
        self.assertTrue(report['workersStopped'])
        self.assertTrue(report['controllerRejected'])
        self.assertEqual(5, report['observed']['allocationCount'])
        self.assertEqual(3, report['observed']['states']['CANCELLED'])
        after = self.rows()
        self.assertIn(completed_row, after)
        self.assertIn(failed_row, after)
        self.assertEqual(saved, output.read_bytes())
        self.assertEqual(3, sum(row[8] for row in after))
        for path, headers, raw, _ in (done, running, allocated, unknown):
            for suffix, method, body in (('', 'PUT', raw), ('/start', 'POST', None), ('/cancel', 'POST', None), ('', 'GET', None)):
                self.assertEqual((403, {'code': 'PROVIDER_FENCED'}), self.rpc(path + suffix, method, body, headers, self.token))
        self.process.kill(); self.process.wait(timeout=5); self.start()
        resumed = self.cli(binding)
        self.assertEqual(report['observed'], resumed['observed'])
        self.assertEqual(after, self.rows())
        self.assertEqual(saved, output.read_bytes())
        self.cli({**binding, 'recoveryId': str(uuid.uuid4())}, expected=2)

    def accepted_body(self, path, headers, body):
        channel = self.tls.wrap_socket(socket.create_connection(('127.0.0.1', self.port), timeout=3), server_hostname='127.0.0.1')
        self.addCleanup(channel.close)
        lines = ['PUT ' + path + ' HTTP/1.1', 'Host: localhost', 'Authorization: Bearer ' + self.token,
                 'Content-Type: application/json', 'Content-Length: ' + str(len(body)), 'Expect: 100-continue',
                 *[key + ': ' + value for key, value in headers.items()]]
        channel.sendall(('\r\n'.join(lines) + '\r\n\r\n').encode())
        interim = b''
        while not interim.endswith(b'\r\n\r\n'):
            interim += channel.recv(1)
        self.assertEqual(b'HTTP/1.1 100 Continue\r\n\r\n', interim)
        return channel

    def test_previously_authenticated_reserve_and_upload_cannot_write_after_fence(self):
        pending = self.work()
        upload = self.work(inputs=True)
        self.assertEqual(201, self.rpc(upload[0], 'PUT', upload[2], upload[1], self.token)[0])
        sockets = [(self.accepted_body(pending[0], pending[1], pending[2]), pending[2]),
                   (self.accepted_body(upload[0] + '/inputs/input', upload[1], upload[3]), upload[3])]
        self.assertEqual(200, self.rpc(method='PUT', value=self.binding())[0])
        for channel, body in sockets:
            channel.sendall(body)
            response = http.client.HTTPResponse(channel); response.begin()
            self.assertEqual(403, response.status)
            self.assertEqual({'code': 'PROVIDER_FENCED'}, json.loads(response.read()))
            response.close(); channel.close()
        self.assertEqual(1, self.wait_quiescent()['allocationCount'])
        self.assertFalse((self.root / 'state' / pending[0].split('/')[-1]).exists())
        self.assertFalse((self.root / 'state' / upload[0].split('/')[-1] / 'inputs/input').exists())
        self.assertEqual(0, self.rows()[0][8])

    def test_lost_fence_reply_can_resume_same_identity_and_token_rotation(self):
        self.allocate(delay=60000, start=True)
        binding = self.binding()
        connection = http.client.HTTPSConnection('127.0.0.1', self.port, context=self.tls, timeout=3)
        connection.request('PUT', '/reference/v1/recovery', json.dumps(binding).encode(),
                           {'Authorization': 'Bearer ' + self.operator, 'Content-Type': 'application/json'})
        connection.close()  # The caller deliberately never reads the mutation acknowledgement.
        deadline = time.monotonic() + 3
        while not self.rpc()[1]['fenced']:
            self.assertLess(time.monotonic(), deadline); time.sleep(.01)
        old = self.operator
        self.operator = secrets.token_urlsafe(32)
        (self.root / 'operator').write_text(self.operator)
        self.assertEqual(401, self.rpc(credential=old)[0])
        self.assertEqual('SOURCE_REMOTE_FENCED', self.cli(binding)['status'])

    def test_same_credential_and_malformed_recovery_body_cannot_mutate(self):
        for value in ({}, {'providerId': 'bad', 'recoveryId': 'bad'}, b'{"providerId":1,"providerId":2}'):
            self.assertEqual(400, self.rpc(method='PUT', value=value)[0])
        (self.root / 'operator').write_text(self.token)
        self.assertEqual(503, self.rpc(credential=self.token)[0])
        (self.root / 'operator').write_text(self.operator)
        self.assertFalse(self.rpc()[1]['fenced'])

    def test_existing_sqlite_without_recovery_table_upgrades_without_changing_history(self):
        existing = self.allocate(inputs=True)
        before = self.rows()
        folder = self.root / 'state' / existing[0].split('/')[-1]
        self.stop()
        # Reproduce the exact previous database layout: allocations exists, recovery did not.
        with sqlite3.connect(self.root / 'state/allocations.sqlite') as db:
            db.execute('DROP TABLE recovery')
        self.start()
        self.assertEqual(before, self.rows())
        self.assertEqual(existing[3], (folder / 'inputs/input').read_bytes())
        identity = self.binding()
        self.stop(); self.start()
        self.assertEqual(identity['providerId'], self.binding()['providerId'])
        self.assertTrue(self.cli(identity)['workersStopped'])

    def test_blocked_worker_is_not_reported_stopped_until_actual_thread_exit(self):
        self.stop()
        provider = remote_server.Provider(self.root / 'held-state', self.root / 'token',
                                          recovery_token_file=self.root / 'operator')
        release = threading.Event()
        entered = threading.Event()
        finished = threading.Event()
        exit_thread = threading.Event()
        compute = provider.compute
        def held_compute(*args):
            entered.set()
            release.wait(20)
            compute(*args)
            finished.set()
            exit_thread.wait(10)
        provider.compute = held_compute
        server = remote_server.ThreadingHTTPServer(('127.0.0.1', self.port), remote_server.Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.root / 'cert.pem', self.root / 'key.pem')
        server.socket = context.wrap_socket(server.socket, server_side=True)
        server.provider = provider
        server.daemon_threads = True
        serving = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .02})
        serving.start()
        try:
            self.allocate(start=True)
            self.assertTrue(entered.wait(3))
            binding = self.binding()
            report = self.cli(binding, ['--timeout', '10'], expected=2)
            self.assertFalse(report['workersStopped'])
            self.assertTrue(report['fenceRetained'])
            self.assertEqual(1, report['observed']['activeWorkers'])
            self.assertEqual(1, report['observed']['states']['CANCELLING'])
            with provider.lock:
                original_workers = list(provider.workers)
            self.assertTrue(any(worker.is_alive() for worker in original_workers))
            release.set()
            self.assertTrue(finished.wait(3))
            stopped_work = self.rpc()[1]
            self.assertEqual(1, stopped_work['states']['CANCELLED'])
            self.assertEqual(1, stopped_work['activeWorkers'])
            self.assertFalse(stopped_work['quiescent'])
            exit_thread.set()
            self.assertTrue(self.cli(binding)['workersStopped'])
            self.assertTrue(all(not worker.is_alive() for worker in original_workers))
        finally:
            release.set(); exit_thread.set(); server.shutdown(); serving.join(3); server.server_close(); provider.close()


if __name__ == '__main__':
    unittest.main()
