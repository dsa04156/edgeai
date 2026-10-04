"""Publish a verified Remote recovery bundle into its explicit versioned backup bucket; never modify the DB."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import re
import socket
import ssl
import threading
import time
from urllib.parse import quote, urlsplit
import xml.etree.ElementTree as ET

from postgres_backup import Blocked, private_file, read_private
from recovery_remote_fence import unique
from recovery_remote_inventory import uid
from recovery_remote_outputs import verify as verify_bundle
from recovery_remote_retire import durable_json
from storage_backup import validate_manifest


def private_json(path):
    with read_private(path) as source:
        raw = source.read()
    return json.loads(raw, object_pairs_hook=unique), hashlib.sha256(raw).hexdigest()


def header(headers, name):
    values = [v for k,v in headers if k.lower() == name.lower()]
    if len(values) != 1: raise Blocked('Missing or duplicate storage response header')
    return values[0]


def version(value):
    if not isinstance(value, str) or not value or value == 'null' or len(value) > 1024 or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise Blocked('An explicit immutable storage version is required')
    return value


class Storage:
    def __init__(self, args):
        self.endpoint = urlsplit(os.environ.get('EDGEAI_BACKUP_STORAGE_URL', ''))
        if (self.endpoint.scheme != 'https' or not self.endpoint.hostname or self.endpoint.username or self.endpoint.password or
                self.endpoint.path not in ('', '/') or self.endpoint.query or self.endpoint.fragment):
            raise ValueError('Explicit credential-free HTTPS backup storage origin required')
        self.user = os.environ.get('EDGEAI_BACKUP_STORAGE_USER', '')
        self.password = os.environ.get('EDGEAI_BACKUP_STORAGE_PASSWORD', '')
        if not self.user or not self.password: raise Blocked('Explicit backup storage credentials required')
        if not re.fullmatch('[a-f0-9]{64}', args.certificate_sha256) or not 10 <= args.timeout <= 3600:
            raise ValueError('Storage leaf SHA256 and timeout 10..3600 required')
        self.region = os.environ.get('EDGEAI_BACKUP_STORAGE_REGION', 'us-east-1')
        if not re.fullmatch('[a-z0-9-]+', self.region): raise ValueError('Invalid storage region')
        self.pin = args.certificate_sha256
        self.tls = ssl.create_default_context(cafile=os.environ.get('EDGEAI_BACKUP_CA_FILE'))
        self.tls.minimum_version = ssl.TLSVersion.TLSv1_2
        self.deadline = time.monotonic() + args.timeout

    def request(self, method, path, query=None, body=b'', extra=None, max_bytes=2097152):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0: raise Blocked('Storage publication deadline expired')
        channel = http.client.HTTPSConnection(self.endpoint.hostname, self.endpoint.port or 443,
                                             context=self.tls, timeout=min(10, remaining))
        timer = None
        try:
            channel.connect()
            if hashlib.sha256(channel.sock.getpeercert(binary_form=True)).hexdigest() != self.pin:
                raise Blocked('Storage certificate identity differs')
            wire = channel.sock
            def interrupt():
                try: wire.shutdown(socket.SHUT_RDWR)
                except OSError: pass
            timer = threading.Timer(max(0, self.deadline - time.monotonic()), interrupt)
            timer.daemon = True; timer.start()
            stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
            encoded = '&'.join(quote(k, safe='') + '=' + quote(v, safe='') for k,v in sorted((query or {}).items()))
            path = quote(path, safe='/~')
            payload = hashlib.sha256(body).hexdigest()
            headers = {'host': self.endpoint.netloc, 'x-amz-content-sha256': payload, 'x-amz-date': stamp, **(extra or {})}
            signed = ';'.join(sorted(headers))
            canonical = '\n'.join([method, path, encoded, ''.join(k + ':' + headers[k] + '\n' for k in sorted(headers)), signed, payload])
            scope = stamp[:8] + '/' + self.region + '/s3/aws4_request'
            message = '\n'.join(['AWS4-HMAC-SHA256', stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()])
            key = ('AWS4' + self.password).encode()
            for part in (stamp[:8], self.region, 's3', 'aws4_request'): key = hmac.new(key, part.encode(), hashlib.sha256).digest()
            headers['Authorization'] = ('AWS4-HMAC-SHA256 Credential=' + self.user + '/' + scope + ', SignedHeaders=' + signed +
                                        ', Signature=' + hmac.new(key, message.encode(), hashlib.sha256).hexdigest())
            channel.request(method, path + ('?' + encoded if encoded else ''), body=body or None, headers=headers)
            response = channel.getresponse(); raw = response.read(max_bytes + 1)
            if len(raw) > max_bytes or time.monotonic() >= self.deadline: raise Blocked('Storage response size or deadline exceeded')
            return response.status, response.getheaders(), raw
        finally:
            if timer is not None: timer.cancel()
            channel.close()

    def identity(self):
        code, _, raw = self.request('GET', '/minio/admin/v3/info')
        if code != 200: raise Blocked('Cannot verify target storage installation')
        identity = json.loads(raw, object_pairs_hook=unique)['deploymentID']
        uid(identity)
        return identity

    def versioned(self, bucket):
        code, _, raw = self.request('GET', '/' + bucket, {'versioning': ''})
        if code != 200 or ET.fromstring(raw).findtext('{*}Status') != 'Enabled':
            raise Blocked('Existing target bucket must have versioning enabled')

    def verify(self, item):
        code, headers, raw = self.request('GET', '/' + item['bucket'] + '/' + item['key'],
                                         {'versionId': version(item['versionId'])}, max_bytes=max(8192, item['bytes']))
        if (code != 200 or version(header(headers, 'x-amz-version-id')) != item['versionId'] or
                header(headers, 'Content-Type') != item['mediaType'] or header(headers, 'Content-Length') != str(item['bytes']) or
                any(k.lower() in ('content-encoding', 'transfer-encoding') for k,v in headers) or
                len(raw) != item['bytes'] or hashlib.sha256(raw).hexdigest() != item['sha256']):
            raise Blocked('Pinned storage object differs from recovered output')

    def publish(self, item, raw):
        path = '/' + item['bucket'] + '/' + item['key']
        for _ in range(3):
            code, headers, _ = self.request('HEAD', path)
            if code == 200:
                result = {**item, 'versionId': version(header(headers, 'x-amz-version-id'))}
                self.verify(result)
                return result, False
            if code != 404: raise Blocked('Existing target object cannot be inspected')
            code, headers, _ = self.request('PUT', path, body=raw, extra={
                'content-type': item['mediaType'], 'if-none-match': '*',
                'x-amz-checksum-sha256': base64.b64encode(bytes.fromhex(item['sha256'])).decode(),
                'x-amz-meta-sha256': item['sha256']})
            if code in (409, 412): continue
            if code != 200: raise Blocked('Conditional output publication was rejected')
            result = {**item, 'versionId': version(header(headers, 'x-amz-version-id'))}
            self.verify(result)
            return result, True
        raise Blocked('Concurrent output publication did not settle within its bound')


def items(manifest, bucket):
    for allocation in manifest['allocations']:
        identity = allocation['identity']
        for output in allocation['outputs']:
            yield {'allocationId': identity['allocationId'], 'runId': identity['runId'], 'taskId': identity['taskId'],
                   'attemptId': identity['attemptId'], 'epoch': identity['epoch'], **output, 'bucket': bucket,
                   'key': 'tasks/' + identity['taskId'] + '/attempts/' + identity['attemptId'] + '/' + output['port'] + '/' + output['sha256']}


def inputs(args):
    verified = verify_bundle(args.bundle)
    manifest, manifest_sha = private_json(args.bundle / 'manifest.json')
    backup, backup_sha = private_json(args.storage_backup / 'manifest.json')
    validate_manifest(backup)
    for key in ('sourceDeploymentId', 'targetDeploymentId'): uid(backup[key])
    if (backup['sourceDeploymentId'] == backup['targetDeploymentId'] or
            not re.fullmatch('[a-z0-9][a-z0-9-]{1,61}[a-z0-9]', args.bucket) or args.bucket not in backup['buckets']):
        raise ValueError('Explicit existing backup bucket on a distinct installation required')
    if manifest_sha != verified['manifestSha256']: raise Blocked('Local recovery bundle changed')
    keys = [item['key'] for item in items(manifest, args.bucket)]
    if len(keys) != len(set(keys)): raise ValueError('Multiple allocations cannot claim the same artifact key')
    return manifest, backup, manifest_sha, backup_sha


def publish(storage, args):
    manifest, backup, manifest_sha, backup_sha = inputs(args)
    target = backup['targetDeploymentId']
    if storage.identity() != target: raise Blocked('Backup storage deployment differs')
    storage.versioned(args.bucket)
    desired = list(items(manifest, args.bucket))
    intent = {'formatVersion': 1, 'scope': 'remote-recovery-storage-publication', 'targetDeploymentId': target,
              'remoteBundleSha256': manifest_sha, 'storageBackupSha256': backup_sha,
              'providerId': manifest['providerId'], 'recoveryId': manifest['recoveryId'], 'objects': desired}
    durable_json(args.output / 'intent.json', intent)
    versions, created = [], 0
    for item in desired:
        with read_private(args.bundle / 'objects' / (item['sha256'] + '.bin')) as source: raw = source.read(1048577)
        if len(raw) != item['bytes'] or hashlib.sha256(raw).hexdigest() != item['sha256']:
            raise Blocked('Recovered source file changed before publication')
        result, added = storage.publish(item, raw)
        versions.append(result); created += int(added)
    if storage.identity() != target: raise Blocked('Target storage identity changed')
    storage.versioned(args.bucket)
    if inputs(args)[2:] != (manifest_sha, backup_sha): raise Blocked('Local recovery inputs changed during publication')
    receipt = {**intent, 'status': 'REMOTE_OUTPUTS_PUBLISHED', 'objects': versions, 'createdVersions': created,
               'reusedVersions': len(versions) - created, 'activated': False, 'databaseModified': False,
               'verifiedAt': datetime.now(timezone.utc).isoformat(),
               'excluded': ['result-task-run-commit', 'service-activation', 'global-recovery-acceptance']}
    durable_json(args.output / 'publication.json', receipt)
    return receipt


def verify(storage, args):
    manifest, backup, manifest_sha, backup_sha = inputs(args)
    receipt, _ = private_json(args.receipt)
    desired = list(items(manifest, args.bucket))
    if (type(receipt.get('formatVersion')) is not int or receipt['formatVersion'] != 1 or
            receipt.get('status') != 'REMOTE_OUTPUTS_PUBLISHED' or receipt.get('scope') != 'remote-recovery-storage-publication' or
            receipt.get('activated') is not False or receipt.get('databaseModified') is not False or
            any(receipt.get(key) != manifest[key] for key in ('providerId', 'recoveryId')) or
            receipt.get('remoteBundleSha256') != manifest_sha or receipt.get('storageBackupSha256') != backup_sha or
            receipt.get('targetDeploymentId') != backup['targetDeploymentId'] or
            [{k:v for k,v in item.items() if k != 'versionId'} for item in receipt['objects']] != desired):
        raise ValueError('Publication receipt differs from the complete recovery bundle and backup')
    counts = [receipt.get(key) for key in ('createdVersions', 'reusedVersions')]
    if any(type(count) is not int or count < 0 for count in counts) or sum(counts) != len(desired):
        raise ValueError('Publication receipt counts differ')
    if storage.identity() != backup['targetDeploymentId']: raise Blocked('Target storage identity differs')
    storage.versioned(args.bucket)
    for item in receipt['objects']: storage.verify(item)
    if storage.identity() != backup['targetDeploymentId']: raise Blocked('Target storage identity changed')
    return {'status': 'REMOTE_PUBLISHED_VERSIONS_VERIFIED', 'objects': receipt['objects'], 'activated': False, 'databaseModified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('publish', 'verify'))
    for name in ('bundle', 'storage-backup', 'output'): parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--receipt', type=Path)
    parser.add_argument('--bucket', required=True)
    parser.add_argument('--certificate-sha256', required=True)
    parser.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args()
    if args.action == 'verify' and args.receipt is None: parser.error('verify requires --receipt')
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    try:
        storage = Storage(args)
        report = publish(storage, args) if args.action == 'publish' else verify(storage, args)
        if args.action == 'verify': durable_json(args.output / 'verification.json', report)
        print(report['status'] + ': ' + str(len(report['objects'])) + ' fixed object versions; database unchanged')
        return 0
    except Exception as error:
        blocked = isinstance(error, (Blocked, OSError, http.client.HTTPException))
        status = 'BLOCKED' if blocked else 'FAIL'
        durable_json(args.output / 'failure.json', {'status': status, 'failureType': type(error).__name__,
                     'databaseModified': False, 'activated': False,
                     'instruction': 'Retain any published objects; rerun the same inputs into a new output directory'})
        print(status + ': storage publication; private intent retained; database unchanged')
        return 2 if blocked else 1


if __name__ == '__main__': raise SystemExit(main())
