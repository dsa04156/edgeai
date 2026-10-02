"""Private, actual TLS Mosquitto dynamic-security fixture. Never prints credentials."""
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
root.chmod(0o700)
binary = os.environ.get('EDGEAI_MOSQUITTO_BINARY', 'mosquitto')
control = os.environ.get('EDGEAI_MOSQUITTO_CTRL_BINARY', 'mosquitto_ctrl')
plugin = os.environ.get('EDGEAI_MOSQUITTO_DYNAMIC_SECURITY_PLUGIN', '/usr/lib/x86_64-linux-gnu/mosquitto_dynamic_security.so')
admin = secrets.token_hex(32)
for name, value in [('admin.password', admin), ('principal.key', secrets.token_hex(32))]:
    (root / name).write_text(value)
    (root / name).chmod(0o600)
config = root / 'dynamic-security.json'
result = subprocess.run([control, 'dynsec', 'init', str(config), 'edgeai-admin'],
                        input=admin + '\n' + admin + '\n', text=True, capture_output=True, timeout=10)
if result.returncode:
    raise RuntimeError('Could not initialize isolated dynamic-security broker')
settings = json.loads(config.read_text())
settings['defaultACLAccess'] = dict.fromkeys(('publishClientSend', 'publishClientReceive', 'subscribe', 'unsubscribe'), False)
config.write_text(json.dumps(settings))
config.chmod(0o600)
for name in ('server', 'untrusted'):
    result = subprocess.run(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-days', '1',
                             '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                             '-keyout', str(root / (name + '.key')), '-out', str(root / (name + '.crt'))],
                            capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError('Could not initialize test TLS')
    (root / (name + '.key')).chmod(0o600)
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
broker_config = root / 'mosquitto.conf'
broker_config.write_text(f'listener {port} 127.0.0.1\nallow_anonymous false\n'
                         f'plugin {plugin}\nplugin_opt_config_file {config}\n'
                         f'certfile {root / "server.crt"}\nkeyfile {root / "server.key"}\n'
                         'persistence false\nmax_packet_size 65536\nmax_queued_messages 32\n'
                         'max_queued_bytes 1048576\nmax_inflight_messages 16\nlog_dest stderr\n')
process = None


def stop():
    global process
    if process is not None:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)
        process = None


def start():
    global process
    process = subprocess.Popen([binary, '-c', str(broker_config)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    end = time.monotonic() + 8
    while time.monotonic() < end:
        if process.poll() is not None:
            raise RuntimeError('Isolated dynamic-security broker failed to start')
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=0.1):
                print(port, flush=True)
                return
        except OSError:
            time.sleep(0.02)
    raise RuntimeError('Isolated broker startup timed out')


def terminate(*_):
    raise SystemExit(0)


signal.signal(signal.SIGTERM, terminate)
try:
    start()
    for command in sys.stdin:
        if command.strip() == 'restart':
            stop()
            start()
        elif command.strip() == 'stop':
            break
finally:
    stop()
