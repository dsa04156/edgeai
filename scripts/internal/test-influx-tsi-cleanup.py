"""Compare TSI orphan-file cleanup of the official and patched influxd 2.9.1 binaries.

Each binary writes a TSI index, then restarts with orphan .tsl/.tsi files planted in one partition and
files of the same names in its working directory, first writable and then on a read-only mount
(`unshare -rm`, like readOnlyRootFilesystem). The patched binary must delete only the partition orphans.
"""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tarfile
import time
import urllib.error
import urllib.request
import uuid

import influx_local

ROOT = Path(__file__).resolve().parents[2]
ORPHANS = ['L0-00000901.tsl', 'L1-00000902.tsi']
ORG, BUCKET = 'tsi-check', 'tsi-check'


def official_binary():
    install = ROOT / '.tools/influxdb2' / influx_local.VERSION
    install.mkdir(parents=True, exist_ok=True)
    archive = install / 'influxdb.tar.gz'
    if not archive.exists():
        subprocess.run(['curl', '-fLsS', '--max-time', '120', '-o', str(archive), influx_local.URL], check=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != influx_local.SHA256:
        raise RuntimeError('Official InfluxDB artifact checksum mismatch')
    if not list(install.rglob('influxd')):
        with tarfile.open(archive) as bundle:
            bundle.extractall(install, filter='data')
    return next(install.rglob('influxd')).resolve()


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def http(origin, path, token='', body=None, raw=None):
    headers = {'Authorization': 'Token ' + token} if token else {}
    data = raw
    if body is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(body).encode()
    request = urllib.request.Request(origin + path, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.read()


class Server:
    def __init__(self, binary, run, cwd, readonly):
        self.port = free_port()
        self.origin = f'http://127.0.0.1:{self.port}'
        args = [str(binary), f'--http-bind-address=127.0.0.1:{self.port}', f'--bolt-path={run / "influxd.bolt"}',
                f'--engine-path={run / "engine"}', f'--sqlite-path={run / "influxd.sqlite"}', '--reporting-disabled=true']
        if readonly:
            # A private mount namespace remounts only the working directory read-only, then execs influxd there.
            args = ['unshare', '-rm', 'sh', '-c', 'mount --bind "$1" "$1" && mount -o remount,bind,ro "$1" && cd "$1" && shift && exec "$@"',
                    'sh', str(cwd), *args]
        self.log_path = run / f'server-{time.monotonic_ns()}.log'
        self.log = open(self.log_path, 'wb')
        self.process = subprocess.Popen(args, cwd=cwd, stdout=self.log, stderr=self.log, start_new_session=True)
        for _ in range(1200):
            if self.process.poll() is not None:
                raise RuntimeError('influxd exited during startup: ' + self.log_path.read_text()[-2000:])
            try:
                if json.loads(http(self.origin, '/health')).get('status') == 'pass':
                    return
            except (OSError, urllib.error.HTTPError, ValueError):
                time.sleep(.1)
        self.process.kill()
        self.process.wait()
        raise RuntimeError('influxd readiness timeout')

    def stop(self):
        self.process.send_signal(signal.SIGTERM)
        self.process.wait(timeout=60)
        self.log.close()
        return self.log_path.read_text()


def rows(server, token):
    flux = f'from(bucket:"{BUCKET}") |> range(start:0) |> keep(columns:["_time","_value","_field","_measurement","host"])'
    body = http(server.origin, f'/api/v2/query?org={ORG}', token, {'query': flux, 'type': 'flux'})
    records = [r for r in csv.reader(io.StringIO(body.decode())) if r]
    header = records[0] if records else []
    return sorted(tuple(r[header.index(c)] for c in ('_measurement', 'host', '_field', '_time', '_value'))
                  for r in records[1:] if r != header)


def partition(run):
    manifests = sorted((run / 'engine/data').glob('*/autogen/*/index/0/MANIFEST'))
    assert len(manifests) == 1, manifests
    return manifests[0].parent


def snapshot(directory):
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.iterdir() if p.is_file()}


def plant(part, cwd):
    manifest_files = set(json.loads((part / 'MANIFEST').read_text())['files'])
    assert manifest_files and not manifest_files & set(ORPHANS), manifest_files
    for name in ORPHANS:
        (part / name).write_bytes(b'orphan')
        (cwd / name).write_bytes(b'unrelated cwd file')
    return manifest_files


def exercise(name, binary, work):
    run, cwd = work / name, work / (name + '-cwd')
    run.mkdir()
    cwd.mkdir()
    server = Server(binary, run, run, False)
    token = secrets.token_urlsafe(32)
    http(server.origin, '/api/v2/setup', body={'username': 'tsi-check', 'password': secrets.token_urlsafe(24),
                                                'org': ORG, 'bucket': BUCKET, 'token': token})
    lines = '\n'.join(f'cpu,host=h{i % 4} value={i} {1_700_000_000 + i}' for i in range(200))
    http(server.origin, f'/api/v2/write?org={ORG}&bucket={BUCKET}&precision=s', token, raw=lines.encode())
    baseline = rows(server, token)
    assert len(baseline) == 200, len(baseline)
    health = json.loads(http(server.origin, '/health'))
    ui = {path: hashlib.sha256(http(server.origin, path)).hexdigest() for path in ('/', '/api/v2/swagger.json')}
    server.stop()
    part = partition(run)
    result = {'binary': str(binary), 'version': health['version'], 'commit': health['commit'], 'ui': ui, 'partition': str(part.relative_to(run)), 'cases': {}}
    for mode in ('writable-cwd', 'readonly-cwd'):
        manifest_files = plant(part, cwd)
        before = {k: v for k, v in snapshot(part).items() if k not in ORPHANS}
        case = {'manifestFiles': sorted(manifest_files)}
        try:
            server = Server(binary, run, cwd, mode == 'readonly-cwd')
            case['rowsMatch'] = rows(server, token) == baseline
            server.stop()
        except (RuntimeError, urllib.error.HTTPError) as error:
            case['rowsMatch'] = False
            case['startError'] = str(error)[-300:]
        log = max(run.glob('server-*.log'), key=lambda p: p.stat().st_mtime_ns).read_text()
        after = snapshot(part)
        case['orphansLeftInPartition'] = sorted(n for n in ORPHANS if n in after)
        case['cwdFilesLeft'] = sorted(n for n in ORPHANS if (cwd / n).exists())
        case['otherPartitionFilesUnchanged'] = {k: v for k, v in after.items() if k not in ORPHANS} == before
        case['unlinkErrors'] = sorted({line.split('error=', 1)[1] for line in log.splitlines() if 'unlinkat' in line and 'error=' in line})
        result['cases'][mode] = case
        for leftover in ORPHANS:
            (part / leftover).unlink(missing_ok=True)
            (cwd / leftover).unlink(missing_ok=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--patched', type=Path, required=True)
    parser.add_argument('--official', type=Path)
    parser.add_argument('--work', type=Path, default=ROOT / '.tools')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/influx-tsi-cleanup-test.json')
    args = parser.parse_args()
    work = args.work.resolve() / ('influx-tsi-cleanup-' + uuid.uuid4().hex[:12])
    work.mkdir(parents=True, mode=0o700)
    report = {'official': exercise('official', (args.official or official_binary()).resolve(), work),
              'patched': exercise('patched', args.patched.resolve(), work)}
    official, patched = report['official']['cases'], report['patched']['cases']
    checks = {
        # Upstream 2.9.1: cleanup targets the working directory, deleting unrelated files or failing on a read-only cwd.
        'official removes same-named cwd files, keeps orphans': official['writable-cwd']['cwdFilesLeft'] == [] and official['writable-cwd']['orphansLeftInPartition'] == ORPHANS,
        'official fails on read-only cwd': not official['readonly-cwd']['rowsMatch'] and bool(official['readonly-cwd']['unlinkErrors']),
    }
    checks['patched serves the release UI and swagger'] = report['official']['ui'] == report['patched']['ui']
    for mode in ('writable-cwd', 'readonly-cwd'):
        case = patched[mode]
        checks[f'patched {mode}: orphans removed, cwd files kept, manifest files unchanged, rows equal'] = (
            case['orphansLeftInPartition'] == [] and case['cwdFilesLeft'] == ORPHANS and case['otherPartitionFilesUnchanged']
            and case['rowsMatch'] and case['unlinkErrors'] == [])
    report['checks'] = checks
    report['status'] = 'PASS' if all(checks.values()) else 'FAIL'
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if report['status'] == 'PASS':
        shutil.rmtree(work)
    raise SystemExit(0 if report['status'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
