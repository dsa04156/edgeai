"""Actual TLS/SQLite start admission, durable receipts, lost replies and recovery boundaries."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import http.client
import json
import socket
import sqlite3
import time
import unittest
import uuid
from urllib.parse import urlencode

import test_remote_recovery as base


class RemoteStartReceiptTest(unittest.TestCase):
    setUp = base.RemoteRecoveryTest.setUp
    start = base.RemoteRecoveryTest.start
    stop = base.RemoteRecoveryTest.stop
    request = base.RemoteRecoveryTest.request
    provider_options = base.RemoteRecoveryTest.provider_options
    rpc = base.RemoteRecoveryTest.rpc
    work = base.RemoteRecoveryTest.work
    allocate = base.RemoteRecoveryTest.allocate
    binding = base.RemoteRecoveryTest.binding
    rows = base.RemoteRecoveryTest.rows
    wait_quiescent = base.RemoteRecoveryTest.wait_quiescent

    def authority(self, item, deadline=None):
        work = json.loads(item[2])
        return {'apiVersion': 'edgeai.remote.start/v1', 'identity': work['identity'],
                'requestDigest': item[1]['X-EdgeAI-Request-Digest'], 'expiresAt': work['expiresAt'],
                'offloadId': str(uuid.uuid4()) if deadline else None, 'startDeadline': deadline}

    def receipts(self):
        with sqlite3.connect('file:' + str(self.root / 'state/allocations.sqlite') + '?mode=ro', uri=True) as db:
            return db.execute('SELECT allocation_id,authority,accepted_at FROM start_receipts ORDER BY allocation_id').fetchall()

    def start_work(self, item, authority):
        return self.rpc(item[0] + '/start', 'POST', authority, item[1], self.token)

    def receipt_path(self, item, binding):
        return '/reference/v1/recovery/allocations/' + item[0].split('/')[-1] + '/start-receipt?' + urlencode(binding)

    def wait_running(self, item):
        deadline = time.monotonic() + 3
        while True:
            status, value = self.rpc(item[0], headers=item[1], credential=self.token)
            self.assertEqual(200, status)
            if value['state'] in ('RUNNING', 'SUCCEEDED'):
                return value
            self.assertLess(time.monotonic(), deadline)
            time.sleep(.01)

    def test_concurrent_start_records_exact_authority_once_and_survives_fence_restart(self):
        item = self.allocate()
        deadline = (datetime.now(timezone.utc) + timedelta(seconds=10)).isoformat()
        authority = self.authority(item, deadline)
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: self.start_work(item, authority), range(8)))
        self.assertTrue(all(code == 200 for code, _ in results))
        self.assertEqual(1, self.rows()[0][-1])
        saved = self.receipts(); self.assertEqual(1, len(saved))
        self.assertEqual(authority, json.loads(saved[0][1]))
        self.assertLess(datetime.fromisoformat(saved[0][2]), datetime.fromisoformat(deadline))
        changed = {**authority, 'offloadId': str(uuid.uuid4())}
        self.assertEqual(409, self.start_work(item, changed)[0])
        self.assertEqual(409, self.start_work(item, None)[0])
        binding = self.binding()
        self.assertEqual(409, self.rpc(self.receipt_path(item, binding))[0])
        self.assertEqual(200, self.rpc(method='PUT', value=binding)[0]); self.wait_quiescent()
        path = self.receipt_path(item, binding)
        self.assertEqual(401, self.rpc(path, credential=self.token)[0])
        status, receipt = self.rpc(path); self.assertEqual(200, status)
        self.assertEqual(authority, receipt['authority']); self.assertEqual(saved[0][2], receipt['acceptedAt'])
        self.process.kill(); self.process.wait(timeout=5); self.start()
        self.assertEqual((200, receipt), self.rpc(path)); self.assertEqual(saved, self.receipts())
        self.assertEqual(1, self.rows()[0][-1])

    def test_wrong_work_identity_lease_transfer_and_malformed_body_cannot_start(self):
        item = self.allocate(); authority = self.authority(item)
        bad = [
            {**authority, 'identity': {**authority['identity'], 'epoch': 2}},
            {**authority, 'requestDigest': 'sha256:' + '0' * 64},
            {**authority, 'expiresAt': '2099-01-01T00:00:00Z'},
            {**authority, 'offloadId': str(uuid.uuid4())},
            {**authority, 'offloadId': 'not-a-uuid', 'startDeadline': authority['expiresAt']},
            {**authority, 'unexpected': True}, b'null', b'[]',
        ]
        for value in bad:
            self.assertIn(self.start_work(item, value)[0], (400, 409))
        self.assertEqual([], self.receipts()); self.assertEqual('ALLOCATED', self.rows()[0][4])
        self.assertEqual(0, self.rows()[0][-1])

    def delayed_start(self, item, authority):
        raw = json.dumps(authority).encode()
        sock = self.tls.wrap_socket(socket.create_connection(('127.0.0.1', self.port), timeout=3), server_hostname='localhost')
        headers = {'Host': 'localhost', 'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json',
                   'Content-Length': str(len(raw)), 'Connection': 'close', **item[1]}
        head = ('POST ' + item[0] + '/start HTTP/1.1\r\n' + ''.join(k + ': ' + v + '\r\n' for k, v in headers.items()) + '\r\n').encode()
        sock.sendall(head + raw[:1])
        return sock, raw[1:]

    def finish_delayed(self, sock, remaining):
        try:
            sock.sendall(remaining); response = http.client.HTTPResponse(sock); response.begin()
            return response.status, json.loads(response.read())
        finally:
            sock.close()

    def test_authenticated_delayed_start_is_rechecked_against_original_deadline(self):
        item = self.allocate()
        authority = self.authority(item, (datetime.now(timezone.utc) + timedelta(milliseconds=150)).isoformat())
        sock, remaining = self.delayed_start(item, authority)
        time.sleep(.2)
        self.assertEqual((409, {'code': 'START_AUTHORITY_EXPIRED'}), self.finish_delayed(sock, remaining))
        self.assertEqual([], self.receipts()); self.assertEqual(0, self.rows()[0][-1])

    def test_authenticated_delayed_start_cannot_cross_provider_recovery_fence(self):
        item = self.allocate(); authority = self.authority(item)
        sock, remaining = self.delayed_start(item, authority)
        try:
            time.sleep(.03)
            binding = self.binding(); self.assertEqual(200, self.rpc(method='PUT', value=binding)[0]); self.wait_quiescent()
            self.assertEqual((403, {'code': 'PROVIDER_FENCED'}), self.finish_delayed(sock, remaining))
        finally:
            sock.close()
        self.assertEqual([], self.receipts()); self.assertEqual(0, self.rows()[0][-1])

    def test_sql_failure_rolls_back_receipt_and_launch_and_existing_receipts_are_immutable(self):
        item = self.allocate(); authority = self.authority(item)
        database = self.root / 'state/allocations.sqlite'
        with sqlite3.connect(database) as db:
            db.execute("CREATE TRIGGER inject_launch_failure BEFORE UPDATE ON allocations WHEN NEW.state='RUNNING' BEGIN SELECT RAISE(ABORT,'Injected launch failure'); END")
        self.assertEqual(500, self.start_work(item, authority)[0])
        self.assertEqual([], self.receipts()); self.assertEqual(0, self.rows()[0][-1])
        with sqlite3.connect(database) as db:
            db.execute('DROP TRIGGER inject_launch_failure')
        self.assertEqual(200, self.start_work(item, authority)[0]); saved = self.receipts()
        with sqlite3.connect(database) as db:
            for sql in ("UPDATE start_receipts SET accepted_at='2099-01-01T00:00:00Z'", 'DELETE FROM start_receipts'):
                with self.assertRaises(sqlite3.IntegrityError):
                    db.execute(sql)
        self.assertEqual(saved, self.receipts()); self.assertEqual(1, self.rows()[0][-1])

    def test_lost_actual_start_reply_and_running_process_death_preserve_receipt_without_reexecution(self):
        item = self.allocate(delay=60000); authority = self.authority(item)
        sock, remaining = self.delayed_start(item, authority)
        try:
            sock.sendall(remaining)
            self.wait_running(item)  # A separate connection proves admission before the unconsumed reply is lost.
        finally:
            sock.close()
        saved = self.receipts(); self.assertEqual(1, len(saved))
        self.assertEqual(200, self.start_work(item, authority)[0]); self.assertEqual(1, self.rows()[0][-1])
        self.process.kill(); self.process.wait(timeout=5); self.start()
        self.assertEqual(saved, self.receipts()); self.assertEqual('PROVIDER_RESTART', self.rows()[0][6])
        self.assertEqual(409, self.start_work(item, authority)[0]); self.assertEqual(1, self.rows()[0][-1])
        binding = self.binding(); self.assertEqual(200, self.rpc(method='PUT', value=binding)[0]); self.wait_quiescent()
        self.assertEqual(authority, self.rpc(self.receipt_path(item, binding))[1]['authority'])

    def test_legacy_execution_and_database_upgrade_never_invent_start_receipts(self):
        item = self.allocate(start=True); authority = self.authority(item)
        self.wait_running(item)
        self.assertEqual(409, self.start_work(item, authority)[0]); self.assertEqual([], self.receipts())
        self.stop()
        with sqlite3.connect(self.root / 'state/allocations.sqlite') as db:
            db.execute('DROP TABLE start_receipts')
        before = self.rows(); self.start()
        self.assertEqual([], self.receipts())
        self.assertEqual(before[0][0:4], self.rows()[0][0:4]); self.assertEqual(1, self.rows()[0][-1])
        binding = self.binding(); self.assertEqual(200, self.rpc(method='PUT', value=binding)[0]); self.wait_quiescent()
        self.assertEqual((404, {'code': 'START_RECEIPT_NOT_RECORDED'}), self.rpc(self.receipt_path(item, binding)))


if __name__ == '__main__':
    unittest.main()
