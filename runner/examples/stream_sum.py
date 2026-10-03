"""Synthetic integer stream reference, not a device/model accuracy benchmark.

mode=zip waits for one frame from every input that has not ended. mode=arrival
consumes each available input. State is the committed running total from the parent.
"""
import base64
import json
import sys

VERSION = 'edgeai.stream-workload/v1'
LIMIT = 12 * 1024 * 1024


def run():
    while True:
        raw = sys.stdin.buffer.readline(LIMIT + 2)
        if not raw:
            return
        if len(raw) > LIMIT + 1 or not raw.endswith(b'\n'):
            raise ValueError()
        value = json.loads(raw)
        if value['apiVersion'] != VERSION:
            raise ValueError()
        state = base64.b64decode(value['stateBase64'], validate=True)
        reply = {'apiVersion': VERSION, 'revision': value['revision'], 'status': 'WAIT',
                 'consumed': {}, 'stateBase64': value['stateBase64'], 'outputs': {}}
        mode = value['parameters'].get('mode', 'arrival')
        if mode not in ('arrival', 'zip'):
            raise ValueError()
        ready = mode == 'arrival' or set(value['inputPorts']) <= set(value['inputs']) | set(value['endedInputs'])
        if ready:
            total = int(state or b'0')
            ended = set(value['endedInputs'])
            changed = False
            for port, frame in value['inputs'].items():
                reply['consumed'][port] = frame['sequence']
                if frame['kind'] == 'END':
                    ended.add(port)
                else:
                    number = json.loads(base64.b64decode(frame['payloadBase64'], validate=True))
                    if type(number) is not int or frame['mediaType'] != 'application/json':
                        raise ValueError()
                    total += number
                    changed = True
            reply['status'] = 'COMPLETE' if ended == set(value['inputPorts']) else 'COMMIT'
            reply['stateBase64'] = base64.b64encode(str(total).encode()).decode('ascii')
            if changed:
                reply['outputs'] = {port: {'payloadBase64': reply['stateBase64'], 'mediaType': 'application/json'} for port in value['outputs']}
        sys.stdout.write(json.dumps(reply, separators=(',', ':')) + '\n')
        sys.stdout.flush()


if __name__ == '__main__':
    try:
        run()
    except Exception:
        # Parent reports a fixed disposition; never print input/state/parameters.
        sys.exit(1)
