"""Portable bounded stream snapshots and external-confirmation publication frontier.

Storage verification and producer fencing belong to the control plane. Call confirm
only after its authenticated receipt; a successful S3 PUT is not that receipt.
No downloaded database, SQL, pickle, credentials or signed URLs enter this format.
"""
import base64
from dataclasses import dataclass, field
import hashlib
import hmac
import json
import os
import re

from edgeai_runner.stream_journal import Journal, Limits, manifest, require
from edgeai_runner.stream_protocol import Binding, Producer, MAX_COUNTER, decode, _unique_object, _invalid_constant

VERSION = 'edgeai.stream-checkpoint/v1'
MEDIA_TYPE = 'application/vnd.edgeai.stream-checkpoint+json'
MAX_BYTES = 72 * 1024 * 1024


def digest(value):
    require(type(value) is str and re.fullmatch('[a-f0-9]{64}', value), 'Invalid checkpoint digest')
    return value


def counter(value):
    require(type(value) is int and 0 <= value <= MAX_COUNTER, 'Invalid checkpoint counter')


def encode(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('ascii')


def state_bytes(value, maximum):
    require(type(value) is str and len(value) <= 4 * ((maximum + 2) // 3), 'Invalid checkpoint state')
    try:
        result = base64.b64decode(value, validate=True)
    except ValueError:
        raise ValueError('Invalid checkpoint state') from None
    require(len(result) <= maximum and base64.b64encode(result).decode('ascii') == value, 'Invalid checkpoint state')
    return result


def validate(wire):
    require(type(wire) is bytes and 0 < len(wire) <= MAX_BYTES, 'Invalid checkpoint size')
    try:
        value = json.loads(wire, object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
    except (ValueError, RecursionError, UnicodeError):
        raise ValueError('Invalid checkpoint JSON') from None
    require(type(value) is dict and set(value) == {'apiVersion','serial','revision','stateBase64','executionSha256','manifest','routes'},
            'Invalid checkpoint fields')
    require(value['apiVersion'] == VERSION, 'Invalid checkpoint version')
    counter(value['serial']); counter(value['revision']); digest(value['executionSha256'])
    require(value['revision'] <= value['serial'], 'Invalid checkpoint revision')
    config = value['manifest']
    require(type(config) is dict and set(config) == {'version','inputs','outputs','limits'} and type(config['version']) is int and config['version'] == 1,
            'Invalid checkpoint manifest')
    limits_doc = config['limits']
    require(type(limits_doc) is dict and set(limits_doc) == {'max_frames','max_buffer_bytes','max_state_bytes'}, 'Invalid checkpoint limits')
    limits = Limits(**limits_doc)
    bindings = {}
    directions = {}
    for direction in ('inputs', 'outputs'):
        entries = config[direction]
        require(type(entries) is list and len(entries) <= 16, 'Invalid checkpoint routes')
        for entry in entries:
            require(type(entry) is dict and set(entry) == {'routeId','generation','producer'}, 'Invalid checkpoint binding')
            binding = Binding(entry['routeId'], entry['generation'], Producer.parse(entry['producer']))
            require(binding.route_id not in bindings, 'Duplicate checkpoint route')
            bindings[binding.route_id] = binding; directions[binding.route_id] = direction
    require(bindings and limits.max_frames >= len(bindings) and limits.max_buffer_bytes >= len(bindings), 'Invalid checkpoint capacity')
    state_bytes(value['stateBase64'], limits.max_state_bytes)
    rows = value['routes']
    require(type(rows) is list and len(rows) == len(bindings), 'Invalid checkpoint route state')
    seen = set()
    for row in rows:
        require(type(row) is dict and set(row) == {'routeId','received','committed','ended','frames'}, 'Invalid checkpoint route state')
        identity = row['routeId']
        require(type(identity) is str and identity in bindings and identity not in seen, 'Invalid checkpoint route state')
        seen.add(identity)
        received, committed = row['received'], row['committed']
        counter(received); counter(committed)
        require(committed <= received and type(row['ended']) is bool and (not row['ended'] or received > 0), 'Invalid checkpoint cursors')
        frames = row['frames']
        require(type(frames) is list and len(frames) == received - committed and len(frames) <= limits.max_frames // len(bindings),
                'Invalid checkpoint frame count')
        size = 0
        for sequence, frame_doc in enumerate(frames, committed + 1):
            frame = decode(encode(frame_doc)); bindings[identity].verify(frame)
            require(frame.sequence == sequence, 'Invalid checkpoint sequence')
            require((frame.kind == 'END') == (row['ended'] and sequence == received), 'Invalid checkpoint end marker')
            size += len(frame.encode())
        require(size <= limits.max_buffer_bytes // len(bindings), 'Checkpoint exceeds route capacity')
    # Canonical bytes prevent ambiguous digest identities across parse/restore/export.
    require(encode(value) == wire, 'Noncanonical checkpoint encoding')
    return value


@dataclass(frozen=True)
class Snapshot:
    wire: bytes = field(repr=False)

    def __post_init__(self):
        validate(self.wire)

    @property
    def sha256(self):
        return hashlib.sha256(self.wire).hexdigest()

    @property
    def serial(self):
        return self.document()['serial']

    def document(self):
        # Return a fresh document; callers cannot mutate the authoritative bytes.
        return json.loads(self.wire)


def capture(journal, execution_sha256):
    """Keep one candidate until confirmed; return None when nothing changed."""
    digest(execution_sha256)
    require(journal.durability == 'EXTERNAL', 'External checkpoint requires external durability')
    with journal._transaction(advance=False):
        journal.db.execute('CREATE TABLE IF NOT EXISTS snapshot_candidate (id INTEGER PRIMARY KEY CHECK(id=1), wire BLOB NOT NULL)')
        previous = journal.db.execute('SELECT wire FROM snapshot_candidate WHERE id=1').fetchone()
        if previous:
            snapshot = Snapshot(previous[0])
            require(snapshot.document()['executionSha256'] == execution_sha256, 'Checkpoint execution changed')
            return snapshot
        confirmed = journal.db.execute('SELECT confirmed_serial FROM durability WHERE id=1').fetchone()[0]
        if journal.snapshot_serial == confirmed:
            return None
        cp = journal.checkpoint()
        rows = journal.db.execute('SELECT id,received,committed,ended FROM route ORDER BY id').fetchall()
        value = {'apiVersion': VERSION, 'serial': journal.snapshot_serial, 'revision': cp.revision,
                 'stateBase64': base64.b64encode(cp.state).decode('ascii'), 'executionSha256': execution_sha256,
                 'manifest': json.loads(manifest(journal.inputs.values(), journal.outputs.values(), journal.limits)),
                 'routes': [{'routeId': r, 'received': n, 'committed': c, 'ended': bool(e),
                             'frames': [json.loads(w) for w, in journal.db.execute('SELECT wire FROM frame WHERE route_id=? ORDER BY sequence', (r,))]}
                            for r,n,c,e in rows]}
        snapshot = Snapshot(encode(value))
        journal.db.execute('INSERT INTO snapshot_candidate VALUES (1,?)', (snapshot.wire,))
        return snapshot


def confirm(journal, serial, sha256):
    """Apply the exact candidate's verified control-plane receipt, not an S3 PUT ACK."""
    counter(serial); digest(sha256)
    require(journal.durability == 'EXTERNAL', 'External checkpoint requires external durability')
    with journal._transaction(advance=False):
        previous, previous_sha = journal.db.execute('SELECT confirmed_serial,digest FROM durability WHERE id=1').fetchone()
        if serial == previous:
            require(previous_sha == sha256, 'Conflicting checkpoint confirmation')
            return
        require(serial > previous and serial <= journal.snapshot_serial, 'Stale checkpoint confirmation')
        exists = journal.db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='snapshot_candidate'").fetchone()
        row = journal.db.execute('SELECT wire FROM snapshot_candidate WHERE id=1').fetchone() if exists else None
        require(row is not None, 'Missing checkpoint candidate')
        snapshot = Snapshot(row[0])
        require(snapshot.serial == serial and hmac.compare_digest(snapshot.sha256, sha256), 'Checkpoint confirmation mismatch')
        for route in snapshot.document()['routes']:
            identity = route['routeId']
            sequence = route['committed'] if identity in journal.inputs else route['received']
            journal.db.execute('UPDATE frontier SET sequence=? WHERE route_id=?', (sequence, identity))
        journal.db.execute('UPDATE durability SET confirmed_serial=?,digest=? WHERE id=1', (serial, sha256))
        journal.db.execute('DELETE FROM snapshot_candidate WHERE id=1')


def restore(directory, snapshot, inputs, outputs, limits, *, expected_sha256, execution_sha256):
    """Restore a verified snapshot into a NEW private volume with exact bindings.

    Generation/Attempt remapping is deliberately not inferred from a file; it needs
    a separate authenticated handover contract. Caller verifies S3 version and hash.
    """
    require(type(snapshot) is Snapshot, 'Invalid checkpoint snapshot')
    digest(expected_sha256); digest(execution_sha256)
    require(hmac.compare_digest(snapshot.sha256, expected_sha256), 'Checkpoint digest mismatch')
    value = snapshot.document()
    require(value['executionSha256'] == execution_sha256, 'Checkpoint execution changed')
    require(value['manifest'] == json.loads(manifest(inputs, outputs, limits)), 'Checkpoint restore authority changed')
    journal = Journal(directory, inputs, outputs, limits, create=True, durability='EXTERNAL')
    try:
        with journal._transaction(advance=False):
            for route in value['routes']:
                identity = route['routeId']
                journal.db.execute('UPDATE route SET received=?,committed=?,ended=? WHERE id=?',
                                   (route['received'], route['committed'], int(route['ended']), identity))
                for frame_doc in route['frames']:
                    frame = decode(encode(frame_doc))
                    journal.db.execute('INSERT INTO frame VALUES (?,?,?)', (identity, frame.sequence, frame.encode()))
                sequence = route['committed'] if identity in journal.inputs else route['received']
                journal.db.execute('UPDATE frontier SET sequence=? WHERE route_id=?', (sequence, identity))
            journal._capacity()
            journal.db.execute('UPDATE checkpoint SET revision=?,state=? WHERE id=1',
                               (value['revision'], state_bytes(value['stateBase64'], limits.max_state_bytes)))
            journal.db.execute('UPDATE durability SET serial=?,confirmed_serial=?,digest=? WHERE id=1',
                               (value['serial'], value['serial'], snapshot.sha256))
        fd = os.open(journal.directory / 'processor.sha256', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'wb') as file:
            file.write(execution_sha256.encode('ascii')); file.flush(); os.fsync(file.fileno())
        fd = os.open(journal.directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        return journal
    except BaseException:
        journal.close()
        raise
