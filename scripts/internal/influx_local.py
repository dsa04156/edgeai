#!/usr/bin/env python3
"""Project-owned InfluxDB 2.9.1. Credentials stay in owner-only files; data survives stop."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tarfile
import time
import urllib.error
import urllib.request

VERSION = '2.9.1'
SHA256 = '762e4fc825c4386e0c5138e7c3f91fc778081db2bada1ec47066e786bf55d9ff'
URL = f'https://download.influxdata.com/influxdb/releases/influxdb2-{VERSION}_linux_amd64.tar.gz'
ROOT = Path(__file__).resolve().parents[2]

def private(path, content):
    if path.is_symlink():
        raise RuntimeError('Private material must be an ordinary owned file')
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as file:
        file.write(content)
    path.chmod(0o600)

def request(origin, path, token='', body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Token ' + token
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(urllib.request.Request(origin + path, data=data, headers=headers), timeout=5) as response:
        payload = response.read(1048577)
        if len(payload) > 1048576:
            raise RuntimeError('InfluxDB response limit exceeded')
        return json.loads(payload) if payload else {}

def owned(pid, run, binary):
    try:
        command = Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
        return Path(f'/proc/{pid}/exe').resolve() == binary and f'--engine-path={run / "engine"}'.encode() in command
    except (FileNotFoundError, PermissionError):
        return False

def start(run, port, org):
    if os.uname().machine != 'x86_64':
        raise RuntimeError('The verified local helper requires Linux amd64')
    install = ROOT / '.tools/influxdb2' / VERSION
    install.mkdir(parents=True, exist_ok=True)
    archive = install / 'influxdb.tar.gz'
    if not archive.exists():
        subprocess.run(['curl', '-fLsS', '--max-time', '120', '-o', str(archive), URL], check=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError('Official InfluxDB artifact checksum mismatch')
    binaries = list(install.rglob('influxd'))
    if not binaries:
        with tarfile.open(archive) as bundle:
            bundle.extractall(install, filter='data')
        binaries = list(install.rglob('influxd'))
    binary = binaries[0].resolve()
    run.mkdir(parents=True, exist_ok=True)
    run = run.resolve()
    origin = f'http://127.0.0.1:{port}'
    pid_file = run / 'pid'
    pid = int(pid_file.read_text()) if pid_file.exists() else 0
    if not owned(pid, run, binary):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', port))
        log = open(run / 'server.log', 'ab')
        process = subprocess.Popen([str(binary), f'--http-bind-address=127.0.0.1:{port}', f'--bolt-path={run / "influxd.bolt"}', f'--engine-path={run / "engine"}', f'--sqlite-path={run / "influxd.sqlite"}', '--reporting-disabled=true'], stdout=log, stderr=log, start_new_session=True)
        private(pid_file, str(process.pid))
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError('Owned InfluxDB failed to start; see its private log')
            try:
                if request(origin, '/health').get('status') == 'pass':
                    break
            except (OSError, urllib.error.HTTPError):
                time.sleep(.1)
        else:
            raise RuntimeError('Owned InfluxDB readiness timeout')
    if request(origin, '/health').get('version', '').lstrip('v') != VERSION:
        raise RuntimeError('InfluxDB version differs from the verified release')
    admin_file = run / 'admin-token'
    if request(origin, '/api/v2/setup').get('allowed'):
        token = secrets.token_urlsafe(48)
        private(admin_file, token)
        request(origin, '/api/v2/setup', body={'username': 'edgeai-local', 'password': secrets.token_urlsafe(32), 'org': org, 'bucket': 'sensor-originals', 'retentionPeriodSeconds': 30 * 86400, 'token': token})
    if not admin_file.exists():
        # Compatibility with the explicitly owned initial acceptance instance.
        existing = run / 'token'
        if not existing.exists():
            raise RuntimeError('Owned store has no administration credential')
        private(admin_file, existing.read_text().strip())
    admin = admin_file.read_text().strip()
    organization = next((item for item in request(origin, '/api/v2/orgs', admin)['orgs'] if item['name'] == org), None)
    if organization is None:
        raise RuntimeError('Configured InfluxDB organization is missing')
    org_id = organization['id']
    buckets = {item['name']: item for item in request(origin, '/api/v2/buckets?limit=100', admin)['buckets']}
    required = {'sensor-originals': 30, **{f'vd-results-{day}d': day for day in range(1, 31)}}
    for name, days in required.items():
        if name not in buckets:
            buckets[name] = request(origin, '/api/v2/buckets', admin, {'orgID': org_id, 'name': name, 'retentionRules': [{'type': 'expire', 'everySeconds': days * 86400}]})
        rules = buckets[name].get('retentionRules', [])
        if len(rules) != 1 or rules[0]['everySeconds'] != days * 86400:
            raise RuntimeError('Existing bucket retention differs; no data policy was overwritten')
    api_file = run / 'api-token'
    if not api_file.exists():
        permissions = [{'action': action, 'resource': {'type': 'buckets', 'id': buckets[name]['id'], 'orgID': org_id}} for name in required for action in ('read', 'write')]
        authorization = request(origin, '/api/v2/authorizations', admin, {'orgID': org_id, 'description': 'EdgeAI measurement read/write', 'permissions': permissions})
        private(api_file, authorization['token'])
    private(run / 'connection.json', json.dumps({'url': origin, 'org': org, 'tokenFile': str(api_file)}))
    print(f'Owned InfluxDB {VERSION} ready at {origin}; raw30d and result1–30d buckets ready.')
    print(f'Connection settings: {run / "connection.json"}; credentials are not printed.')

def stop(run):
    install = ROOT / '.tools/influxdb2' / VERSION
    binaries = list(install.rglob('influxd'))
    pid_file = run / 'pid'
    if not pid_file.exists():
        return
    pid = int(pid_file.read_text())
    stat = Path(f'/proc/{pid}/stat')
    if not stat.exists() or stat.read_text().rsplit(')', 1)[1].split()[0] == 'Z':
        return
    if not binaries or not owned(pid, run.resolve(), binaries[0].resolve()):
        raise RuntimeError('Recorded process is not the owned InfluxDB instance')
    os.kill(pid, signal.SIGTERM)
    for _ in range(100):
        if not owned(pid, run.resolve(), binaries[0].resolve()):
            print('Owned InfluxDB stopped; data and credentials retained.')
            return
        time.sleep(.1)
    raise RuntimeError('InfluxDB is still stopping; no forced termination performed')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['start', 'stop', 'status'])
    parser.add_argument('--test', action='store_true')
    args = parser.parse_args()
    run = ROOT / ('output/sensor-storage/influx-test' if args.test else '.tools/influx-data-store')
    if args.action == 'start':
        start(run, 19086 if args.test else 18086, 'edgeai-test' if args.test else 'edgeai')
    elif args.action == 'stop':
        stop(run)
    else:
        binaries = list((ROOT / '.tools/influxdb2' / VERSION).rglob('influxd'))
        pid_file = run / 'pid'
        raise SystemExit(0 if binaries and pid_file.exists() and owned(int(pid_file.read_text()), run.resolve(), binaries[0].resolve()) else 1)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        detail = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(f'FAIL: InfluxDB setup: {detail}. No credentials are printed.')
        raise SystemExit(1)
