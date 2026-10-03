"""Private terminal intent, never a locally issued completion grant or MQTT lease."""
import json
import os
from pathlib import Path
import stat
import tempfile

from edgeai_runner.stream_journal import manifest
from edgeai_runner.stream_protocol import Binding, Producer, _counter, _uuid, _unique_object, _invalid_constant


class CompletionError(RuntimeError):
    pass


def require(condition):
    if not condition:
        raise CompletionError('STREAM_INVALID_COMPLETION_INTENT')


def read(directory, run_id, actor, limits):
    path = Path(directory)/'completion.json'
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return None
    except OSError:
        raise CompletionError('STREAM_INVALID_COMPLETION_INTENT') from None
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
                and stat.S_IMODE(info.st_mode) == 0o600 and 0 < info.st_size <= 32768)
        value = json.loads(os.read(fd, 32769), object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
        require(type(value) is dict and set(value) == {'version','runId','actor','manifest','routes'})
        require(type(value['version']) is int and value['version'] == 1 and value['runId'] == run_id
                and Producer.parse(value['actor']) == actor)
        config = value['manifest']
        require(type(config) is dict and type(config.get('outputs')) is list and 1 <= len(config['outputs']) <= 16)
        bindings = [Binding(b['routeId'], b['generation'], Producer.parse(b['producer'])) for b in config['outputs']]
        require(all(b.producer == actor for b in bindings) and len({b.route_id for b in bindings}) == len(bindings))
        require(json.dumps(config, sort_keys=True, separators=(',',':')) == manifest([], bindings, limits))
        routes = value['routes']; require(type(routes) is list and len(routes) == len(bindings))
        for row in routes:
            require(type(row) is dict and set(row) == {'generationId','routeId','sequence'})
            _uuid(row['generationId']); _uuid(row['routeId']); _counter(row['sequence'])
        require(len({r['generationId'] for r in routes}) == len(routes))
        require({r['routeId'] for r in routes} == {b.route_id for b in bindings})
        return value
    except (ValueError, TypeError, KeyError, OverflowError, RecursionError, OSError):
        raise CompletionError('STREAM_INVALID_COMPLETION_INTENT') from None
    finally:
        os.close(fd)


def verify(value, journal):
    require(not journal.inputs and all(b.producer.kind == 'DEVICE_SESSION' for b in journal.outputs.values()))
    require(json.dumps(value['manifest'], sort_keys=True, separators=(',',':')) == journal.db.execute('SELECT manifest FROM checkpoint').fetchone()[0])
    expected = {r['routeId']:r['sequence'] for r in value['routes']}
    rows = journal.db.execute('SELECT id,direction,received,committed,ended FROM route').fetchall()
    require(len(rows) == len(expected) and journal.usage()[0] == 0)
    require(all(direction == 'OUT' and ended == 1 and received == committed == expected.get(identity)
                for identity,direction,received,committed,ended in rows))


def write(directory, run_id, actor, assignments, journal):
    checkpoint = journal.checkpoint()
    value = {'version':1,'runId':run_id,'actor':actor.document(),
             'manifest':json.loads(journal.db.execute('SELECT manifest FROM checkpoint').fetchone()[0]),
             'routes':[{'generationId':a.generation_id,'routeId':a.binding.route_id,
                        'sequence':checkpoint.output_sequences[a.binding.route_id]}
                       for a in sorted(assignments.values(),key=lambda a:a.generation_id)]}
    verify(value, journal)
    encoded = json.dumps(value,sort_keys=True,separators=(',',':')).encode(); require(len(encoded) <= 32768)
    fd, temporary = tempfile.mkstemp(prefix='.completion-', dir=directory)
    try:
        with os.fdopen(fd,'wb') as file:
            file.write(encoded); file.flush(); os.fsync(file.fileno())
        os.replace(temporary,Path(directory)/'completion.json')
        parent = os.open(directory,os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(parent)
        finally: os.close(parent)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    return value
