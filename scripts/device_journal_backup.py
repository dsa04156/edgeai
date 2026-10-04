"""Consistent encrypted Device source journal snapshots; restore into a quarantined new directory."""
import argparse
import base64
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys
import tempfile
import time

from postgres_backup import ROOT, Blocked, private_file
import private_material_backup as material

sys.path.insert(0, str(ROOT / 'runner'))
from edgeai_runner.stream_checkpoint import MAX_BYTES, encode, state_bytes, validate_state
from edgeai_runner.stream_journal import Journal, Limits, manifest, require
from edgeai_runner.stream_protocol import Binding, Producer, _uuid
from edgeai_runner import stream_source_completion as completion

SCOPE = 'device-source-journal-backup'
CHUNK_BYTES = 8 * 1024 * 1024


def private_directory(path):
    info = path.lstat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
            and stat.S_IMODE(info.st_mode) == 0o700, 'Private source directory required')


def completion_bytes(directory):
    path = directory / 'completion.json'
    if not os.path.lexists(path): return None
    with material.private_input(path, 32768) as source: return source.read()


def validate(wire):
    require(type(wire) is bytes and 0 < len(wire) <= MAX_BYTES, 'Invalid Device backup size')
    value = material.json_value(wire)
    require(type(value) is dict and set(value) == {'formatVersion','scope','serial','revision','stateBase64','manifest','routes','completion'},
            'Invalid Device backup fields')
    require(type(value['formatVersion']) is int and value['formatVersion'] == 1 and value['scope'] == SCOPE,
            'Invalid Device backup format')
    validate_state(value)
    config = value['manifest']
    bindings = [Binding(b['routeId'], b['generation'], Producer.parse(b['producer'])) for b in config['outputs']]
    require(not config['inputs'] and bindings and all(b.producer.kind == 'DEVICE_SESSION' for b in bindings)
            and len({b.producer for b in bindings}) == 1, 'Backup requires one LOCAL Device source')
    limits = Limits(**config['limits'])
    require(config == json.loads(manifest([], bindings, limits)), 'Noncanonical Device manifest')
    intent = value['completion']
    if intent is not None:
        require(type(intent) is dict and isinstance(intent.get('runId'), str), 'Invalid completion intent')
        _uuid(intent['runId'])
        # Reuse the exact live source completion contract, without a broker or lease.
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            material.write_json(directory / 'completion.json', intent)
            require(completion.read(directory, intent['runId'], bindings[0].producer, limits) == intent,
                    'Completion identity differs')
        require(intent['manifest'] == config, 'Completion manifest differs from snapshot')
        cursors = {r['routeId']: r['sequence'] for r in intent['routes']}
        require(all(r['ended'] and not r['frames'] and r['received'] == r['committed'] == cursors[r['routeId']]
                    for r in value['routes']), 'Completion cursors differ from snapshot')
    require(encode(value) == wire, 'Noncanonical Device backup encoding')
    return value, bindings, limits


def capture(directory, timeout, *, _quarantined=False):
    """One read transaction preserves state, processing acknowledgements and all fanout frames together."""
    directory = directory.absolute(); private_directory(directory); private_directory(directory / 'journal')
    require(_quarantined or not os.path.lexists(directory / 'journal/recovery.json'), 'Cannot back up an unactivated recovery')
    before = completion_bytes(directory)
    path = directory / 'journal/journal.sqlite'
    deadline = time.monotonic() + timeout
    with material.private_input(path, 160 * 1024 * 1024) as source:
        identity = os.fstat(source.fileno())
        with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, isolation_level=None, timeout=timeout)) as db:
            db.execute('PRAGMA query_only=ON'); db.execute('PRAGMA trusted_schema=OFF')
            db.set_progress_handler(lambda: int(time.monotonic() >= deadline), 1000)
            db.execute('BEGIN')
            tables = {name for name, in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            require(tables <= {'checkpoint','route','frame','durability','frontier','snapshot_candidate'}
                    and {'checkpoint','route','frame','durability','frontier'} <= tables, 'Unsupported journal schema')
            require(db.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'Journal integrity check failed')
            require(db.execute('SELECT count(*) FROM checkpoint').fetchone() == (1,)
                    and db.execute('SELECT count(*) FROM durability').fetchone() == (1,), 'Invalid singleton journal state')
            require(db.execute('SELECT mode,confirmed_serial,digest FROM durability WHERE id=1').fetchone() == ('LOCAL',-1,None),
                    'Only LOCAL Device journals can use this backup')
            require('snapshot_candidate' not in tables or db.execute('SELECT count(*) FROM snapshot_candidate').fetchone() == (0,),
                    'Unconfirmed external checkpoint cannot use Device backup')
            count, size, largest = db.execute('SELECT count(*),coalesce(sum(length(wire)),0),coalesce(max(length(wire)),0) FROM frame').fetchone()
            require(count <= 4096 and size <= 67108864 and largest <= 524288, 'Journal frame allocation exceeds limit')
            row = db.execute('SELECT manifest,revision,state FROM checkpoint WHERE id=1').fetchone()
            require(row is not None and isinstance(row[0], str) and len(row[0]) <= 16384
                    and type(row[2]) is bytes and len(row[2]) <= 1048576, 'Invalid checkpoint allocation')
            config = material.json_value(row[0])
            rows = db.execute('SELECT id,direction,received,committed,ended FROM route LIMIT 17').fetchall()
            require(1 <= len(rows) <= 16 and all(r[1] == 'OUT' and r[4] in (0,1) for r in rows), 'Device source output routes required')
            require(set(db.execute('SELECT route_id,sequence FROM frontier')) == {(r[0],0) for r in rows},
                    'LOCAL source frontier changed')
            def frames(route_id):
                result = []
                for sequence, wire in db.execute('SELECT sequence,wire FROM frame WHERE route_id=? ORDER BY sequence',(route_id,)):
                    frame = material.json_value(wire)
                    require(frame.get('sequence') == sequence, 'Journal frame index differs')
                    result.append(frame)
                return result
            value = {'formatVersion':1,'scope':SCOPE,'serial':db.execute('SELECT serial FROM durability WHERE id=1').fetchone()[0],
                'revision':row[1],'stateBase64':base64.b64encode(row[2]).decode('ascii'),'manifest':config,
                'routes':[{'routeId':r,'received':n,'committed':c,'ended':bool(e),
                    'frames':frames(r)}
                    for r,d,n,c,e in sorted(rows)],
                'completion':None if before is None else material.json_value(before)}
            require(sum(len(r['frames']) for r in value['routes']) == count, 'Journal has unbound frames')
            db.execute('COMMIT')
        current = path.lstat()
        require((identity.st_dev,identity.st_ino,identity.st_uid,identity.st_mode,identity.st_nlink)
                == (current.st_dev,current.st_ino,current.st_uid,current.st_mode,current.st_nlink), 'Source journal identity changed')
    require(completion_bytes(directory) == before, 'Completion intent changed during snapshot; retry')
    wire = encode(value); validate(wire)
    return wire


def backup(age, directory, recipient, output, timeout=30):
    wire = capture(directory, timeout)
    with tempfile.TemporaryDirectory(prefix='snapshot-', dir=output) as temporary:
        selected = []
        for index, offset in enumerate(range(0, len(wire), CHUNK_BYTES)):
            name = 'journal-' + str(index).zfill(3) + '.json.part'; path = Path(temporary) / name
            with private_file(path) as target: target.write(wire[offset:offset+CHUNK_BYTES])
            selected.append((name,path))
        envelope = material.backup(age, selected, recipient, output)
    report = {'formatVersion':1,'scope':SCOPE,'status':'ENCRYPTED_DEVICE_JOURNAL',
        'bundleId':envelope['bundleId'],'ciphertextSha256':envelope['ciphertextSha256'],
        'snapshotBytes':len(wire),'activated':False,'originAuthenticated':False}
    material.write_json(output / 'journal-backup.json', report)
    return report


def restore(age, bundle, identity, output):
    with tempfile.TemporaryDirectory(prefix='recovery-', dir=output) as temporary:
        temporary = Path(temporary)
        decrypted = temporary / 'decrypted'; decrypted.mkdir(mode=0o700)
        envelope = material.restore(age, bundle, identity, decrypted)
        paths = sorted((decrypted / 'files').iterdir())
        require(1 <= len(paths) <= (MAX_BYTES + CHUNK_BYTES - 1) // CHUNK_BYTES
                and [p.name for p in paths] == ['journal-' + str(i).zfill(3) + '.json.part' for i in range(len(paths))],
                'Invalid Device snapshot chunks')
        parts = []
        for index, path in enumerate(paths):
            with material.private_input(path, CHUNK_BYTES) as source: raw = source.read()
            require(0 < len(raw) <= CHUNK_BYTES and (index == len(paths)-1 or len(raw) == CHUNK_BYTES), 'Invalid Device chunk size')
            parts.append(raw)
        wire = b''.join(parts); value, bindings, limits = validate(wire)
        source = temporary / 'source'; source.mkdir(mode=0o700)
        with Journal(source / 'journal', [], bindings, limits, create=True) as journal:
            with journal._transaction(advance=False):
                for route in value['routes']:
                    journal.db.execute('UPDATE route SET received=?,committed=?,ended=? WHERE id=?',
                        (route['received'],route['committed'],int(route['ended']),route['routeId']))
                    for frame in route['frames']:
                        journal.db.execute('INSERT INTO frame VALUES (?,?,?)', (route['routeId'],frame['sequence'],encode(frame)))
                journal.db.execute('UPDATE checkpoint SET revision=?,state=? WHERE id=1',
                    (value['revision'],state_bytes(value['stateBase64'],limits.max_state_bytes)))
                journal.db.execute('UPDATE durability SET serial=? WHERE id=1', (value['serial'],))
                journal._capacity()
        if value['completion'] is not None: material.write_json(source / 'completion.json', value['completion'])
        marker = {'formatVersion':1,'scope':SCOPE,'status':'QUARANTINED_DEVICE_JOURNAL',
            'bundleId':envelope['bundleId'],'snapshotSha256':hashlib.sha256(wire).hexdigest(),'activated':False}
        material.write_json(source / 'journal/recovery.json', marker)
        for directory in (source / 'journal', source):
            fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(fd)
            finally: os.close(fd)
        require(not os.path.lexists(output / 'source'), 'Existing source restore destination refused')
        os.rename(source, output / 'source')
        fd = os.open(output, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)
    report = {**marker,'ciphertextSha256':envelope['ciphertextSha256'],'originAuthenticated':False,
        'routes':len(bindings),'pendingFrames':sum(len(r['frames']) for r in value['routes']),
        'completionIntent':value['completion'] is not None,'restoredAt':material.timestamp()}
    material.write_json(output / 'restore-report.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__); actions = parser.add_subparsers(dest='action', required=True)
    save = actions.add_parser('backup'); save.add_argument('--source',type=Path,required=True)
    save.add_argument('--recipient',required=True); save.add_argument('--timeout',type=int,default=30)
    load = actions.add_parser('restore'); load.add_argument('--input',type=Path,required=True)
    load.add_argument('--identity',type=Path,required=True)
    for action in (save,load): action.add_argument('--output',type=Path,required=True)
    args = parser.parse_args(); created = False
    try:
        age = material.Age()
        if args.action == 'backup': require(1 <= args.timeout <= 120, 'Invalid snapshot timeout')
        args.output.mkdir(mode=0o700); created = True
        result = (backup(age,args.source,args.recipient,args.output,args.timeout) if args.action == 'backup'
                  else restore(age,args.input,args.identity,args.output))
        print(result['status'] + ': source identity retained; no producer activated')
        return 0
    except Exception as error:
        blocked = isinstance(error,Blocked)
        if created: material.write_json(args.output / 'failure.json', {'status':'BLOCKED' if blocked else 'FAIL',
            'failureType':type(error).__name__,'activated':False})
        print(('BLOCKED' if blocked else 'FAIL') + ': Device journal recovery; private values suppressed')
        return 2 if blocked else 1


if __name__ == '__main__': raise SystemExit(main())
