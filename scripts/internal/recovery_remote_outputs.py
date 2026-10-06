"""Recover uncommitted Remote success files into a verified private bundle; never activate or modify the DB."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import stat
from urllib.parse import urlencode

from postgres_backup import Blocked, Postgres, private_file, read_private
from recovery_remote_fence import Client, token, unique
from recovery_remote_inventory import uid, validate_item
from recovery_remote_retire import prepare, durable_json


def download(client, credential, args, allocation, output):
    path = '/reference/v1/recovery/allocations/' + allocation + '/outputs/' + output['port'] + '?' + urlencode({
        'providerId': args.provider_id, 'recoveryId': args.recovery_id})
    code, headers, raw = client.request(credential, path=path, max_bytes=max(8192, output['bytes']), raw_response=True)
    headers = [(key.lower(), value) for key, value in headers]
    if (code != 200 or [v for k,v in headers if k == 'content-type'] != [output['mediaType']] or
            [v for k,v in headers if k == 'content-length'] != [str(output['bytes'])] or
            any(k in ('transfer-encoding', 'content-encoding') for k,v in headers) or
            len(raw) != output['bytes'] or hashlib.sha256(raw).hexdigest() != output['sha256']):
        raise Blocked('Remote recovery file differs from the frozen allocation observation')
    return raw


def verify(directory):
    directory = Path(directory)
    if directory.is_symlink() or directory.stat().st_mode & 0o077:
        raise ValueError('Private bundle directory required')
    with read_private(directory / 'manifest.json') as source:
        manifest_bytes = source.read()
        manifest = json.loads(manifest_bytes, object_pairs_hook=unique)
    if (manifest.get('formatVersion') != 1 or manifest.get('scope') != 'restored-reference-remote-output-bundle' or
            manifest.get('status') != 'REMOTE_OUTPUTS_RECOVERED' or manifest.get('activated') is not False or
            manifest.get('databaseModified') is not False or not isinstance(manifest.get('allocations'), list)):
        raise ValueError('Unsupported recovery output manifest')
    for key in ('providerId', 'recoveryId'): uid(manifest[key])
    for key in ('restoreReportSha256', 'providerInventorySha256', 'intentSha256'):
        if not re.fullmatch('[a-f0-9]{64}', manifest.get(key, '')): raise ValueError('Invalid bundle identity digest')
    with read_private(directory / 'intent.json') as source:
        raw_intent = source.read()
        if hashlib.sha256(raw_intent).hexdigest() != manifest['intentSha256']:
            raise ValueError('Recovery intent differs')
        intent = json.loads(raw_intent, object_pairs_hook=unique)
    inventory = intent['inventory']
    selected = [row['provider'] for row in inventory['allocations']
                if row['database']['result_id'] is None and row['provider']['state'] == 'SUCCEEDED']
    retained = sum(row['database']['result_id'] is not None for row in inventory['allocations'])
    if (inventory['status'] != 'OBSERVED_REMOTE_INVENTORY' or manifest['allocations'] != selected or
            manifest.get('committedResultsRetained') != retained or
            any(manifest.get(key) != intent[key] for key in ('targetDatabase', 'databaseOid')) or
            any(manifest[key] != inventory[key] for key in ('restoreReportSha256', 'providerInventorySha256')) or
            any(manifest[key] != inventory['providerStatus'][key] for key in ('providerId', 'recoveryId'))):
        raise ValueError('Bundle differs from the complete matched database/provider inventory')
    descriptors = []
    try:
        descriptors.append(os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW))
        descriptors.append(os.open('objects', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptors[-1]))
        if os.fstat(descriptors[-1]).st_mode & 0o077: raise ValueError('Private object directory required')
        seen, files, total = set(), set(), 0
        for allocation in manifest['allocations']:
            validate_item(allocation)
            identity = allocation['identity']['allocationId']
            if allocation['state'] != 'SUCCEEDED' or identity in seen:
                raise ValueError('Bundle contains duplicate or non-success allocation')
            seen.add(identity)
            for output in allocation['outputs']:
                filename = output['sha256'] + '.bin'
                descriptor = os.open(filename, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptors[-1])
                with os.fdopen(descriptor, 'rb') as source:
                    info = os.fstat(source.fileno())
                    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_size != output['bytes']:
                        raise ValueError('Invalid recovered output file')
                    raw = source.read(1048577)
                if len(raw) != output['bytes'] or hashlib.sha256(raw).hexdigest() != output['sha256']:
                    raise ValueError('Recovered file content differs')
                files.add(filename); total += len(raw)
        if set(os.listdir(descriptors[-1])) != files:
            raise ValueError('Unreferenced or missing bundle object')
    finally:
        for descriptor in reversed(descriptors): os.close(descriptor)
    return {'status': 'REMOTE_OUTPUT_BUNDLE_VERIFIED', 'allocations': len(seen), 'uniqueFiles': len(files),
            'referencedBytes': total, 'manifestSha256': hashlib.sha256(manifest_bytes).hexdigest(),
            'activated': False, 'databaseModified': False, 'sourceContacted': False}


def recover(pg, args):
    plan = prepare(pg, args)
    durable_json(args.output / 'intent.json', plan)
    objects = args.output / 'objects'; objects.mkdir(mode=0o700)
    client, credential = Client(args), token(args.recovery_token_file)
    selected, retained, written = [], 0, set()
    for row in plan['inventory']['allocations']:
        if row['database']['result_id'] is not None:
            retained += 1; continue
        actual = row['provider']
        if actual['state'] != 'SUCCEEDED': continue
        selected.append(actual)
        for output in actual['outputs']:
            raw = download(client, credential, args, row['allocationId'], output)
            filename = output['sha256'] + '.bin'
            if filename not in written:
                with private_file(objects / filename) as target:
                    target.write(raw); target.flush(); os.fsync(target.fileno())
                written.add(filename)
    descriptor = os.open(objects, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(descriptor)
    finally: os.close(descriptor)
    after = prepare(pg, args)
    if (after['databaseOid'] != plan['databaseOid'] or after['marker'] != plan['marker'] or
            after['beforeGuard'] != plan['beforeGuard'] or after['inventory']['providerInventorySha256'] !=
            plan['inventory']['providerInventorySha256']):
        raise Blocked('Database or provider changed while recovering files')
    manifest = {'formatVersion': 1, 'scope': 'restored-reference-remote-output-bundle',
                'status': 'REMOTE_OUTPUTS_RECOVERED', 'activated': False, 'databaseModified': False,
                'targetDatabase': args.database, 'databaseOid': plan['databaseOid'],
                'providerId': args.provider_id, 'recoveryId': args.recovery_id,
                'restoreReportSha256': plan['inventory']['restoreReportSha256'],
                'providerInventorySha256': plan['inventory']['providerInventorySha256'],
                'intentSha256': hashlib.sha256((args.output / 'intent.json').read_bytes()).hexdigest(),
                'allocations': selected, 'committedResultsRetained': retained,
                'createdAt': datetime.now(timezone.utc).isoformat(),
                'excluded': ['s3-publish', 'workflow-result-commit', 'service-activation', 'external-provider-contract']}
    durable_json(args.output / 'manifest.json', manifest)
    try:
        verified = verify(args.output)
    except Exception:
        (args.output / 'manifest.json').unlink()
        raise
    durable_json(args.output / 'verification.json', verified)
    return verified


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='action', required=True)
    checking = subparsers.add_parser('verify')
    checking.add_argument('--input', required=True, type=Path)
    fetching = subparsers.add_parser('recover')
    for name in ('database', 'endpoint', 'certificate-sha256', 'provider-id', 'recovery-id', 'provider-key'):
        fetching.add_argument('--' + name, required=True)
    for name in ('restore-report', 'ca-file', 'recovery-token-file', 'output'):
        fetching.add_argument('--' + name, required=True, type=Path)
    fetching.add_argument('--transport', choices=['native', 'compose'], default='native')
    fetching.add_argument('--pg-bin', type=Path)
    fetching.add_argument('--timeout', type=int, default=120)
    fetching.add_argument('--page-size', type=int, default=100)
    args = parser.parse_args()
    if args.action == 'recover': args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    try:
        if args.action == 'verify': report = verify(args.input)
        else:
            pg = Postgres(args.transport, args.pg_bin, diagnostics=args.output / 'postgres')
            report = recover(pg, args)
        status = 'REMOTE_OUTPUTS_RECOVERED' if args.action == 'recover' else report['status']
        print(status + ': ' + str(report['uniqueFiles']) + ' files; database unchanged')
        return 0
    except Exception as error:
        blocked = isinstance(error, (Blocked, OSError, http.client.HTTPException))
        status = 'BLOCKED' if blocked else 'FAIL'
        if args.action == 'recover':
            durable_json(args.output / 'failure.json', {'status': status, 'failureType': type(error).__name__,
                                                       'activated': False, 'databaseModified': False})
        print(status + ': Remote output bundle; private evidence retained; database unchanged')
        return 2 if blocked else 1


if __name__ == '__main__':
    raise SystemExit(main())
