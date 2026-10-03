"""Bounded, single-owner stream inbox/checkpoint/outbox on a persistent local volume.

This is a data-plane journal, not the control-plane DataRoute repository. MQTT PUBACK
never removes an outbox frame. Only a trusted consumer processing watermark does.
Recovery requires the same volume and exact bindings; cross-Pod restore is separate.
"""
from contextlib import contextmanager
from dataclasses import asdict, dataclass
import fcntl
import json
import os
from pathlib import Path
import sqlite3
import stat

from edgeai_runner.stream_protocol import Binding, Frame, MAX_COUNTER, decode


class JournalError(ValueError):
    pass


class Backpressure(JournalError):
    """Retry after downstream progress; nothing from this transaction was committed."""


class SequenceGap(JournalError):
    """The publisher must replay from the consumer's committed watermark."""


def require(condition, reason):
    if not condition:
        raise JournalError(reason)


@dataclass(frozen=True)
class Limits:
    max_frames: int = 128
    max_buffer_bytes: int = 16777216
    max_state_bytes: int = 262144

    def __post_init__(self):
        for value, maximum in ((self.max_frames, 4096), (self.max_buffer_bytes, 67108864),
                               (self.max_state_bytes, 1048576)):
            require(type(value) is int and 1 <= value <= maximum, 'Invalid stream journal limit')


@dataclass(frozen=True)
class Checkpoint:
    revision: int
    state: bytes
    input_sequences: dict[str, int]
    output_sequences: dict[str, int]
    ended_inputs: frozenset[str]


@dataclass(frozen=True)
class Emission:
    route_id: str
    payload: bytes
    media_type: str | None
    kind: str = 'DATA'


def manifest(inputs, outputs, limits):
    def entries(bindings):
        return [{'routeId': b.route_id, 'generation': b.generation, 'producer': b.producer.document()}
                for b in sorted(bindings, key=lambda b: b.route_id)]
    return json.dumps({'version': 1, 'inputs': entries(inputs), 'outputs': entries(outputs),
                       'limits': asdict(limits)}, sort_keys=True, separators=(',', ':'))


class Journal:
    """One owner/process/thread per private directory; create and recover are explicit."""
    def __init__(self, directory, inputs, outputs, limits=Limits(), *, create=False):
        require(type(limits) is Limits, 'Invalid stream journal limit')
        self.inputs = self._bindings(inputs)
        self.outputs = self._bindings(outputs)
        require(not self.inputs.keys() & self.outputs.keys(), 'Duplicate stream journal route')
        require(self.inputs or self.outputs, 'Stream journal requires routes')
        routes = len(self.inputs) + len(self.outputs)
        require(limits.max_frames >= routes and limits.max_buffer_bytes >= routes,
                'Stream journal requires capacity for every route')
        self.route_frames = limits.max_frames // routes
        self.route_bytes = limits.max_buffer_bytes // routes
        self.limits = limits
        self.db = None
        self.lock = None
        self.authority_guard = None
        directory = Path(directory)
        if create:
            directory.mkdir(mode=0o700)
        info = directory.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
                and stat.S_IMODE(info.st_mode) == 0o700, 'Private stream journal directory required')
        try:
            self.lock = os.open(directory / 'owner.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise JournalError('Stream journal already owned') from None
            path = directory / 'journal.sqlite'
            if create:
                fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_RDWR | os.O_NOFOLLOW, 0o600)
                os.close(fd)
            info = path.lstat()
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
                    and stat.S_IMODE(info.st_mode) == 0o600, 'Private stream journal file required')
            self.db = sqlite3.connect(path, isolation_level=None, timeout=5)
            self.db.execute('PRAGMA journal_mode=DELETE')
            self.db.execute('PRAGMA synchronous=EXTRA')
            self.db.execute('PRAGMA foreign_keys=ON')
            self.db.execute('PRAGMA trusted_schema=OFF')
            self.db.execute('PRAGMA cache_size=-2048')
            # Reuse freed pages; cap both payload and database allocation. No growing WAL.
            max_pages = (2 * limits.max_buffer_bytes + limits.max_frames * 2048
                         + 2 * limits.max_state_bytes + 1048576) // 4096 + 1
            self.db.execute(f'PRAGMA max_page_count={max_pages}')
            expected = manifest(self.inputs.values(), self.outputs.values(), limits)
            if create:
                self._initialize(expected)
                parent = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(parent)
                finally:
                    os.close(parent)
            require(self.db.execute('SELECT manifest FROM checkpoint').fetchone() == (expected,),
                    'Stream journal binding or limits changed')
        except BaseException:
            self.close()
            raise

    @staticmethod
    def _bindings(bindings):
        require(type(bindings) in (tuple, list) and len(bindings) <= 16, 'Invalid stream journal routes')
        require(all(type(b) is Binding for b in bindings), 'Invalid stream journal binding')
        result = {b.route_id: b for b in bindings}
        require(len(result) == len(bindings), 'Duplicate stream journal route')
        return result

    def _initialize(self, expected):
        with self._transaction():
            self.db.execute('CREATE TABLE checkpoint (id INTEGER PRIMARY KEY CHECK(id=1), '
                            'manifest TEXT NOT NULL, revision INTEGER NOT NULL, state BLOB NOT NULL)')
            self.db.execute('CREATE TABLE route (id TEXT PRIMARY KEY, direction TEXT NOT NULL, '
                            'received INTEGER NOT NULL DEFAULT 0, committed INTEGER NOT NULL DEFAULT 0, '
                            'ended INTEGER NOT NULL DEFAULT 0)')
            self.db.execute('CREATE TABLE frame (route_id TEXT NOT NULL REFERENCES route(id), '
                            'sequence INTEGER NOT NULL, wire BLOB NOT NULL, PRIMARY KEY(route_id, sequence))')
            self.db.execute('INSERT INTO checkpoint VALUES (1, ?, 0, ?)', (expected, b''))
            self.db.executemany('INSERT INTO route(id,direction) VALUES (?,?)',
                                [(r, 'IN') for r in self.inputs] + [(r, 'OUT') for r in self.outputs])

    @contextmanager
    def _transaction(self):
        require(self.db is not None, 'Stream journal is closed')
        if self.authority_guard is not None:
            self.authority_guard()
        self.db.execute('BEGIN IMMEDIATE')
        try:
            yield
            if self.authority_guard is not None:
                self.authority_guard()
            self.db.commit()
        except BaseException:
            if self.db.in_transaction:
                self.db.rollback()
            raise

    def close(self):
        if self.db is not None:
            self.db.close()
            self.db = None
        if self.lock is not None:
            os.close(self.lock)
            self.lock = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def _route(self, identity):
        row = self.db.execute('SELECT received,committed,ended FROM route WHERE id=?', (identity,)).fetchone()
        require(row is not None, 'Unknown stream journal route')
        return row

    def usage(self):
        return self.db.execute('SELECT count(*),coalesce(sum(length(wire)),0) FROM frame').fetchone()

    def _capacity(self):
        # Reserve both message and byte capacity for each input/output. A fast source
        # cannot fill the join's entire journal and prevent a missing input from arriving.
        rows = self.db.execute('SELECT count(*),sum(length(wire)) FROM frame GROUP BY route_id').fetchall()
        if any(count > self.route_frames or size > self.route_bytes for count, size in rows):
            raise Backpressure('Stream journal is full')

    def receive(self, frame):
        require(type(frame) is Frame and frame.binding.route_id in self.inputs, 'Unknown stream input')
        binding = self.inputs[frame.binding.route_id]
        binding.verify(frame)
        wire = frame.encode()
        with self._transaction():
            received, committed, ended = self._route(binding.route_id)
            if frame.sequence <= committed:
                # Already processed: discard without executing or updating checkpoint. Historic
                # payload hashes are deliberately not retained without bound.
                return 'COMMITTED'
            if frame.sequence <= received:
                original = self.db.execute('SELECT wire FROM frame WHERE route_id=? AND sequence=?',
                                           (binding.route_id, frame.sequence)).fetchone()
                require(original == (wire,), 'Conflicting stream replay')
                return 'BUFFERED'
            require(not ended, 'Stream input already ended')
            if frame.sequence != received + 1:
                raise SequenceGap('Missing stream sequence')
            self.db.execute('INSERT INTO frame VALUES (?,?,?)', (binding.route_id, frame.sequence, wire))
            self._capacity()
            self.db.execute('UPDATE route SET received=?,ended=? WHERE id=?',
                            (frame.sequence, int(frame.kind == 'END'), binding.route_id))
            return 'ACCEPTED'

    def checkpoint(self):
        revision, state = self.db.execute('SELECT revision,state FROM checkpoint').fetchone()
        rows = self.db.execute('SELECT id,direction,received,committed,ended FROM route').fetchall()
        return Checkpoint(revision, state, {r: c for r, d, n, c, e in rows if d == 'IN'},
                          {r: n for r, d, n, c, e in rows if d == 'OUT'},
                          frozenset(r for r, d, n, c, e in rows if d == 'IN' and e and c == n))

    def pending(self):
        """At most the first unprocessed frame per input; the workload defines join semantics."""
        rows = self.db.execute('SELECT f.wire FROM route r JOIN frame f ON f.route_id=r.id '
                               'AND f.sequence=r.committed+1 WHERE r.direction=\'IN\' ORDER BY r.id').fetchall()
        return tuple(decode(wire) for wire, in rows)

    def commit(self, revision, consumed, state, emitted=()):
        """Atomic local checkpoint + input cursors + newly numbered output frames.

        The caller must be side-effect-free until commit succeeds. External model/file/HTTP
        effects are not made exactly-once by this transaction.
        """
        require(type(revision) is int and 0 <= revision < MAX_COUNTER, 'Invalid checkpoint revision')
        require(type(state) is bytes and len(state) <= self.limits.max_state_bytes, 'Invalid checkpoint size')
        require(type(consumed) in (list, tuple) and len(consumed) <= 16, 'Invalid consumed frames')
        require(type(emitted) in (list, tuple) and len(emitted) <= 16, 'Invalid emitted frames')
        require(all(type(f) is Frame for f in consumed) and all(type(e) is Emission for e in emitted),
                'Invalid checkpoint frames')
        ids = [f.binding.route_id for f in consumed]
        require(len(set(ids)) == len(ids), 'Consume at most one frame per input')
        with self._transaction():
            current = self.checkpoint()
            require(current.revision == revision, 'Stale stream checkpoint')
            for frame in consumed:
                require(frame.binding.route_id in self.inputs, 'Unknown stream input')
                self.inputs[frame.binding.route_id].verify(frame)
                require(frame.sequence == current.input_sequences[frame.binding.route_id] + 1,
                        'Consume the next stream frame')
                row = self.db.execute('SELECT wire FROM frame WHERE route_id=? AND sequence=?',
                                      (frame.binding.route_id, frame.sequence)).fetchone()
                require(row == (frame.encode(),), 'Consumed stream frame was not received')
                self.db.execute('DELETE FROM frame WHERE route_id=? AND sequence=?',
                                (frame.binding.route_id, frame.sequence))
                self.db.execute('UPDATE route SET committed=? WHERE id=?', (frame.sequence, frame.binding.route_id))
            result = []
            for item in emitted:
                require(item.route_id in self.outputs, 'Unknown stream output')
                sequence, _, ended = self._route(item.route_id)
                require(not ended, 'Stream output already ended')
                frame = Frame(self.outputs[item.route_id], sequence + 1, item.kind, item.payload, item.media_type)
                self.db.execute('INSERT INTO frame VALUES (?,?,?)', (item.route_id, frame.sequence, frame.encode()))
                self.db.execute('UPDATE route SET received=?,ended=? WHERE id=?',
                                (frame.sequence, int(item.kind == 'END'), item.route_id))
                result.append(frame)
            self._capacity()
            self.db.execute('UPDATE checkpoint SET revision=?,state=? WHERE id=1', (revision + 1, state))
            return tuple(result)

    def outgoing(self, limit=16):
        require(type(limit) is int and 1 <= limit <= 256, 'Invalid replay limit')
        rows = self.db.execute('SELECT wire FROM (SELECT f.wire,f.route_id,'
                               'row_number() OVER (PARTITION BY f.route_id ORDER BY f.sequence) AS position '
                               'FROM frame f JOIN route r ON r.id=f.route_id WHERE r.direction=\'OUT\') '
                               'ORDER BY position,route_id LIMIT ?', (limit,)).fetchall()
        return tuple(decode(wire) for wire, in rows)

    def acknowledge(self, binding, sequence):
        """Only call for an authenticated consumer's committed processing watermark."""
        require(type(binding) is Binding and self.outputs.get(binding.route_id) == binding,
                'Stale or foreign stream acknowledgement')
        require(type(sequence) is int and 0 <= sequence <= MAX_COUNTER, 'Invalid stream acknowledgement')
        with self._transaction():
            produced, committed, _ = self._route(binding.route_id)
            require(sequence <= produced, 'Acknowledgement exceeds produced sequence')
            if sequence > committed:
                self.db.execute('DELETE FROM frame WHERE route_id=? AND sequence<=?', (binding.route_id, sequence))
                self.db.execute('UPDATE route SET committed=? WHERE id=?', (sequence, binding.route_id))
