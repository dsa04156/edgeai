"""Actual TLS launcher, private credentials, durable cancellation and trust verification."""
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]


class TlsFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='edgeai-tls-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.token = secrets.token_urlsafe(32)
        (self.root / 'token').write_text(self.token)
        (self.root / 'token').chmod(0o600)
        result = subprocess.run(['openssl','req','-x509','-nodes','-newkey','rsa:2048','-days','1',
            '-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1',
            '-keyout',str(self.root/'key.pem'),'-out',str(self.root/'cert.pem')],capture_output=True,timeout=15)
        self.assertEqual(0,result.returncode,'Test certificate generation failed; private output suppressed')
        (self.root / 'key.pem').chmod(0o600)
        self.tls = ssl.create_default_context(cafile=str(self.root/'cert.pem'))
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));self.port=sock.getsockname()[1]
        self.process = None
        self.addCleanup(self.stop)
        self.start()

    def start(self):
        self.process = subprocess.Popen(['python3',str(ROOT/'deploy/kind/remote-provider.py'),
            '--host','127.0.0.1','--port',str(self.port),'--state-dir',str(self.root/'state'),
            '--token-file',str(self.root/'token'),'--cert-file',str(self.root/'cert.pem'),'--key-file',str(self.root/'key.pem'),
            *self.provider_options()],
            env={**os.environ,'PYTHONPATH':str(ROOT/'simulator'),'PYTHONDONTWRITEBYTECODE':'1'},stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        deadline=time.monotonic()+10
        while time.monotonic()<deadline and self.process.poll() is None:
            try:
                if self.request('/')[0]==401:return
            except OSError:pass
            time.sleep(.05)
        self.fail('TLS fixture did not become ready; private output suppressed')

    def provider_options(self):
        return []

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=5)

    def request(self,path,method='GET',headers=None):
        request=urllib.request.Request('https://127.0.0.1:'+str(self.port)+path,method=method,
            headers=headers or {},data=b'' if method=='POST' else None)
        try:response=urllib.request.urlopen(request,context=self.tls,timeout=3)
        except urllib.error.HTTPError as error:response=error
        with response:return response.status,json.loads(response.read(262144))

    def test_requires_trusted_tls_and_valid_bearer(self):
        self.assertEqual(401,self.request('/')[0])
        with self.assertRaises(urllib.error.URLError):
            urllib.request.urlopen('https://127.0.0.1:'+str(self.port),timeout=3)
        with socket.create_connection(('127.0.0.1',self.port),timeout=3) as sock:
            with self.assertRaises(ssl.SSLCertVerificationError):self.tls.wrap_socket(sock,server_hostname='wrong.example.test')
        self.assertEqual(404,self.request('/',headers={'Authorization':'Bearer '+self.token})[0])
        self.assertEqual(404,self.request('/reference/v1/recovery',headers={'Authorization':'Bearer '+self.token})[0])
        self.token=secrets.token_urlsafe(32);(self.root/'token').write_text(self.token)
        self.assertEqual(404,self.request('/',headers={'Authorization':'Bearer '+self.token})[0])

    def test_tls_provider_keeps_cancel_tombstone_across_process_restart(self):
        identity={name:str(uuid.uuid4()) for name in ('allocationId','runId','taskId','attemptId')}
        headers={'Authorization':'Bearer '+self.token,'X-EdgeAI-Run-Id':identity['runId'],
            'X-EdgeAI-Task-Id':identity['taskId'],'X-EdgeAI-Attempt-Id':identity['attemptId'],'X-EdgeAI-Epoch':'1'}
        path='/reference/v1/allocations/'+identity['allocationId']
        status,before=self.request(path+'/cancel','POST',headers)
        self.assertEqual(200,status);self.assertEqual('CANCELLED',before['state'])
        self.stop();self.start()
        status,after=self.request(path,headers=headers)
        self.assertEqual(200,status);self.assertEqual(before,after)


if __name__=='__main__':
    unittest.main()
