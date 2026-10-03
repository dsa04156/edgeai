"""Combine two fixed stream result files for the synthetic multi-device demo."""
import json
import os
from pathlib import Path


def main():
    inputs = {}
    for port in ('root', 'sink'):
        value = json.loads((Path(os.environ['EDGEAI_INPUT_DIR']) / port).read_bytes())
        if type(value) is not dict or set(value) != {'sum'} or type(value['sum']) is not int:
            raise ValueError('Invalid synthetic stream result')
        inputs[port] = value['sum']
    report = {'sourceMode': 'SYNTHETIC', 'sum': sum(inputs.values()), 'inputs': inputs}
    (Path(os.environ['EDGEAI_OUTPUT_DIR']) / 'report').write_text(json.dumps(report))


if __name__ == '__main__':
    main()
