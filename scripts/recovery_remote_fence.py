"""Fence one explicitly identified SYNTHETIC reference Remote provider and await its workers."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
from pathlib import Path
import re
import socket
import ssl
import threading
import time
from urllib.parse import urlsplit
import uuid

from postgres_backup import Blocked, private_file, read_private

STATES = {'ALLOCATED', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED'}
PATH = '/reference/v1/recovery'


def token(path):
    with read_private(path) as source:
        value = source.read(258).decode('ascii').strip()
    if not re.fullmatch('[A-Za-z0-9_-]{32,256}', value):
        raise ValueError('Invalid private credential')
    return value


def unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise Blocked('Duplicate response field')
        value[key] = item
    return value


class Client:
    def __init__(self, args):
        self.args = args
        self.endpoint = urlsplit(args.endpoint)
        if (self.endpoint.scheme != 'https' or not self.endpoint.hostname or self.endpoint.username or
                self.endpoint.password or self.endpoint.path not in ('', '/') or self.endpoint.query or self.endpoint.fragment):
            raise ValueError('An explicit HTTPS origin is required')
        if not re.fullmatch('[a-f0-9]{64}', args.certificate_sha256) or not 10 <= args.timeout <= 300:
            raise ValueError('Explicit certificate SHA256 and timeout 10..300 required')
        self.tls = ssl.create_default_context(cafile=str(args.ca_file))
        self.tls.minimum_version = ssl.TLSVersion.TLSv1_2
        self.deadline = time.monotonic() + args.timeout

    def request(self, credential, method='GET', path=PATH, value=None, max_bytes=8192):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise Blocked('Provider workers have not stopped before the deadline')
        connection = http.client.HTTPSConnection(self.endpoint.hostname, self.endpoint.port or 443,
                                                context=self.tls, timeout=min(3, remaining))
        timer = None
        try:
            connection.connect()
            # Validate the actual request socket before transmitting any credential.
            if hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest() != self.args.certificate_sha256:
                raise Blocked('Provider certificate identity differs')
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise Blocked('Provider recovery deadline expired')
            channel = connection.sock
            def interrupt():
                try:
                    channel.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            # A peer trickling headers/body cannot keep extending the socket idle timeout.
            timer = threading.Timer(remaining, interrupt)
            timer.daemon = True
            timer.start()
            connection.request(method, path, body=json.dumps(value).encode() if value is not None else None,
                               headers={'Authorization': 'Bearer ' + credential, 'Content-Type': 'application/json'})
            response = connection.getresponse()
            raw = response.read(max_bytes + 1)
            if time.monotonic() >= self.deadline:
                raise Blocked('Provider recovery deadline expired')
            if len(raw) > max_bytes or response.getheader('Content-Type') != 'application/json':
                raise Blocked('Unsupported provider response')
            return response.status, json.loads(raw, object_pairs_hook=unique)
        finally:
            if timer is not None:
                timer.cancel()
            connection.close()

    def status(self, credential, method='GET', value=None):
        code, value = self.request(credential, method, value=value)
        fields = {'apiVersion', 'providerId', 'recoveryId', 'sourceMode', 'fenced', 'allocationCount',
                  'states', 'activeWorkers', 'quiescent'}
        if code != 200 or not isinstance(value, dict) or set(value) != fields:
            raise Blocked('Provider recovery request rejected')
        if value['apiVersion'] != 'edgeai.remote.recovery/v1' or value['sourceMode'] != 'SYNTHETIC':
            raise Blocked('Unsupported provider recovery protocol')
        for field in ('providerId', 'recoveryId'):
            if field == 'recoveryId' and value[field] is None:
                continue
            if not isinstance(value[field], str) or str(uuid.UUID(value[field])) != value[field]:
                raise Blocked('Invalid provider identity')
        if self.args.provider_id and value['providerId'] != self.args.provider_id:
            raise Blocked('Provider installation identity differs')
        if (not isinstance(value['states'], dict) or set(value['states']) != STATES or
                any(type(n) is not int or not 0 <= n <= 9007199254740991
                    for n in [value['allocationCount'], value['activeWorkers'], *value['states'].values()])):
            raise Blocked('Invalid provider inventory')
        active = sum(value['states'][s] for s in ('ALLOCATED', 'RUNNING', 'CANCELLING'))
        if (sum(value['states'].values()) != value['allocationCount'] or
                type(value['fenced']) is not bool or value['fenced'] != (value['recoveryId'] is not None) or
                type(value['quiescent']) is not bool or
                value['quiescent'] != (value['fenced'] and active == 0 and value['activeWorkers'] == 0)):
            raise Blocked('Inconsistent provider recovery state')
        return value


def execute(args, report):
    for identifier in (args.provider_id, args.recovery_id):
        if identifier is not None and str(uuid.UUID(identifier)) != identifier:
            raise ValueError('Canonical UUID required')
    if not args.inspect and not all((args.provider_id, args.recovery_id, args.controller_token_file)):
        raise ValueError('Fence requires provider ID, recovery ID and previous controller credential file')
    operator = token(args.recovery_token_file)
    controller = token(args.controller_token_file) if not args.inspect else None
    if controller == operator:
        raise ValueError('Separate operator and controller credentials required')
    client = Client(args)
    observed = client.status(operator)
    report['observed'] = observed
    if args.inspect:
        report['status'] = 'SOURCE_REMOTE_INSPECTED'
        return
    if observed['recoveryId'] not in (None, args.recovery_id):
        raise Blocked('Provider belongs to another recovery')
    observed = client.status(operator, 'PUT', {'providerId': args.provider_id, 'recoveryId': args.recovery_id})
    while True:
        report['observed'] = observed
        if not observed['fenced'] or observed['recoveryId'] != args.recovery_id:
            raise Blocked('Provider recovery fence differs')
        report['fenceRetained'] = True
        if observed['quiescent']:
            code, denied = client.request(controller, path='/')
            if code != 403 or denied != {'code': 'PROVIDER_FENCED'}:
                raise Blocked('Previous controller access has not been fenced')
            final = client.status(operator)
            report['observed'] = final
            if not final['quiescent'] or final['recoveryId'] != args.recovery_id:
                raise Blocked('Provider changed during final verification')
            report.update(status='SOURCE_REMOTE_FENCED', workersStopped=True, controllerRejected=True,
                          verifiedAt=datetime.now(timezone.utc).isoformat())
            return
        time.sleep(min(.05, max(0, client.deadline - time.monotonic())))
        observed = client.status(operator)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('endpoint', 'certificate-sha256'):
        parser.add_argument('--' + name, required=True)
    for name in ('ca-file', 'recovery-token-file', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--controller-token-file', type=Path)
    parser.add_argument('--provider-id')
    parser.add_argument('--recovery-id')
    parser.add_argument('--inspect', action='store_true', help='Read installation identity and state without mutation')
    parser.add_argument('--timeout', type=int, default=60)
    args = parser.parse_args()
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    report = {'scope': 'reference-remote-source-fence', 'status': 'RUNNING', 'fenceRetained': False,
              'workersStopped': False, 'controllerRejected': False, 'activated': False, 'globalQuiescenceProven': False}
    code = 1
    try:
        execute(args, report)
        code = 0
    except (Blocked, OSError, http.client.HTTPException) as error:
        report.update(status='BLOCKED', failureType=type(error).__name__)
        code = 2
    except Exception as error:
        report.update(status='FAIL', failureType=type(error).__name__)
    with private_file(args.output / 'fence-report.json', 'w') as out:
        json.dump(report, out, indent=2); out.write('\n')
    print(report['status'] + ': reference Remote; private report retained')
    return code


if __name__ == '__main__':
    raise SystemExit(main())
