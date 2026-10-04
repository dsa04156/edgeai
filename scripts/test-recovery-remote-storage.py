"""Publish an actual PG/TLS-produced Remote bundle into two owned TLS MinIO installations."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import traceback
from types import SimpleNamespace
import urllib.request
import uuid

from postgres_backup import ROOT, private_file
from recovery_remote_outputs import verify as verify_bundle
from recovery_remote_retire import durable_json
from recovery_remote_storage import Storage, publish
from storage_backup import Client, backup, verify as verify_backup


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--minio-binary', type=Path, default=ROOT / '.tools/minio')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/recovery-remote-storage-test.json')
    args = parser.parse_args()
    work = ROOT / '.tools' / ('recovery-remote-storage-test-' + uuid.uuid4().hex); work.mkdir(mode=0o700)
    processes, identities = [], {}
    env = {k:v for k,v in os.environ.items() if not k.startswith(('MC_', 'EDGEAI_DB_', 'PG'))}
    env.update(MC_CONFIG_DIR=str(work / 'mc'), MC_NO_COLOR='1', MC_DISABLE_PAGER='1', EDGEAI_BACKUP_CA_FILE=str(work / 'ca.crt'))
    previous_env = os.environ.copy()
    bucket = 'edgeai-recovery-artifacts'
    report = {'status': 'RUNNING', 'scope': 'remote-recovery-storage-publication-tests', 'cases': [], 'sourceMode': 'SYNTHETIC'}

    def run(command, body=None, expected=0, environment=None):
        result = subprocess.run(list(map(str, command)), input=body, capture_output=True, timeout=300, env=environment or env)
        with private_file(work / ('command-' + uuid.uuid4().hex + '.log')) as output: output.write(result.stdout + result.stderr)
        assert result.returncode == expected, 'Storage test command outcome differs; private diagnostics retained'
        return result

    def mc(arguments, body=None): return run([ROOT / '.tools/mc', '--json', *arguments], body)

    def start(name):
        certs = work / (name + '-certs')
        if name not in identities:
            identities[name] = ('recovery-publish-test', secrets.token_hex(24))
            certs.mkdir(mode=0o700); ca_dir = certs / 'CAs'; ca_dir.mkdir(mode=0o700)
            shutil.copyfile(work / 'ca.crt', ca_dir / 'root.crt')
            extensions = certs / 'extensions'
            extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n')
            run(['openssl', 'req', '-new', '-nodes', '-newkey', 'rsa:2048', '-subj', '/CN=localhost',
                 '-keyout', certs / 'private.key', '-out', certs / 'leaf.csr'])
            (certs / 'private.key').chmod(0o600)
            run(['openssl', 'x509', '-req', '-in', certs / 'leaf.csr', '-CA', work / 'ca.crt', '-CAkey', work / 'ca.key',
                 '-set_serial', str(secrets.randbits(127) + 1), '-days', '1', '-extfile', extensions, '-out', certs / 'public.crt'])
        user, password = identities[name]
        with socket.socket() as api, socket.socket() as console:
            api.bind(('127.0.0.1', 0)); console.bind(('127.0.0.1', 0))
            port, console_port = api.getsockname()[1], console.getsockname()[1]
        origin = 'https://localhost:' + str(port)
        env['MC_HOST_' + name] = 'https://' + user + ':' + password + '@localhost:' + str(port)
        prefix = 'EDGEAI_BACKUP_SOURCE_' if name == 'origin' else 'EDGEAI_BACKUP_STORAGE_'
        env.update({prefix + 'URL': origin, prefix + 'USER': user, prefix + 'PASSWORD': password})
        with private_file(work / (name + '-' + uuid.uuid4().hex + '.log')) as log:
            process = subprocess.Popen([str(args.minio_binary.resolve()), 'server', str(work / (name + '-data')),
                '--address', '127.0.0.1:' + str(port), '--console-address', '127.0.0.1:' + str(console_port),
                '--certs-dir', str(certs), '--quiet'], stdout=log, stderr=log,
                env={**env, 'MINIO_ROOT_USER': user, 'MINIO_ROOT_PASSWORD': password})
        processes.append(process)
        tls = ssl.create_default_context(cafile=str(work / 'ca.crt')); deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            assert process.poll() is None, 'Owned storage process exited'
            try:
                with urllib.request.urlopen(origin + '/minio/health/live', context=tls, timeout=1) as response:
                    if response.status == 200: return process
            except OSError: time.sleep(.1)
        raise AssertionError('Owned storage readiness timed out')

    def options():
        return SimpleNamespace(bundle=args.bundle, storage_backup=work / 'backup', bucket=bucket,
                               certificate_sha256=pin, timeout=120, output=work / ('publication-' + uuid.uuid4().hex))

    def cli(a, action='publish', expected=0, receipt=None, extra=(), environment=None):
        command = [sys.executable, 'scripts/recovery_remote_storage.py', action]
        for key, value in vars(a).items(): command += ['--' + key.replace('_', '-'), str(value)]
        if receipt is not None: command += ['--receipt', str(receipt)]
        result = run(command + list(extra), expected=expected, environment=environment)
        for _, password in identities.values(): assert password.encode() not in result.stdout + result.stderr
        if expected: return None
        return json.loads((a.output / ('publication.json' if action == 'publish' else 'verification.json')).read_text())

    def passed(name): report['cases'].append(name); print('PASS: ' + name, flush=True)

    code = 1
    try:
        original_bundle = verify_bundle(args.bundle)
        assert original_bundle['allocations'] == 1 and original_bundle['uniqueFiles'] == 1
        run(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-days', '1', '-subj', '/CN=recovery-storage-test',
             '-addext', 'basicConstraints=critical,CA:TRUE', '-keyout', work / 'ca.key', '-out', work / 'ca.crt'])
        (work / 'ca.key').chmod(0o600)
        ca = work / 'mc/certs/CAs'; ca.mkdir(mode=0o700, parents=True); shutil.copyfile(work / 'ca.crt', ca / 'root.crt')
        original = start('origin'); replica = start('replica')
        pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((work / 'replica-certs/public.crt').read_text())).hexdigest()
        mc(['mb', 'origin/' + bucket]); mc(['version', 'enable', 'origin/' + bucket])
        for value in (b'original fixed version', b'later original version'): mc(['pipe', 'origin/' + bucket + '/retained.bin'], value)
        mc(['pipe', 'origin/' + bucket + '/empty.bin'], b'')
        os.environ.clear(); os.environ.update(env)
        backup_dir = work / 'backup'; backup_dir.mkdir(mode=0o700)
        backup_client = Client(backup_dir, source=True)
        backup(backup_client, [bucket], 120)
        backup_manifest = json.loads((backup_dir / 'manifest.json').read_text())
        assert len(backup_manifest['versions']) == 3
        original.terminate(); original.wait(timeout=15)
        verify_backup(backup_client, backup_manifest)
        passed('actual TLS replication preserves three original versions on a distinct installation with original storage offline')

        a = options(); receipt = cli(a)
        assert receipt['createdVersions'] == 1 and receipt['reusedVersions'] == 0
        item = receipt['objects'][0]
        assert len(item['versionId']) > 0 and item['versionId'] != 'null'
        assert item['key'] == 'tasks/' + item['taskId'] + '/attempts/' + item['attemptId'] + '/' + item['port'] + '/' + item['sha256']
        assert (a.output / 'publication.json').stat().st_mode & 0o777 == 0o600
        cli(options(), action='verify', receipt=a.output / 'publication.json')
        assert len(backup_client.versions('replica', bucket)) == 4
        passed('actual PG/Remote bundle publishes its file into the original bucket and canonical key with verified immutable version')

        repeat = cli(options())
        assert repeat['createdVersions'] == 0 and repeat['objects'] == receipt['objects']
        assert len(backup_client.versions('replica', bucket)) == 4
        passed('same bundle reuses the exact existing version without a second PUT')

        def remove_test_version(target_item):
            assert target_item['key'] == item['key'] and target_item['bucket'] == bucket
            mc(['rm', '--force', '--version-id', target_item['versionId'], 'replica/' + bucket + '/' + target_item['key']])
        remove_test_version(item)
        lost = options(); lost.output.mkdir(mode=0o700)
        class LostReply(Storage):
            def request(self, method, *positional, **keywords):
                result = super().request(method, *positional, **keywords)
                if method == 'PUT' and result[0] == 200: raise OSError('Injected lost successful PUT reply')
                return result
        try: publish(LostReply(lost), lost)
        except OSError: pass
        else: raise AssertionError('Lost PUT response not injected')
        assert (lost.output / 'intent.json').exists() and not (lost.output / 'publication.json').exists()
        resumed = cli(options())
        assert resumed['createdVersions'] == 0 and len(backup_client.versions('replica', bucket)) == 4
        passed('actual successful PUT with lost reply retains intent and is reused by fresh recovery without duplicate versions')

        remove_test_version(resumed['objects'][0])
        barrier = threading.Barrier(2)
        class Racing(Storage):
            waited = False
            def request(self, method, *positional, **keywords):
                result = super().request(method, *positional, **keywords)
                if method == 'HEAD' and not self.waited:
                    self.waited = True; assert result[0] == 404; barrier.wait(timeout=10)
                return result
        first, second = options(), options()
        first.output.mkdir(mode=0o700); second.output.mkdir(mode=0o700)
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(publish, Racing(option), option) for option in (first, second)]
            race = [future.result(timeout=60) for future in futures]
        assert sum(result['createdVersions'] for result in race) == 1
        assert race[0]['objects'] == race[1]['objects'] and len(backup_client.versions('replica', bucket)) == 4
        fixed_receipt = first.output / 'publication.json'; fixed_item = race[0]['objects'][0]
        passed('two real TLS writers observe absence concurrently and conditional PUT creates exactly one version')

        for extra, environment in ((['--certificate-sha256', '0' * 64], None),
                                   ([], {**env, 'EDGEAI_BACKUP_CA_FILE': ssl.get_default_verify_paths().cafile})):
            cli(options(), expected=2, extra=extra, environment=environment)
        original = start('origin')
        wrong_target = {**env, **{'EDGEAI_BACKUP_STORAGE_' + suffix: env['EDGEAI_BACKUP_SOURCE_' + suffix] for suffix in ('URL', 'USER', 'PASSWORD')}}
        origin_pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((work / 'origin-certs/public.crt').read_text())).hexdigest()
        cli(options(), expected=2, extra=['--certificate-sha256', origin_pin], environment=wrong_target)
        assert len(backup_client.versions('replica', bucket)) == 4
        passed('wrong actual TLS pin, CA and storage installation cannot publish any object')

        storage = Storage(options())
        code_put, _, _ = storage.request('PUT', '/' + bucket + '/' + item['key'], body=b'owned conflicting latest fixture',
                                         extra={'content-type': item['mediaType']})
        assert code_put == 200
        versions_with_conflict = backup_client.versions('replica', bucket)
        cli(options(), expected=2)
        assert backup_client.versions('replica', bucket) == versions_with_conflict
        cli(options(), action='verify', receipt=fixed_receipt)
        passed('a conflicting existing latest object is preserved and rejected while the exact previously published version still verifies')

        mc(['version', 'suspend', 'replica/' + bucket]); cli(options(), expected=2)
        mc(['version', 'enable', 'replica/' + bucket]); cli(options(), action='verify', receipt=fixed_receipt)
        passed('suspended bucket versioning blocks publication without altering existing versions')

        replica.kill(); replica.wait(timeout=10); replica = start('replica')
        os.environ.clear(); os.environ.update(env)
        cli(options(), action='verify', receipt=fixed_receipt)
        verify_dir = work / 'after-restart'; verify_dir.mkdir(mode=0o700)
        backup_client = Client(verify_dir, source=True)
        verify_backup(backup_client, backup_manifest)
        passed('actual target SIGKILL restart preserves installation identity, new fixed output version and all original versions')

        for mode in ('task', 'provider', 'recovery', 'counts'):
            changed = json.loads(fixed_receipt.read_text())
            if mode == 'task': changed['objects'][0]['taskId'] = str(uuid.uuid4())
            elif mode == 'provider': changed['providerId'] = str(uuid.uuid4())
            elif mode == 'recovery': changed['recoveryId'] = str(uuid.uuid4())
            else: changed['createdVersions'] = 100
            wrong_receipt = work / ('wrong-publication-' + mode + '.json'); durable_json(wrong_receipt, changed)
            cli(options(), action='verify', receipt=wrong_receipt, expected=1)
        assert verify_bundle(args.bundle) == original_bundle
        passed('publication receipts cannot remap task/provider/recovery or counts and the original private bundle remains unchanged')

        damaged = work / 'damaged-bundle'; shutil.copytree(args.bundle, damaged)
        next((damaged / 'objects').glob('*.bin')).write_bytes(b'owned invalid source fixture')
        unchanged = backup_client.versions('replica', bucket)
        cli(options(), expected=1, extra=['--bundle', str(damaged)])
        assert backup_client.versions('replica', bucket) == unchanged and verify_bundle(args.bundle) == original_bundle
        passed('damaged local source bundle is rejected before storage mutation without altering the original input')

        remove_test_version(fixed_item)
        cli(options(), action='verify', receipt=fixed_receipt, expected=2)
        verify_backup(backup_client, backup_manifest)
        passed('missing exact output version is rejected even when another latest object exists; original backup versions remain intact')
        report.update(status='PASS', sourceBundleAllocations=original_bundle['allocations'], publishedFiles=1,
                      publishedBytes=original_bundle['referencedBytes'], preservedOriginalVersions=3,
                      concurrentNewVersions=1, databaseModified=False,
                      minioBinarySha256=hashlib.sha256(args.minio_binary.read_bytes()).hexdigest())
        code = 0
    except Exception as error:
        report.update(status='FAIL', failureType=type(error).__name__)
        with private_file(work / 'failure.log', 'w') as log: traceback.print_exc(file=log)
        print('FAIL: actual Remote storage publication; private diagnostics: ' + str(work), flush=True)
    finally:
        os.environ.clear(); os.environ.update(previous_env)
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        report['ownedProcessesStopped'] = all(process.poll() is not None for process in processes)
        args.report.parent.mkdir(parents=True, exist_ok=True); args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'] + ': ' + str(len(report['cases'])) + ' actual Remote storage publication cases', flush=True)
    return code


if __name__ == '__main__': raise SystemExit(main())
