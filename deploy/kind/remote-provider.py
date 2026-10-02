"""TLS-only launcher for the synthetic provider in an owned disposable test cluster."""
import argparse
from http.server import ThreadingHTTPServer
import os
import signal
import ssl
import threading
from remote_server import Handler, Provider


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('state-dir', 'token-file', 'cert-file', 'key-file'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8443)
    args = parser.parse_args()
    os.umask(0o077)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(args.cert_file, args.key_file)
    provider = Provider(args.state_dir, args.token_file)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.provider = provider
    server.daemon_threads = True
    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        server.serve_forever(poll_interval=.05)
    finally:
        server.server_close()
        provider.close()


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Remote test TLS provider failed; private details suppressed', flush=True)
        raise SystemExit(1)
