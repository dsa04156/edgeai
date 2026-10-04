"""Bounded, single-owner stream inbox/checkpoint/outbox on a persistent local volume.

This is a data-plane journal, not the control-plane DataRoute repository. MQTT PUBACK
never removes an outbox frame. Only a trusted consumer processing watermark does.
Reopening requires the same volume and exact bindings. Portable external snapshots
and new-volume restore are implemented separately in stream_checkpoint.
Explicit same-Device-session source handover can change output generations only.
"""
from contextlib import contextmanager
from dataclasses import asdict, dataclass, replace
import fcntl
import json
import os
from pathlib import Path
import sqlite3
import stat

from edgeai_runner.stream_protocol import Binding, Frame, Producer, MAX_COUNTER, decode


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
    def __init__(self, directory, inputs, outputs, limits=Limits(), *, create=False, durability='LOCAL',
                 source_handover=False, guard=None):
        require(type(limits) is Limits, 'Invalid stream journal limit')
        require(durability in ('LOCAL', 'EXTERNAL'), 'Invalid stream durability')
        self.durability = durability
        self._clock_ready = False
        self.inputs = self._bindings(inputs)
        self.outputs = self._bindings(outputs)
        require(guard is None or callable(guard), 'Invalid stream authority guard')
        require(type(source_handover) is bool and (not source_handover or not create and durability == 'LOCAL'
                and not self.inputs and self.outputs and callable(guard)
                and len({b.producer for b in self.outputs.values()}) == 1
                and all(b.producer.kind == 'DEVICE_SESSION' for b in self.outputs.values())),
                'Explicit same-session Device source handover required')
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
        self.authority_guard = guard
        directory = Path(directory)
        self.directory = directory
        if create:
            directory.mkdir(mode=0o700)
        info = directory.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
                and stat.S_IMODE(info.st_mode) == 0o700, 'Private stream journal directory required')
        require(not os.path.lexists(directory / 'recovery.json'),
                'Stream journal recovery requires explicit activation')
        self._check_retirement()
        try:
            self.lock = os.open(directory / 'owner.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise JournalError('Stream journal already owned') from None
            # Retirement/restore may have been published after the first check
            # while this process was acquiring ownership.
            require(not os.path.lexists(directory / 'recovery.json'),
                    'Stream journal recovery requires explicit activation')
            self._check_retirement()
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
            if source_handover:
                self._handover_source(expected)
            require(self.db.execute('SELECT manifest FROM checkpoint').fetchone() == (expected,),
                    'Stream journal binding or limits changed')
            # Local format upgrade is additive. A pre-checkpoint journal can only
            # reopen in LOCAL mode; enabling external durability needs explicit restore.
            exists = self.db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='durability'").fetchone()
            require(create or exists or durability == 'LOCAL', 'Cannot promote a local journal implicitly')
            with self._transaction():
                self.db.execute('CREATE TABLE IF NOT EXISTS durability (id INTEGER PRIMARY KEY CHECK(id=1), '
                                'mode TEXT NOT NULL, serial INTEGER NOT NULL, confirmed_serial INTEGER NOT NULL, digest TEXT)')
                self.db.execute('CREATE TABLE IF NOT EXISTS frontier (route_id TEXT PRIMARY KEY REFERENCES route(id), sequence INTEGER NOT NULL)')
                self.db.execute('INSERT OR IGNORE INTO durability VALUES (1,?,0,-1,NULL)', (durability,))
                self.db.executemany('INSERT OR IGNORE INTO frontier VALUES (?,0)', [(r,) for r in (*self.inputs, *self.outputs)])
                require(self.db.execute('SELECT mode FROM durability').fetchone() == (durability,),
                        'Stream durability changed')
            self._clock_ready = True
        except BaseException:
            self.close()
            raise

    def _handover_source(self, expected):
        """Called under the exclusive directory lock and authenticated current guard."""
        row = self.db.execute('SELECT manifest FROM checkpoint').fetchone()
        require(row is not None and len(row[0]) <= 16384, 'Invalid Device source manifest')
        old = json.loads(row[0])
        previous = self._bindings([Binding(v['routeId'], v['generation'], Producer.parse(v['producer']))
                                   for v in old['outputs']])
        require(not old['inputs'] and manifest([], list(previous.values()), self.limits) == row[0]
                and previous.keys() == self.outputs.keys(), 'Device source routes or limits changed')
        for identity, binding in previous.items():
            current = self.outputs[identity]
            require(binding.producer == current.producer and binding.generation <= current.generation,
                    'Device source identity changed or generation regressed')
        require(self.db.execute('SELECT mode,confirmed_serial,digest FROM durability').fetchone() == ('LOCAL', -1, None),
                'Externally checkpointed source requires server handover')
        candidate = self.db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='snapshot_candidate'").fetchone()
        require(not candidate or self.db.execute('SELECT 1 FROM snapshot_candidate LIMIT 1').fetchone() is None,
                'Pending external checkpoint requires server handover')
        require(set(self.db.execute('SELECT id,direction FROM route')) == {(identity, 'OUT') for identity in previous},
                'Device source journal must contain only its output routes')
        self._clock_ready = True
        with self._transaction():
            if row == (expected,):
                return
            # One frame at a time, bounded by the existing per-route reservation.
            for identity, sequence, wire in self.db.execute('SELECT route_id,sequence,wire FROM frame ORDER BY route_id,sequence'):
                frame = decode(wire)
                previous[identity].verify(frame)
                require(frame.sequence == sequence, 'Device source frame index mismatch')
                rebound = replace(frame, binding=self.outputs[identity])
                self.db.execute('UPDATE frame SET wire=? WHERE route_id=? AND sequence=?', (rebound.encode(), identity, sequence))
            self._capacity()
            self.db.execute('UPDATE checkpoint SET manifest=? WHERE id=1', (expected,))

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
    def _transaction(self, *, advance=True):
        require(self.db is not None, 'Stream journal is closed')
        self._check_retirement()
        if self.authority_guard is not None:
            self.authority_guard()
        self.db.execute('BEGIN IMMEDIATE')
        try:
            before = self.db.total_changes
            yield
            if advance and self._clock_ready and self.db.total_changes != before:
                serial = self.snapshot_serial
                require(serial < MAX_COUNTER, 'Stream snapshot counter exhausted')
                self.db.execute('UPDATE durability SET serial=? WHERE id=1', (serial + 1,))
            if self.authority_guard is not None:
                self.authority_guard()
            self._check_retirement()
            self.db.commit()
        except BaseException:
            if self.db.in_transaction:
                self.db.rollback()
            raise

    def _check_retirement(self):
        require(not os.path.lexists(self.directory / 'retirement.json'),
                'Stream source journal is permanently retired')

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

    @property
    def snapshot_serial(self):
        return self.db.execute('SELECT serial FROM durability WHERE id=1').fetchone()[0]

    def processing_sequences(self):
        if self.durability == 'LOCAL':
            return self.checkpoint().input_sequences
        return dict(self.db.execute("SELECT f.route_id,f.sequence FROM frontier f JOIN route r ON r.id=f.route_id WHERE r.direction='IN'"))

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
        require(type(emitted) in (list, tuple) and len(emitted) <= 32, 'Invalid emitted frames')
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

    def outgoing(self, limit=16, *, confirmed_only=False):
        require(type(limit) is int and 1 <= limit <= 256, 'Invalid replay limit')
        guard = 'AND f.sequence<=(SELECT sequence FROM frontier WHERE route_id=r.id) ' if confirmed_only and self.durability == 'EXTERNAL' else ''
        rows = self.db.execute('SELECT wire FROM (SELECT f.wire,f.route_id,'
                               'row_number() OVER (PARTITION BY f.route_id ORDER BY f.sequence) AS position '
                               'FROM frame f JOIN route r ON r.id=f.route_id WHERE r.direction=\'OUT\' ' + guard + ') '
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
            if self.durability == 'EXTERNAL':
                confirmed = self.db.execute('SELECT sequence FROM frontier WHERE route_id=?', (binding.route_id,)).fetchone()[0]
                require(sequence <= confirmed, 'Acknowledgement exceeds external checkpoint')
            if sequence > committed:
                self.db.execute('DELETE FROM frame WHERE route_id=? AND sequence<=?', (binding.route_id, sequence))
                self.db.execute('UPDATE route SET committed=? WHERE id=?', (sequence, binding.route_id))
