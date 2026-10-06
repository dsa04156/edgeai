"""Prepare immutable, recoverable v1 STREAM identities; never activate or rotate a deployment.

All credential-bearing subprocess output stays private. A mode600 recovery bundle is
written before Kubernetes mutation, and a cluster/namespace identity prevents reuse
against a different installation. Existing resources must match in full.
"""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
LABELS = {'app.kubernetes.io/part-of': 'edgeai', 'app.kubernetes.io/managed-by': 'edgeai-bootstrap'}
SECRET_KEYS = {
    'edgeai-stream-ca-v1': {'ca.crt', 'ca.key', 'trust.p12'},
    'edgeai-api-stream-identity-v1': {'tls.crt', 'tls.key', 'admin.password', 'principal.key', 'device.key'},
    'edgeai-mqtt-identity-v1': {'tls.crt', 'tls.key', 'dynamic-security.json'},
    'edgeai-minio-identity-v1': {'tls.crt', 'tls.key'},
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(command, data=None):
    result = subprocess.run(command, input=data, capture_output=True, timeout=90)
    require(result.returncode == 0, 'Preparation operation failed; credential-bearing output suppressed.')
    return result.stdout


def private(path, directory=False):
    value = path.lstat()
    require(not path.is_symlink() and value.st_uid == os.getuid()
            and stat.S_IMODE(value.st_mode) == (0o700 if directory else 0o600)
            and (stat.S_ISDIR(value.st_mode) if directory else stat.S_ISREG(value.st_mode)),
            'Recovery state must be owner-only, owned regular files/directories without symlinks.')


def write_private(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as out:
        out.write(data)
        out.flush()
        os.fsync(out.fileno())


def generate(directory, keytool, control):
    with tempfile.TemporaryDirectory(prefix='issue-', dir=directory) as work:
        temp = Path(work)
        ca, key = temp / 'ca.crt', temp / 'ca.key'
        run(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:3072', '-days', '3650',
             '-subj', '/CN=edgeai-stream-ca-v1', '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
             '-addext', 'keyUsage=critical,keyCertSign,cRLSign', '-keyout', str(key), '-out', str(ca)])
        key.chmod(0o600)
        trust = temp / 'trust.p12'
        # Retain standard Java trust for existing HTTPS Remote providers as well as the new private CA.
        cacerts = Path(keytool).resolve().parents[1] / 'lib/security/cacerts'
        require(cacerts.is_file(), 'The selected JDK must include its standard cacerts trust store.')
        run([keytool, '-importkeystore', '-noprompt', '-srckeystore', str(cacerts), '-srcstorepass', 'changeit',
             '-destkeystore', str(trust), '-deststoretype', 'PKCS12', '-deststorepass', 'changeit'])
        run([keytool, '-importcert', '-noprompt', '-alias', 'edgeai-stream-ca-v1', '-file', str(ca),
             '-keystore', str(trust), '-storepass', 'changeit'])
        values = {'edgeai-stream-ca-v1': {'ca.crt': ca.read_bytes(), 'ca.key': key.read_bytes(), 'trust.p12': trust.read_bytes()}}
        for name, service in [('edgeai-api-stream-identity-v1', 'edgeai-api'),
                              ('edgeai-mqtt-identity-v1', 'edgeai-mqtt'), ('edgeai-minio-identity-v1', 'edgeai-minio')]:
            cert, leaf_key, csr = [temp / (service + extension) for extension in ('.crt', '.key', '.csr')]
            extensions = temp / (service + '.ext')
            extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n'
                'extendedKeyUsage=serverAuth\nsubjectAltName=' + ','.join('DNS:' + host for host in
                (service, service + '.edgeai', service + '.edgeai.svc', service + '.edgeai.svc.cluster.local', 'localhost')) + '\n')
            run(['openssl', 'req', '-new', '-nodes', '-newkey', 'rsa:2048', '-subj', '/CN=' + service,
                 '-keyout', str(leaf_key), '-out', str(csr)])
            leaf_key.chmod(0o600)
            run(['openssl', 'x509', '-req', '-in', str(csr), '-CA', str(ca), '-CAkey', str(key),
                 '-set_serial', str(secrets.randbits(127) + 1), '-days', '365', '-extfile', str(extensions), '-out', str(cert)])
            run(['openssl', 'verify', '-CAfile', str(ca), '-verify_hostname', service + '.edgeai.svc', str(cert)])
            values[name] = {'tls.crt': cert.read_bytes(), 'tls.key': leaf_key.read_bytes()}
        admin = secrets.token_hex(32).encode()
        dynamic = temp / 'dynamic-security.json'
        run([control, 'dynsec', 'init', str(dynamic), 'edgeai-admin'], admin + b'\n' + admin + b'\n')
        policy = json.loads(dynamic.read_bytes())
        policy['defaultACLAccess'] = dict.fromkeys(('publishClientSend', 'publishClientReceive', 'subscribe', 'unsubscribe'), False)
        values['edgeai-mqtt-identity-v1']['dynamic-security.json'] = json.dumps(policy).encode()
        values['edgeai-api-stream-identity-v1'].update({'admin.password': admin, 'principal.key': secrets.token_hex(32).encode(),
                                                     'device.key': secrets.token_hex(32).encode()})
        return {name: {k: base64.b64encode(v).decode() for k, v in content.items()} for name, content in values.items()}


def documents(values):
    require(set(values) == set(SECRET_KEYS), 'Recovery bundle has an unexpected identity contract.')
    for name, keys in SECRET_KEYS.items():
        require(set(values[name]) == keys, 'Identity key contract differs; nothing was rotated.')
        for value in values[name].values():
            require(0 < len(base64.b64decode(value, validate=True)) < 1048576, 'Invalid or oversized identity value.')
    def document(kind, name, namespace='edgeai', **content):
        return {'apiVersion': 'v1', 'kind': kind, 'metadata': {'name': name, 'namespace': namespace, 'labels': LABELS},
                'immutable': True, **content}
    result = [document('Secret', name, type='Opaque', data=content) for name, content in values.items()]
    ca = base64.b64decode(values['edgeai-stream-ca-v1']['ca.crt'], validate=True).decode()
    trust = values['edgeai-stream-ca-v1']['trust.p12']
    for namespace in ('edgeai', 'edgeai-runtimes'):
        result.append(document('ConfigMap', 'edgeai-runtime-ca-v1', namespace, data={'ca.crt': ca},
                               **({'binaryData': {'trust.p12': trust}} if namespace == 'edgeai' else {})))
    broker_config = (ROOT / 'deploy/kubernetes/components/stream/mosquitto.conf').read_bytes()
    digest = hashlib.sha256(b'edgeai-mqtt-v1\nssl://edgeai-mqtt.edgeai.svc:8883\n' + ca.encode() + broker_config).hexdigest()
    settings = {
        'EDGEAI_API_TLS_ENABLED': 'true', 'EDGEAI_API_TLS_PORT': '18443',
        'EDGEAI_API_TLS_CERTIFICATE_FILE': '/tmp/stream-identity/tls.crt',
        'EDGEAI_API_TLS_PRIVATE_KEY_FILE': '/tmp/stream-identity/tls.key',
        'EDGEAI_RUNTIME_CONTROL_PLANE_URL': 'https://edgeai-api.edgeai.svc:18443',
        'EDGEAI_RUNTIME_CA_CONFIG_MAP': 'edgeai-runtime-ca-v1',
        'EDGEAI_STORAGE_URL': 'https://edgeai-minio.edgeai.svc:9000',
        'EDGEAI_STORAGE_RUNNER_URL': 'https://edgeai-minio.edgeai.svc:9000',
        'EDGEAI_STREAM_ENABLED': 'true', 'EDGEAI_STREAM_BINDINGS_ENABLED': 'true', 'EDGEAI_STREAM_RUNS_ENABLED': 'true',
        'EDGEAI_STREAM_BROKER_URL': 'ssl://edgeai-mqtt.edgeai.svc:8883',
        'EDGEAI_STREAM_BROKER_DIGEST': 'sha256:' + digest,
        'EDGEAI_STREAM_ADMIN_USER': 'edgeai-admin',
        'EDGEAI_STREAM_ADMIN_PASSWORD_FILE': '/tmp/stream-identity/admin.password',
        'EDGEAI_STREAM_PRINCIPAL_KEY_FILE': '/tmp/stream-identity/principal.key',
        'EDGEAI_STREAM_DEVICE_KEY_FILE': '/tmp/stream-identity/device.key',
        'EDGEAI_STREAM_CA_FILE': '/var/run/edgeai-trust/ca.crt',
    }
    result.append(document('ConfigMap', 'edgeai-stream-config-v1', data=settings))
    return result


def validate_material(values, directory):
    """Validate restored identities too, before trusting a saved bundle or creating anything."""
    with tempfile.TemporaryDirectory(prefix='validate-', dir=directory) as work:
        temp = Path(work)
        material = {name: {k: base64.b64decode(v, validate=True) for k, v in content.items()} for name, content in values.items()}
        ca = temp / 'ca.crt'
        ca.write_bytes(material['edgeai-stream-ca-v1']['ca.crt'])
        identities = [('edgeai-stream-ca-v1', 'ca.crt', 'ca.key', None)] + [
            (name, 'tls.crt', 'tls.key', service + '.edgeai.svc') for name, service in
            [('edgeai-api-stream-identity-v1', 'edgeai-api'), ('edgeai-mqtt-identity-v1', 'edgeai-mqtt'), ('edgeai-minio-identity-v1', 'edgeai-minio')]]
        keys = []
        for index, (name, cert_name, key_name, hostname) in enumerate(identities):
            cert, key = temp / (str(index) + '.crt'), temp / (str(index) + '.key')
            cert.write_bytes(material[name][cert_name])
            write_private(key, material[name][key_name])
            run(['openssl', 'x509', '-in', str(cert), '-checkend', '86400', '-noout'])
            pub = run(['openssl', 'pkey', '-in', str(key), '-pubout'])
            require(pub == run(['openssl', 'x509', '-in', str(cert), '-pubkey', '-noout']), 'Certificate and private key do not match.')
            keys.append(pub)
            if hostname:
                run(['openssl', 'verify', '-CAfile', str(ca), '-verify_hostname', hostname, '-purpose', 'sslserver', str(cert)])
        require(len(set(keys)) == 4, 'Service and CA private keys must be distinct.')
        for key in ('admin.password', 'principal.key', 'device.key'):
            value = material['edgeai-api-stream-identity-v1'][key]
            require(len(value) == 64 and all(c in b'0123456789abcdef' for c in value), 'Stream secret must be a 256-bit hex value.')
        policy = json.loads(material['edgeai-mqtt-identity-v1']['dynamic-security.json'])
        require(policy['defaultACLAccess'] == dict.fromkeys(('publishClientSend', 'publishClientReceive', 'subscribe', 'unsubscribe'), False),
                'Bootstrap broker policy must deny every default ACL.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    parser.add_argument('--state-dir', type=Path, default=ROOT / '.tools/kubernetes/stream-v1')
    args = parser.parse_args()
    kubectl = ['kubectl', '--context', args.context, '--request-timeout=20s']
    def call(arguments, doc=None):
        raw = run(kubectl + arguments, None if doc is None else json.dumps(doc).encode())
        return json.loads(raw) if raw.strip() else None
    def owned(value):
        return all(value['metadata'].get('labels', {}).get(k) == v for k, v in LABELS.items()) and not value['metadata'].get('deletionTimestamp')
    namespace_uids = {}
    for namespace in ('edgeai', 'edgeai-runtimes'):
        current = call(['get', 'namespace', namespace, '-o', 'json'])
        require(owned(current), 'Refusing an unowned or terminating namespace.')
        namespace_uids[namespace] = current['metadata']['uid']
    directory = args.state_dir.absolute()
    require(not any(p.is_symlink() for p in (directory, *directory.parents)), 'Recovery directory ancestors cannot be symlinks.')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    private(directory, directory=True)
    if directory.is_relative_to(ROOT):
        ignored = subprocess.run(['git', 'check-ignore', '--quiet', str(directory / 'recovery.json')], cwd=ROOT)
        require(ignored.returncode == 0, 'Recovery files inside this repository must be Git-ignored.')
    with os.fdopen(os.open(directory / '.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+') as lock:
        private(directory / '.lock')
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        existing = {}
        for name in SECRET_KEYS:
            value = call(['-n', 'edgeai', 'get', 'secret', name, '--ignore-not-found', '-o', 'json'])
            if value:
                require(owned(value) and value.get('immutable') is True, 'Refusing an unowned or mutable identity Secret.')
                existing[name] = value['data']
        path = directory / 'recovery.json'
        if path.exists() or path.is_symlink():
            private(path)
            saved = json.loads(path.read_bytes())
            require(saved['schema'] == 1 and saved['namespaceUids'] == namespace_uids,
                    'Recovery bundle belongs to a different installation; select its separate state directory.')
            values = saved['secrets']
        elif existing:
            require(set(existing) == set(SECRET_KEYS), 'Partial identities require the original recovery bundle; nothing was regenerated.')
            values = existing
        else:
            keytool = shutil.which('keytool') or str(ROOT / '.tools/jdk/bin/keytool')
            control = os.environ.get('EDGEAI_MOSQUITTO_CTRL_BINARY') or shutil.which('mosquitto_ctrl') or str(ROOT / '.tools/mosquitto/usr/bin/mosquitto_ctrl')
            values = generate(directory, keytool, control)
        resources = documents(values)
        validate_material(values, directory)
        missing = []
        # Inspect every object before the first write; never silently adopt or update data.
        for doc in resources:
            meta = doc['metadata']
            current = call(['-n', meta['namespace'], 'get', doc['kind'], meta['name'], '--ignore-not-found', '-o', 'json'])
            if current:
                require(owned(current) and current.get('immutable') is True and
                        all(current.get(field, {}) == doc.get(field, {}) for field in ('data', 'binaryData')),
                        'Existing stream identity/configuration differs; nothing was overwritten or rotated.')
            else:
                call(['create', '--dry-run=server', '-f', '-', '-o', 'json'], doc)
                missing.append(doc)
        if not path.exists():
            content = json.dumps({'schema': 1, 'namespaceUids': namespace_uids, 'secrets': values}, sort_keys=True).encode() + b'\n'
            temporary = directory / ('recovery-' + secrets.token_hex(8) + '.tmp')
            write_private(temporary, content)
            os.rename(temporary, path)
            fd = os.open(directory, os.O_DIRECTORY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        for doc in missing:
            call(['create', '-f', '-', '-o', 'json'], doc)
        print('PASS: 4 immutable identities and 3 public configuration/trust resources retained; recovery mode600; no rotation or activation.')


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt):
        raise SystemExit('STREAM identity preparation failed; private details suppressed. Existing identities were not changed. Check ownership, recovery files, and tool availability.') from None
