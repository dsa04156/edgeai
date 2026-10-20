"""Continuous typed sample aggregation and final artifact for the workflow example."""
import base64
import json
import math
import os
from pathlib import Path
import sys

VERSION = 'edgeai.stream-workload/v1'
LIMIT = 12 * 1024 * 1024


def run():
    for raw in iter(lambda: sys.stdin.buffer.readline(LIMIT + 2), b''):
        if len(raw) > LIMIT + 1 or not raw.endswith(b'\n'):
            raise ValueError()
        request = json.loads(raw)
        if request['apiVersion'] != VERSION or request['inputPorts'] != ['input'] or request['outputs']:
            raise ValueError()
        previous = base64.b64decode(request['stateBase64'], validate=True)
        state = json.loads(previous) if previous else {'count': 0, 'numericCount': 0, 'sum': 0,
            'minimum': None, 'maximum': None, 'mean': None, 'lastSample': None, 'ended': False}
        consumed = {}
        ended = set(request['endedInputs'])
        for port, frame in request['inputs'].items():
            consumed[port] = frame['sequence']
            if frame['kind'] == 'END':
                ended.add(port)
                continue
            sample = json.loads(base64.b64decode(frame['payloadBase64'], validate=True))
            if frame['mediaType'] != 'application/json' or type(sample) is not dict or sample.get('quality') != 'VALID':
                raise ValueError()
            value = sample.get('value')
            if type(value) not in (int, float, bool) or state['count'] >= 9007199254740991:
                raise ValueError()
            state['count'] += 1
            state['lastSample'] = sample
            if type(value) is not bool:
                if not math.isfinite(value): raise ValueError()
                state['numericCount'] += 1
                state['sum'] += value
                if not math.isfinite(state['sum']): raise ValueError()
                state['minimum'] = value if state['minimum'] is None else min(value, state['minimum'])
                state['maximum'] = value if state['maximum'] is None else max(value, state['maximum'])
                state['mean'] = state['sum'] / state['numericCount']
        state['ended'] = ended == set(request['inputPorts'])
        response = {'apiVersion': VERSION, 'revision': request['revision'],
            'status': 'COMPLETE' if state['ended'] else 'COMMIT' if consumed else 'WAIT',
            'consumed': consumed, 'outputs': {},
            'stateBase64': base64.b64encode(json.dumps(state, separators=(',', ':'), allow_nan=False).encode()).decode()
                if consumed else request['stateBase64']}
        sys.stdout.write(json.dumps(response, separators=(',', ':')) + '\n')
        sys.stdout.flush()


def finalize():
    state = json.loads(Path(os.environ['EDGEAI_STATE_FILE']).read_bytes())
    if not state.get('ended'):
        raise ValueError()
    Path(os.environ['EDGEAI_OUTPUT_DIR'], 'result').write_text(json.dumps(state, separators=(',', ':'), allow_nan=False))


if __name__ == '__main__':
    try:
        if sys.argv[1:] == ['--finalize']: finalize()
        elif sys.argv[1:]: raise ValueError()
        else: run()
    except Exception:
        sys.exit(1)
