"""Verify the dedicated persistent TLS broker, including one UID-guarded Pod replacement.

Requires the v1 bootstrap and stream broker manifests in edgeai. Uses only a unique
empty probe role; leaves all application identities, routes and data untouched.
"""
import argparse
import base64
import json
from pathlib import Path
import queue
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import uuid
import paho.mqtt.client as mqtt

ROOT = Path(__file__).resolve().parents[1]
COMMAND = '$CONTROL/dynamic-security/v1'


def wait(predicate, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(.5)
    raise AssertionError('Persistent broker verification timed out')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/stream-platform-broker.json')
    args = parser.parse_args()
    k = ['kubectl', '--context', args.context, '--request-timeout=20s']
    def read(kind, name):
        p = subprocess.run(k + ['-n', 'edgeai', 'get', kind, name, '--ignore-not-found', '-o', 'json'], capture_output=True, check=True)
        return json.loads(p.stdout) if p.stdout.strip() else None
    namespace = read('namespace', 'edgeai')
    assert namespace['metadata']['labels']['app.kubernetes.io/managed-by'] == 'edgeai-bootstrap'
    owner = read('statefulset', 'edgeai-mqtt')
    assert owner['metadata']['labels']['app.kubernetes.io/part-of'] == 'edgeai'
    assert owner['metadata']['labels']['app.kubernetes.io/managed-by'] == 'edgeai-bootstrap'
    assert owner['spec']['replicas'] == 1
    pinned = 'sha256:38c0da4f2ef84284d47b3b3eeea1cb3bdeabe81ee10caf0cd5c5ff61ee3ea408'
    assert owner['spec']['template']['spec']['containers'][0]['image'].endswith('@' + pinned)
    claim = wait(lambda: read('pvc', 'data-edgeai-mqtt-0'))
    assert claim['status']['phase'] == 'Bound'
    def ready(previous=None):
        pod = read('pod', 'edgeai-mqtt-0')
        if not pod or pod['metadata'].get('deletionTimestamp') or pod['metadata']['uid'] == previous:
            return None
        assert pod['metadata']['ownerReferences'][0]['uid'] == owner['metadata']['uid']
        if any(c['type'] == 'Ready' and c['status'] == 'True' for c in pod['status'].get('conditions', [])):
            assert pod['status']['containerStatuses'][0]['imageID'].endswith('@' + pinned)
            return pod
        return None
    first = wait(ready)
    ca = base64.b64decode(read('secret', 'edgeai-stream-ca-v1')['data']['ca.crt'])
    password = base64.b64decode(read('secret', 'edgeai-api-stream-identity-v1')['data']['admin.password']).decode()
    probe = 'edgeai-bootstrap-probe-' + uuid.uuid4().hex
    report = {'scope': 'persistent-deployment-tls-broker', 'podUids': [first['metadata']['uid']], 'pvcUid': claim['metadata']['uid']}
    with tempfile.TemporaryDirectory(prefix='broker-proof-', dir=ROOT / '.tools') as work:
        ca_path = Path(work) / 'ca.crt'
        ca_path.write_bytes(ca)
        forwards = []
        def connect():
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0))
                port = sock.getsockname()[1]
            proc = subprocess.Popen(k + ['-n', 'edgeai', 'port-forward', '--address', '127.0.0.1', 'pod/edgeai-mqtt-0', str(port) + ':8883'],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            forwards.append(proc)
            tls = ssl.create_default_context(cafile=str(ca_path))
            def tls_ready():
                assert proc.poll() is None, 'Broker port-forward stopped'
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=2) as raw, tls.wrap_socket(raw, server_hostname='localhost'):
                        return True
                except (OSError, ssl.SSLError):
                    return False
            wait(tls_ready, 20)
            # A certificate from our CA is required; ordinary public roots must reject it.
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=2) as raw, ssl.create_default_context().wrap_socket(raw, server_hostname='localhost'):
                    raise AssertionError('Untrusted broker certificate was accepted')
            except ssl.SSLCertVerificationError:
                pass
            for username in (None, 'edgeai-admin'):
                rejected = threading.Event()
                denied = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='edgeai-denied-' + uuid.uuid4().hex, protocol=mqtt.MQTTv5)
                denied.tls_set_context(tls)
                if username:
                    denied.username_pw_set(username, uuid.uuid4().hex)
                denied.on_connect = lambda c, u, flags, reason, props: rejected.set() if reason.is_failure else None
                try:
                    denied.connect('localhost', port, keepalive=15)
                    denied.loop_start()
                    assert rejected.wait(5), 'Anonymous or incorrect broker credential was accepted'
                finally:
                    denied.disconnect();denied.loop_stop()
            client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='edgeai-bootstrap-' + uuid.uuid4().hex, protocol=mqtt.MQTTv5)
            client.tls_set_context(tls)
            client.username_pw_set('edgeai-admin', password)
            connected, subscribed, messages = threading.Event(), threading.Event(), queue.Queue()
            def on_connect(c, userdata, flags, code, properties):
                if code == 0:
                    connected.set()
            client.on_connect = on_connect
            client.on_subscribe = lambda c, u, mid, reasons, properties: subscribed.set() if all(int(r.value) < 128 for r in reasons) else None
            client.on_message = lambda c, u, message: messages.put(json.loads(message.payload))
            client.connect('localhost', port, keepalive=15)
            client.loop_start()
            assert connected.wait(10), 'TLS broker admin authentication failed'
            client.subscribe(COMMAND + '/response', qos=1)
            assert subscribed.wait(10), 'TLS broker admin subscription failed'
            def command(name, **values):
                correlation = uuid.uuid4().hex
                client.publish(COMMAND, json.dumps({'commands': [{'command': name, 'correlationData': correlation, **values}]}), qos=1).wait_for_publish(10)
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    for response in messages.get(timeout=10)['responses']:
                        if response.get('correlationData') == correlation:
                            assert response['command'] == name and 'error' not in response, 'Broker management command rejected; private response suppressed'
                            return response
                raise AssertionError('Broker management response timed out')
            return client, command
        client = None
        try:
            client, command = connect()
            defaults = command('getDefaultACLAccess')['data']['acls']
            assert {a['acltype']: a['allow'] for a in defaults} == dict.fromkeys(('publishClientSend', 'publishClientReceive', 'subscribe', 'unsubscribe'), False)
            command('createRole', rolename=probe, acls=[])
            original = command('getRole', rolename=probe)['data']['role']
            assert original['rolename'] == probe and not original.get('acls')
            client.disconnect();client.loop_stop();client = None
            for proc in forwards:
                proc.terminate();proc.wait(timeout=10)
            forwards.clear()
            body = {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': first['metadata']['uid']}}
            subprocess.run(k + ['delete', '--raw', '/api/v1/namespaces/edgeai/pods/edgeai-mqtt-0', '-f', '-'],
                           input=json.dumps(body), text=True, capture_output=True, check=True)
            second = wait(lambda: ready(first['metadata']['uid']))
            report['podUids'].append(second['metadata']['uid'])
            assert read('pvc', 'data-edgeai-mqtt-0')['metadata']['uid'] == claim['metadata']['uid']
            client, command = connect()
            restored = command('getRole', rolename=probe)['data']['role']
            assert restored == original, 'Broker role changed or disappeared after Pod replacement'
            defaults = command('getDefaultACLAccess')['data']['acls']
            assert all(not a['allow'] for a in defaults)
            command('deleteRole', rolename=probe)
            report.update(defaultDenyPreserved=True, rolePreserved=True, trustedTls=True, untrustedTlsRejected=True, invalidCredentialsRejected=True,
                          imageIds=[p['status']['containerStatuses'][0]['imageID'] for p in (first, second)])
        finally:
            if client:
                client.disconnect();client.loop_stop()
            for proc in forwards:
                proc.terminate();proc.wait(timeout=10)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: actual persistent TLS broker; default-deny; CA validation; admin authentication; same PVC/role across new Pod UID; probe role removed')


if __name__ == '__main__':
    main()
