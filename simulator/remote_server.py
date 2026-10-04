"""Persistent loopback reference Remote provider. Actual synthetic computation; no OCI/resource reservation."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import re
import signal
import sqlite3
import stat
import threading
import time
import uuid
from urllib.parse import parse_qs, urlsplit

LIMIT = 262144
FILE_LIMIT = 1048576  # Explicit reference-provider capability, not the platform's 256 MiB ceiling.
TERMINAL = {'SUCCEEDED', 'FAILED', 'CANCELLED'}
PORT = re.compile(r'[a-z][a-z0-9]*([._-][a-z0-9]+)*\Z')
RECOVERY_OUTPUT = re.compile(r'/reference/v1/recovery/allocations/([a-f0-9-]{36})/outputs/([a-z0-9._-]+)\Z')
RECOVERY_START = re.compile(r'/reference/v1/recovery/allocations/([a-f0-9-]{36})/start-receipt\Z')


def recovery_path(path):
    return path == '/reference/v1/recovery' or path.startswith('/reference/v1/recovery/')


class Rejected(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


def object_fields(value, fields):
    if not isinstance(value, dict) or set(value) != set(fields):
        raise Rejected(400, 'INVALID_REQUEST')
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Rejected(400, 'INVALID_REQUEST')
        result[key] = value
    return result


def decode(data):
    try:
        return json.loads(data, object_pairs_hook=unique_object, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, UnicodeError, RecursionError):
        raise Rejected(400, 'INVALID_REQUEST') from None


def instant(value):
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if parsed.tzinfo is None or parsed.utcoffset().total_seconds() != 0:
            raise ValueError()
        return parsed.timestamp()
    except (ValueError, AttributeError, OverflowError):
        raise Rejected(400, 'INVALID_REQUEST') from None


def integer(value, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise Rejected(400, 'INVALID_REQUEST')
    return value


def identity(value):
    object_fields(value, ['allocationId', 'runId', 'taskId', 'attemptId', 'epoch'])
    try:
        for key in ['allocationId', 'runId', 'taskId', 'attemptId']:
            if str(uuid.UUID(value[key])) != value[key]:
                raise ValueError()
    except (ValueError, AttributeError, TypeError):
        raise Rejected(400, 'INVALID_IDENTITY') from None
    integer(value['epoch'], 1, 9007199254740991)
    return value


def file_metadata(value):
    object_fields(value, ['port', 'bytes', 'sha256', 'mediaType'])
    if not isinstance(value['port'], str) or len(value['port']) > 100 or not PORT.fullmatch(value['port']):
        raise Rejected(400, 'INVALID_REQUEST')
    integer(value['bytes'], 0, FILE_LIMIT)
    if not isinstance(value['sha256'], str) or not re.fullmatch('[a-f0-9]{64}', value['sha256']) or value['mediaType'] != 'application/json':
        raise Rejected(400, 'INVALID_REQUEST')
    return value


class Provider:
    def __init__(self, root, token_file, fault_file=None, recovery_token_file=None):
        self.root, self.token_file = Path(root).resolve(), Path(token_file)
        self.fault_file = Path(fault_file) if fault_file else None
        self.recovery_token_file = Path(recovery_token_file) if recovery_token_file else None
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.ownership = (self.root / '.server.lock').open('a')
        fcntl.flock(self.ownership, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.lock = threading.RLock()
        self.stopping = threading.Event()
        self.workers = set()
        self.db = sqlite3.connect(self.root / 'allocations.sqlite', check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS allocations (id TEXT PRIMARY KEY, identity TEXT NOT NULL, digest TEXT, work TEXT, state TEXT NOT NULL, revision INTEGER NOT NULL, failure TEXT, outputs TEXT NOT NULL, executions INTEGER NOT NULL DEFAULT 0)')
        self.db.execute('CREATE TABLE IF NOT EXISTS recovery (singleton INTEGER PRIMARY KEY CHECK(singleton=1), provider_id TEXT NOT NULL, recovery_id TEXT)')
        self.db.execute('CREATE TABLE IF NOT EXISTS start_receipts (allocation_id TEXT PRIMARY KEY, authority TEXT NOT NULL, accepted_at TEXT NOT NULL)')
        self.db.execute("CREATE TRIGGER IF NOT EXISTS start_receipts_immutable_update BEFORE UPDATE ON start_receipts BEGIN SELECT RAISE(ABORT,'Immutable start receipt'); END")
        self.db.execute("CREATE TRIGGER IF NOT EXISTS start_receipts_immutable_delete BEFORE DELETE ON start_receipts BEGIN SELECT RAISE(ABORT,'Immutable start receipt'); END")
        self.db.execute('INSERT OR IGNORE INTO recovery VALUES (1,?,NULL)', (str(uuid.uuid4()),))
        self.db.execute("UPDATE allocations SET state='FAILED',failure='PROVIDER_RESTART',revision=revision+1,outputs='[]' WHERE state='RUNNING'")
        self.db.execute("UPDATE allocations SET state='CANCELLED',failure=NULL,revision=revision+1,outputs='[]' WHERE state='CANCELLING'")
        self.db.commit()
        self.expirer = threading.Thread(target=self.expire_loop, daemon=True)
        self.expirer.start()

    @contextmanager
    def transaction(self):
        with self.lock, self.db:
            yield

    def require_active(self):
        # Called under the same lock as admission/publication, including after HTTP body reads.
        if self.db.execute('SELECT recovery_id FROM recovery WHERE singleton=1').fetchone()[0] is not None:
            raise Rejected(403, 'PROVIDER_FENCED')

    def live_workers(self):
        # Keep a thread until is_alive() is false, not merely until its finally block begins.
        self.workers.intersection_update(worker for worker in list(self.workers) if worker.is_alive())
        return len(self.workers)

    def recovery_status(self):
        with self.transaction():
            provider_id, recovery_id = self.db.execute('SELECT provider_id,recovery_id FROM recovery WHERE singleton=1').fetchone()
            counts = dict.fromkeys(['ALLOCATED', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED'], 0)
            counts.update(self.db.execute('SELECT state,count(*) FROM allocations GROUP BY state').fetchall())
            workers = self.live_workers()
            return {'apiVersion': 'edgeai.remote.recovery/v1', 'providerId': provider_id,
                    'recoveryId': recovery_id, 'sourceMode': 'SYNTHETIC', 'fenced': recovery_id is not None,
                    'allocationCount': sum(counts.values()), 'states': counts, 'activeWorkers': workers,
                    'quiescent': recovery_id is not None and workers == 0 and
                    all(counts[state] == 0 for state in ('ALLOCATED', 'RUNNING', 'CANCELLING'))}

    def fence(self, value):
        object_fields(value, ['providerId', 'recoveryId'])
        try:
            if any(str(uuid.UUID(value[key])) != value[key] for key in value):
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise Rejected(400, 'INVALID_IDENTITY') from None
        with self.transaction():
            provider_id, recovery_id = self.db.execute('SELECT provider_id,recovery_id FROM recovery WHERE singleton=1').fetchone()
            if value['providerId'] != provider_id:
                raise Rejected(409, 'PROVIDER_IDENTITY_CONFLICT')
            if recovery_id is not None and recovery_id != value['recoveryId']:
                raise Rejected(409, 'RECOVERY_IDENTITY_CONFLICT')
            self.db.execute('UPDATE recovery SET recovery_id=? WHERE singleton=1', (value['recoveryId'],))
            self.db.execute("UPDATE allocations SET state='CANCELLED',failure=NULL,outputs='[]',revision=revision+1 WHERE state='ALLOCATED'")
            self.db.execute("UPDATE allocations SET state='CANCELLING',failure=NULL,outputs='[]',revision=revision+1 WHERE state='RUNNING'")
        return self.recovery_status()

    def recovery_inventory(self, query):
        if set(query) != {'providerId', 'recoveryId', 'after', 'limit'} or any(len(v) != 1 for v in query.values()):
            raise Rejected(400, 'INVALID_REQUEST')
        provider_id, recovery_id, after, limit = (query[k][0] for k in ('providerId', 'recoveryId', 'after', 'limit'))
        try:
            for value in (provider_id, recovery_id, *([after] if after else [])):
                if str(uuid.UUID(value)) != value:
                    raise ValueError()
            if not re.fullmatch('[1-9][0-9]{0,2}', limit) or not 1 <= int(limit) <= 100:
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise Rejected(400, 'INVALID_REQUEST') from None
        with self.transaction():
            status = self.recovery_status()
            if status['providerId'] != provider_id or status['recoveryId'] != recovery_id:
                raise Rejected(409, 'RECOVERY_IDENTITY_CONFLICT')
            if not status['quiescent']:
                raise Rejected(409, 'RECOVERY_NOT_QUIESCENT')
            # Admission is fenced and every worker has exited: application code cannot change
            # any allocation now. Page the complete frozen history, never only known DB IDs.
            rows = self.db.execute('SELECT identity,digest,work,state,revision,failure,outputs,executions '
                                   'FROM allocations WHERE id>? ORDER BY id LIMIT ?', (after, int(limit) + 1)).fetchall()
            items = [{**self.status(json.loads(row[0]), row[:7]), 'executions': row[7]} for row in rows[:int(limit)]]
            return {'apiVersion': 'edgeai.remote.recovery/v1', 'providerId': provider_id, 'recoveryId': recovery_id,
                    'allocationCount': status['allocationCount'], 'after': after,
                    'nextAfter': items[-1]['identity']['allocationId'] if len(rows) > int(limit) else None, 'items': items}

    def row(self, expected):
        value = self.db.execute('SELECT identity,digest,work,state,revision,failure,outputs FROM allocations WHERE id=?', (expected['allocationId'],)).fetchone()
        if value and json.loads(value[0]) != expected:
            raise Rejected(409, 'IDENTITY_CONFLICT')
        return value

    def recovery_output(self, allocation_id, port, query):
        if set(query) != {'providerId', 'recoveryId'} or any(len(v) != 1 for v in query.values()):
            raise Rejected(400, 'INVALID_REQUEST')
        try:
            for value in (allocation_id, query['providerId'][0], query['recoveryId'][0]):
                if str(uuid.UUID(value)) != value:
                    raise ValueError()
            if not PORT.fullmatch(port): raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise Rejected(400, 'INVALID_REQUEST') from None
        with self.transaction():
            status = self.recovery_status()
            if status['providerId'] != query['providerId'][0] or status['recoveryId'] != query['recoveryId'][0]:
                raise Rejected(409, 'RECOVERY_IDENTITY_CONFLICT')
            if not status['quiescent']:
                raise Rejected(409, 'RECOVERY_NOT_QUIESCENT')
            row = self.db.execute('SELECT state,outputs FROM allocations WHERE id=?', (allocation_id,)).fetchone()
            if row is None: raise Rejected(404, 'NOT_FOUND')
            output = next((item for item in json.loads(row[1]) if item['port'] == port), None)
            if row[0] != 'SUCCEEDED' or output is None:
                raise Rejected(409, 'OUTPUT_NOT_READY')
            descriptors = []
            try:
                # Resolve each directory from an open descriptor. Never follow a replaced parent/file symlink.
                descriptors.append(os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW))
                for component in (allocation_id, 'outputs'):
                    descriptors.append(os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptors[-1]))
                descriptor = os.open(port, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptors[-1])
                with os.fdopen(descriptor, 'rb') as source:
                    before = os.fstat(source.fileno())
                    if not stat.S_ISREG(before.st_mode) or before.st_size != output['bytes'] or not 0 <= before.st_size <= FILE_LIMIT:
                        raise Rejected(409, 'OUTPUT_UNAVAILABLE')
                    raw = source.read(FILE_LIMIT + 1)
                    after = os.fstat(source.fileno())
                if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                    raise Rejected(409, 'OUTPUT_UNAVAILABLE')
                if len(raw) != output['bytes'] or hashlib.sha256(raw).hexdigest() != output['sha256']:
                    raise Rejected(409, 'OUTPUT_UNAVAILABLE')
                return raw, output['mediaType']
            except OSError:
                raise Rejected(409, 'OUTPUT_UNAVAILABLE') from None
            finally:
                for descriptor in reversed(descriptors): os.close(descriptor)

    def status(self, expected, row=None):
        row = row or self.row(expected)
        if row is None:
            raise Rejected(404, 'NOT_FOUND')
        return {'identity': expected, 'requestDigest': row[1], 'state': row[3], 'revision': row[4], 'failureReason': row[5],
                'expiresAt': json.loads(row[2])['expiresAt'] if row[2] else None, 'sourceMode': 'SYNTHETIC', 'outputs': json.loads(row[6])}

    def update(self, expected, state, failure=None, outputs=None):
        self.db.execute('UPDATE allocations SET state=?,failure=?,outputs=?,revision=revision+1 WHERE id=?',
                        (state, failure, json.dumps(outputs or []), expected['allocationId']))

    def expire(self, expected):
        row = self.row(expected)
        if row and row[2] and row[3] not in TERMINAL and instant(json.loads(row[2])['expiresAt']) <= time.time():
            if row[3] == 'ALLOCATED':
                self.update(expected, 'FAILED', 'LEASE_EXPIRED')
            # Running computation observes the lease itself and only then acknowledges termination.
        return self.row(expected)

    def reserve(self, expected, raw, digest):
        work = object_fields(decode(raw), ['apiVersion', 'identity', 'serviceSpec', 'parameters', 'inputs', 'expiresAt'])
        if identity(work['identity']) != expected:
            raise Rejected(409, 'IDENTITY_CONFLICT')
        actual = 'sha256:' + hashlib.sha256(b'edgeai-reference-allocation-v1\n' + raw).hexdigest()
        if digest != actual:
            raise Rejected(400, 'DIGEST_MISMATCH')
        with self.transaction():
            self.require_active()
            previous = self.expire(expected)
            if previous:
                if previous[1] != digest:
                    raise Rejected(409, 'REQUEST_CONFLICT')
                return self.status(expected), False
            if work['apiVersion'] != 'edgeai.remote.reference/v1' or not isinstance(work['parameters'], dict) or not isinstance(work['serviceSpec'], dict):
                raise Rejected(400, 'INVALID_REQUEST')
            deadline = instant(work['expiresAt'])
            if deadline <= time.time() or deadline > time.time() + 86400:
                raise Rejected(409, 'LEASE_EXPIRED')
            spec = work['serviceSpec']
            if spec.get('command') != ['python3', '/opt/edgeai/examples/linear.py'] or spec.get('args', []) or spec.get('runtimeClassName') or spec.get('nodeSelector') or spec.get('tolerations'):
                raise Rejected(422, 'UNSUPPORTED_EXECUTION')
            outputs, inputs = spec.get('outputs'), spec.get('inputs', {})
            if not isinstance(outputs, dict) or set(outputs) != {'output'} or not isinstance(outputs['output'], dict) or outputs['output'].get('mediaType') != 'application/json':
                raise Rejected(422, 'UNSUPPORTED_EXECUTION')
            integer(outputs['output'].get('maxBytes'), 1, FILE_LIMIT)
            if not isinstance(inputs, dict) or set(inputs) - {'input'}:
                raise Rejected(422, 'UNSUPPORTED_EXECUTION')
            if 'input' in inputs:
                declaration = inputs['input']
                if not isinstance(declaration, dict) or declaration.get('mediaType') != 'application/json' or type(declaration.get('required', False)) is not bool:
                    raise Rejected(422, 'UNSUPPORTED_EXECUTION')
                integer(declaration.get('maxBytes'), 1, FILE_LIMIT)
            if not isinstance(work['inputs'], list) or len(work['inputs']) > 1:
                raise Rejected(422, 'UNSUPPORTED_EXECUTION')
            for item in work['inputs']:
                file_metadata(item)
                if item['port'] != 'input':
                    raise Rejected(422, 'UNSUPPORTED_EXECUTION')
                if item['port'] not in inputs or item['bytes'] > inputs[item['port']]['maxBytes']:
                    raise Rejected(400, 'INPUT_CONTRACT')
            if inputs.get('input', {}).get('required') and not work['inputs']:
                raise Rejected(400, 'INPUT_REQUIRED')
            # A simulator-only delay exercises cancellation/leases; it is never a hardware measurement.
            integer(work['parameters'].get('simulationDelayMillis', 0), 0, 60000)
            folder = self.root / expected['allocationId']
            # A crash before SQLite commit may leave directories. Reuse only real owned directories;
            # uploaded inputs are still checked against this immutable work before starting.
            for path in [folder, folder / 'inputs', folder / 'outputs']:
                path.mkdir(mode=0o700, exist_ok=True)
                if path.is_symlink() or not path.is_dir():
                    raise Rejected(503, 'PROVIDER_UNAVAILABLE')
            self.db.execute('INSERT INTO allocations(id,identity,digest,work,state,revision,outputs) VALUES (?,?,?,?,?,1,?)',
                            (expected['allocationId'], json.dumps(expected), digest, raw.decode(), 'ALLOCATED', '[]'))
            return self.status(expected), True

    def upload(self, expected, port, raw, media_type):
        with self.transaction():
            self.require_active()
            row = self.expire(expected)
            if row is None:
                raise Rejected(404, 'NOT_FOUND')
            if row[3] in TERMINAL or row[3] == 'CANCELLING':
                raise Rejected(409, 'NOT_ACTIVE')
            work = json.loads(row[2])
            declared = next((f for f in work['inputs'] if f['port'] == port), None)
            if declared is None or media_type != declared['mediaType'] or len(raw) != declared['bytes'] or hashlib.sha256(raw).hexdigest() != declared['sha256']:
                raise Rejected(400, 'INPUT_INTEGRITY')
            target = self.root / expected['allocationId'] / 'inputs' / port
            if not target.exists():
                if row[3] != 'ALLOCATED':
                    raise Rejected(409, 'ALREADY_STARTED')
                self.write_file(target, raw)
            elif target.read_bytes() != raw:
                raise Rejected(409, 'INPUT_CONFLICT')
            return self.status(expected)

    @staticmethod
    def write_file(target, raw):
        temporary = target.with_name('.' + uuid.uuid4().hex + '.part')
        try:
            with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as file:
                file.write(raw); file.flush(); os.fsync(file.fileno())
            os.replace(temporary, target)
            fd = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        finally:
            temporary.unlink(missing_ok=True)

    def recovery_start_receipt(self, allocation_id, query):
        if set(query) != {'providerId', 'recoveryId'} or any(len(v) != 1 for v in query.values()):
            raise Rejected(400, 'INVALID_REQUEST')
        try:
            if any(str(uuid.UUID(v)) != v for v in (allocation_id, query['providerId'][0], query['recoveryId'][0])):
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise Rejected(400, 'INVALID_REQUEST') from None
        with self.transaction():
            status = self.recovery_status()
            if status['providerId'] != query['providerId'][0] or status['recoveryId'] != query['recoveryId'][0]:
                raise Rejected(409, 'RECOVERY_IDENTITY_CONFLICT')
            if not status['quiescent']:
                raise Rejected(409, 'RECOVERY_NOT_QUIESCENT')
            row = self.db.execute('SELECT authority,accepted_at FROM start_receipts WHERE allocation_id=?', (allocation_id,)).fetchone()
            if row is None:
                raise Rejected(404, 'START_RECEIPT_NOT_RECORDED')
            return {'apiVersion': 'edgeai.remote.start-receipt/v1', 'providerId': status['providerId'],
                    'recoveryId': status['recoveryId'], 'authority': json.loads(row[0]), 'acceptedAt': row[1]}

    def start(self, expected, authority=None):
        with self.transaction():
            self.require_active()
            row = self.expire(expected)
            if row is None:
                raise Rejected(404, 'NOT_FOUND')
            work = json.loads(row[2]) if row[2] else None
            recorded = self.db.execute('SELECT authority FROM start_receipts WHERE allocation_id=?', (expected['allocationId'],)).fetchone()
            if authority is not None:
                object_fields(authority, ['apiVersion', 'identity', 'requestDigest', 'expiresAt', 'offloadId', 'startDeadline'])
                identity(authority['identity'])
                if (authority['apiVersion'] != 'edgeai.remote.start/v1' or authority['identity'] != expected or
                        authority['requestDigest'] != row[1] or work is None or instant(authority['expiresAt']) != instant(work['expiresAt'])):
                    raise Rejected(409, 'START_AUTHORITY_CONFLICT')
                if (authority['offloadId'] is None) != (authority['startDeadline'] is None):
                    raise Rejected(400, 'INVALID_REQUEST')
                if authority['offloadId'] is not None:
                    try:
                        if str(uuid.UUID(authority['offloadId'])) != authority['offloadId']:
                            raise ValueError()
                    except (ValueError, TypeError, AttributeError):
                        raise Rejected(400, 'INVALID_REQUEST') from None
                    instant(authority['startDeadline'])
                if recorded is not None and json.loads(recorded[0]) != authority:
                    raise Rejected(409, 'START_AUTHORITY_CONFLICT')
                if row[3] != 'ALLOCATED' and recorded is None:
                    raise Rejected(409, 'START_RECEIPT_NOT_RECORDED')
            elif recorded is not None:
                raise Rejected(409, 'START_AUTHORITY_REQUIRED')
            if row[3] in {'CANCELLED', 'CANCELLING', 'FAILED'}:
                raise Rejected(409, 'NOT_ACTIVE')
            if row[3] != 'ALLOCATED':
                return self.status(expected)
            if self.live_workers() >= 8:
                raise Rejected(503, 'CAPACITY_UNAVAILABLE')
            for f in work['inputs']:
                path = self.root / expected['allocationId'] / 'inputs' / f['port']
                if not path.is_file() or path.stat().st_size != f['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != f['sha256']:
                    raise Rejected(409, 'INCOMPLETE_INPUTS')
            if authority is not None:
                accepted_at = datetime.now(timezone.utc)
                if (instant(authority['expiresAt']) <= accepted_at.timestamp() or
                        authority['startDeadline'] is not None and instant(authority['startDeadline']) <= accepted_at.timestamp()):
                    raise Rejected(409, 'START_AUTHORITY_EXPIRED')
                self.db.execute('INSERT INTO start_receipts VALUES (?,?,?)',
                    (expected['allocationId'], json.dumps(authority, sort_keys=True, separators=(',', ':')), accepted_at.isoformat()))
            self.update(expected, 'RUNNING')
            self.db.execute('UPDATE allocations SET executions=executions+1 WHERE id=?', (expected['allocationId'],))
            # Commit before launching. A crash in this gap becomes PROVIDER_RESTART, never a duplicate launch.
            self.db.commit()
            worker = threading.Thread(target=self.compute, args=(expected, work), daemon=True)
            self.workers.add(worker)
            worker.start()
            return self.status(expected)

    def cancel(self, expected):
        with self.transaction():
            self.require_active()
            row = self.expire(expected)
            if row is None:
                self.db.execute("INSERT INTO allocations(id,identity,state,revision,outputs) VALUES (?,?,'CANCELLED',1,'[]')", (expected['allocationId'], json.dumps(expected)))
            elif row[3] == 'ALLOCATED':
                self.update(expected, 'CANCELLED')
            elif row[3] == 'RUNNING':
                self.update(expected, 'CANCELLING')
            return self.status(expected)

    def interrupted(self, expected, work):
        row = self.row(expected)
        if row[3] == 'CANCELLING':
            self.update(expected, 'CANCELLED'); return True
        if instant(work['expiresAt']) <= time.time():
            self.update(expected, 'FAILED', 'LEASE_EXPIRED'); return True
        if self.stopping.is_set():
            self.update(expected, 'FAILED', 'PROVIDER_RESTART'); return True
        return row[3] != 'RUNNING'

    def compute(self, expected, work):
        try:
            delay = time.monotonic() + work['parameters'].get('simulationDelayMillis', 0) / 1000
            while True:
                with self.transaction():
                    if self.interrupted(expected, work):
                        return
                if time.monotonic() >= delay:
                    break
                self.stopping.wait(.02)
            parameters = work['parameters']
            data = decode((self.root / expected['allocationId'] / 'inputs' / 'input').read_bytes()) if work['inputs'] else parameters
            features, weights = data['features'], parameters['weights']
            if not isinstance(features, list) or not isinstance(weights, list) or not features or len(features) != len(weights) or len(features) > 4096:
                raise ValueError()
            values = [float(v) for v in [*features, *weights, parameters.get('bias', 0)]]
            if not all(math.isfinite(v) for v in values):
                raise ValueError()
            score = sum(float(x) * float(w) for x, w in zip(features, weights)) + float(parameters.get('bias', 0))
            result = {'sourceMode': 'SYNTHETIC', 'features': features, 'score': score, 'prediction': int(score >= 0)}
            raw = json.dumps(result, allow_nan=False).encode()
            if len(raw) > work['serviceSpec']['outputs']['output']['maxBytes']:
                with self.transaction():
                    if not self.interrupted(expected, work):
                        self.update(expected, 'FAILED', 'OUTPUT_INVALID')
                return
            # Cancel/expiry and publication serialize on the same lock. No late successful overwrite.
            with self.transaction():
                if self.interrupted(expected, work):
                    return
                self.write_file(self.root / expected['allocationId'] / 'outputs' / 'output', raw)
                self.update(expected, 'SUCCEEDED', outputs=[{'port': 'output', 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(), 'mediaType': 'application/json'}])
        except Exception:
            with self.transaction():
                if not self.interrupted(expected, work):
                    self.update(expected, 'FAILED', 'WORKLOAD_FAILED')

    def expire_loop(self):
        while not self.stopping.wait(.1):
            with self.transaction():
                for (stored,) in self.db.execute("SELECT identity FROM allocations WHERE state='ALLOCATED'").fetchall():
                    self.expire(json.loads(stored))

    def close(self):
        self.stopping.set()
        for worker in list(self.workers):
            worker.join(5)
        self.expirer.join(5)
        with self.lock:
            if self.live_workers():
                raise RuntimeError('Reference computation failed to stop')
            self.db.close()
        self.ownership.close()

    def fault(self, name):
        # Optional test-owned local file; no network fault-control endpoint.
        with self.lock:
            if self.fault_file is None or not self.fault_file.exists():
                return False
            faults = decode(self.fault_file.read_bytes())
            if name not in faults:
                return False
            faults.remove(name)
            self.write_file(self.fault_file, json.dumps(faults).encode())
            return True


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass  # URLs, headers, request bodies and provider credentials never enter logs.

    def setup(self):
        super().setup()
        self.connection.settimeout(3)

    def reply(self, status, value, media_type='application/json'):
        data = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', media_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Connection', 'close')
        self.end_headers(); self.wfile.write(data); self.close_connection = True

    def authenticate(self, recovery=False):
        p = self.server.provider
        if recovery and p.recovery_token_file is None:
            raise Rejected(404, 'NOT_FOUND')
        token = (p.recovery_token_file if recovery else p.token_file).read_text().strip()
        if recovery and hmac.compare_digest(token, p.token_file.read_text().strip()):
            raise Rejected(503, 'RECOVERY_CREDENTIAL_NOT_SEPARATE')
        if not re.fullmatch('[A-Za-z0-9_-]{32,256}', token) or not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
            raise Rejected(401, 'UNAUTHORIZED')

    def handle_expect_100(self):
        try:
            recovery = recovery_path(urlsplit(self.path).path)
            self.authenticate(recovery)
            if not recovery:
                with self.server.provider.transaction():
                    self.server.provider.require_active()
            return super().handle_expect_100()
        except Rejected as error:
            self.reply(error.status, {'code': error.code})
            return False
        except Exception:
            self.reply(503, {'code': 'PROVIDER_UNAVAILABLE'})
            return False

    def recovery(self):
        self.authenticate(recovery=True)
        lengths = self.headers.get_all('Content-Length', [])
        if len(lengths) > 1 or 'Transfer-Encoding' in self.headers:
            raise Rejected(400, 'INVALID_REQUEST')
        try:
            size = int(lengths[0]) if lengths else 0
        except ValueError:
            raise Rejected(400, 'INVALID_REQUEST') from None
        if not 0 <= size <= 1024:
            raise Rejected(413, 'TOO_LARGE')
        parsed = urlsplit(self.path)
        output = RECOVERY_OUTPUT.fullmatch(parsed.path)
        start = RECOVERY_START.fullmatch(parsed.path)
        if start:
            if self.command != 'GET' or size:
                raise Rejected(405, 'METHOD_NOT_ALLOWED')
            self.reply(200, self.server.provider.recovery_start_receipt(start[1], parse_qs(parsed.query, keep_blank_values=True)))
            return
        if output:
            if self.command != 'GET' or size:
                raise Rejected(405, 'METHOD_NOT_ALLOWED')
            raw, media_type = self.server.provider.recovery_output(output[1], output[2], parse_qs(parsed.query, keep_blank_values=True))
            self.reply(200, raw, media_type)
            return
        if parsed.path == '/reference/v1/recovery/allocations':
            if self.command != 'GET' or size:
                raise Rejected(405, 'METHOD_NOT_ALLOWED')
            self.reply(200, self.server.provider.recovery_inventory(parse_qs(parsed.query, keep_blank_values=True)))
            return
        if parsed.path != '/reference/v1/recovery':
            raise Rejected(404, 'NOT_FOUND')
        if parsed.query:
            raise Rejected(400, 'INVALID_REQUEST')
        if self.command == 'GET' and size == 0:
            self.reply(200, self.server.provider.recovery_status())
        elif self.command == 'PUT' and self.headers.get('Content-Type') == 'application/json':
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise Rejected(400, 'INCOMPLETE_BODY')
            self.authenticate(recovery=True)
            self.reply(200, self.server.provider.fence(decode(raw)))
        else:
            raise Rejected(405, 'METHOD_NOT_ALLOWED')

    def dispatch(self):
        p = self.server.provider
        try:
            if recovery_path(urlsplit(self.path).path):
                self.recovery()
                return
            self.authenticate()
            with p.transaction():
                p.require_active()
            match = re.fullmatch(r'/reference/v1/allocations/([a-f0-9-]{36})(?:/(start|cancel|inputs/[a-z0-9._-]+|outputs/[a-z0-9._-]+))?', self.path)
            if not match:
                raise Rejected(404, 'NOT_FOUND')
            try:
                expected = identity({'allocationId': match[1], 'runId': self.headers.get('X-EdgeAI-Run-Id'), 'taskId': self.headers.get('X-EdgeAI-Task-Id'),
                                     'attemptId': self.headers.get('X-EdgeAI-Attempt-Id'), 'epoch': int(self.headers.get('X-EdgeAI-Epoch', ''))})
                lengths = self.headers.get_all('Content-Length', [])
                if len(lengths) > 1 or 'Transfer-Encoding' in self.headers:
                    raise ValueError()
                size = int(lengths[0]) if lengths else 0
            except (ValueError, TypeError):
                raise Rejected(400, 'INVALID_REQUEST') from None
            suffix = match[2] or ''
            bound = FILE_LIMIT if suffix.startswith('inputs/') else LIMIT
            if not 0 <= size <= bound:
                raise Rejected(413, 'TOO_LARGE')
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise Rejected(400, 'INCOMPLETE_BODY')
            if self.command == 'PUT' and not suffix:
                if self.headers.get('Content-Type') != 'application/json':
                    raise Rejected(400, 'INVALID_REQUEST')
                value, created = p.reserve(expected, raw, self.headers.get('X-EdgeAI-Request-Digest'))
                if p.fault('reserve_timeout_once'):
                    time.sleep(1)
                self.reply(201 if created else 200, value)
            elif self.command == 'PUT' and suffix.startswith('inputs/'):
                self.reply(200, p.upload(expected, suffix[7:], raw, self.headers.get('Content-Type')))
            elif self.command == 'POST' and suffix == 'start':
                if raw and self.headers.get('Content-Type') != 'application/json':
                    raise Rejected(400, 'INVALID_REQUEST')
                authority = decode(raw) if raw else None
                if raw and not isinstance(authority, dict):
                    raise Rejected(400, 'INVALID_REQUEST')
                self.reply(200, p.start(expected, authority))
            elif self.command == 'POST' and not raw and suffix == 'cancel':
                self.reply(200, p.cancel(expected))
            elif self.command == 'GET' and not raw:
                with p.transaction():
                    p.require_active()
                    row = p.expire(expected)
                    value = p.status(expected, row)
                    if not suffix:
                        self.reply(200, value)
                    elif suffix.startswith('outputs/'):
                        output = next((v for v in value['outputs'] if v['port'] == suffix[8:]), None)
                        if value['state'] != 'SUCCEEDED' or output is None:
                            raise Rejected(409, 'OUTPUT_NOT_READY')
                        self.reply(200, (p.root / expected['allocationId'] / 'outputs' / output['port']).read_bytes(), output['mediaType'])
                    else:
                        raise Rejected(404, 'NOT_FOUND')
            else:
                raise Rejected(405, 'METHOD_NOT_ALLOWED')
        except Rejected as error:
            self.reply(error.status, {'code': error.code})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except Exception:
            self.reply(500, {'code': 'PROVIDER_UNAVAILABLE'})

    do_GET = dispatch
    do_POST = dispatch
    do_PUT = dispatch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', required=True)
    parser.add_argument('--token-file', required=True)
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--ready-file', required=True, help='Private local test rendezvous file; contains only the loopback port')
    parser.add_argument('--fault-file', help='Optional private test file of one-shot faults; never a production setting')
    parser.add_argument('--recovery-token-file', help='Separate operator credential; recovery API disabled when absent')
    args = parser.parse_args()
    os.umask(0o077)
    provider = Provider(args.state_dir, args.token_file, args.fault_file, args.recovery_token_file)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.provider = provider
    server.daemon_threads = True
    ready = Path(args.ready_file)
    Provider.write_file(ready, json.dumps({'port': server.server_address[1], 'sourceMode': 'SYNTHETIC'}).encode())
    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        server.serve_forever(poll_interval=.05)
    finally:
        server.server_close(); provider.close(); ready.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
