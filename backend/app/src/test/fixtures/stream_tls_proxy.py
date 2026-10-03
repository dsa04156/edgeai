"""Owned loopback TLS termination for real Spring and S3 integration tests.

Preserve the signed Host and request target; never log headers, bodies or URLs.
No protocol replies are simulated by this proxy.
"""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import signal
import ssl
import sys
from urllib.parse import urlsplit

root = Path(sys.argv[1])
target = urlsplit(sys.argv[2])
assert target.scheme == 'http' and target.hostname in ('127.0.0.1', 'localhost')
assert not target.username and not target.password and target.path in ('', '/')
LIMIT = 73 * 1024 * 1024
HOP = {'connection', 'keep-alive', 'transfer-encoding', 'content-length',
       'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'upgrade'}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def forward(self):
        connection = http.client.HTTPConnection(target.hostname, target.port, timeout=15)
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 <= size <= LIMIT or self.headers.get('Transfer-Encoding'):
                self.send_error(400)
                return
            body = self.rfile.read(size)
            if len(body) != size:
                return
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP}
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read(LIMIT + 1)
            if len(payload) > LIMIT:
                self.send_error(502)
                return
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in HOP:
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (OSError, http.client.HTTPException):
            # A killed test client may drop TLS after the backend has committed.
            self.close_connection = True
        finally:
            connection.close()

    do_GET = do_POST = do_PUT = do_HEAD = forward


def stop(*_):
    raise SystemExit(0)


signal.signal(signal.SIGTERM, stop)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(root / 'server.crt', root / 'server.key')
with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(server.server_port, flush=True)
    server.serve_forever(poll_interval=.05)
