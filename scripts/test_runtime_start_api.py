"""Obtain a production start journal over TLS using an actual Pod-bound Kubernetes token."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import runpy
import secrets
import socket
import subprocess
from types import SimpleNamespace
import urllib.error
import urllib.request

from postgres_backup import ROOT, literal, private_file
from recovery_remote_storage import Storage, header

Api=runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']


def admit(fixture,pg,database,context,pod,kube,create,namespace,on_admitted=None):
    runtime=context['runtime'];work=fixture.work
    def capture(arguments,document=None):
        result=subprocess.run(kube.command+arguments,input=None if document is None else json.dumps(document).encode(),
            capture_output=True,timeout=30)
        assert result.returncode==0,'Actual API claim Kubernetes setup failed; private output suppressed'
        return result.stdout
    def private(name,value):
        path=work/name
        with private_file(path) as target:target.write(value)
        return str(path)
    # Grant only get in the owned test namespace; reuse existing cluster-wide
    # node/TokenReview permissions of the bootstrapped control-plane account.
    labels={'app.kubernetes.io/part-of':'edgeai','app.kubernetes.io/managed-by':'edgeai-bootstrap'}
    create({'apiVersion':'rbac.authorization.k8s.io/v1','kind':'Role',
        'metadata':{'namespace':namespace,'name':'start-claim-read','labels':labels},
        'rules':[{'apiGroups':[''],'resources':['pods'],'verbs':['get']},
                 {'apiGroups':['batch'],'resources':['jobs'],'verbs':['get']}]})
    create({'apiVersion':'rbac.authorization.k8s.io/v1','kind':'RoleBinding',
        'metadata':{'namespace':namespace,'name':'start-claim-read','labels':labels},
        'roleRef':{'apiGroup':'rbac.authorization.k8s.io','kind':'Role','name':'start-claim-read'},
        'subjects':[{'kind':'ServiceAccount','namespace':'edgeai','name':'edgeai-control-plane'}]})
    server=capture(['config','view','--minify','-o','jsonpath={.clusters[0].cluster.server}']).decode().strip()
    ca=base64.b64decode(capture(['config','view','--minify','--raw','-o',
        'jsonpath={.clusters[0].cluster.certificate-authority-data}']),validate=True)
    assert server.startswith('https://') and ca
    ca_file=private('kube-ca.crt',ca)
    control_token=private('kube-token',capture(['-n','edgeai','create','token','edgeai-control-plane','--duration=10m']))
    signing_key=secrets.token_hex(32);key_file=private('runner.key',signing_key.encode())
    fixture.addCleanup(Path(control_token).unlink,missing_ok=True)
    fixture.addCleanup(Path(key_file).unlink,missing_ok=True)
    account=pod['spec']['serviceAccountName']
    request={'apiVersion':'authentication.k8s.io/v1','kind':'TokenRequest','spec':{
        'audiences':['edgeai-runner'],'expirationSeconds':600,
        'boundObjectRef':{'apiVersion':'v1','kind':'Pod','name':pod['metadata']['name'],'uid':pod['metadata']['uid']}}}
    pod_token=json.loads(capture(['create','--raw','/api/v1/namespaces/'+namespace+'/serviceaccounts/'+account+'/token','-f','-'],request))['status']['token']
    keytool=ROOT/'.tools/jdk/bin/keytool'
    trust=work/'start-api-trust.p12'
    result=subprocess.run([str(keytool) if keytool.exists() else 'keytool','-importcert','-noprompt',
        '-alias','edgeai-start-test','-file',str(fixture.root/'cert.pem'),'-keystore',str(trust),
        '-storetype','PKCS12','-storepass','changeit'],capture_output=True,timeout=30)
    assert result.returncode==0,'Owned Java TLS trust preparation failed'
    with socket.socket() as probe:probe.bind(('127.0.0.1',0));tls_port=probe.getsockname()[1]
    api=Api(database,work,extra_env={
        'EDGEAI_RUNTIME_ENABLED':'true','EDGEAI_RUNTIME_WORKER_ENABLED':'false','EDGEAI_RUNTIME_NAMESPACE':namespace,
        'EDGEAI_RUNTIME_SERVICE_ACCOUNT':account,'EDGEAI_RUNNER_KEY_FILE':key_file,
        'EDGEAI_KUBE_API_URL':server,'EDGEAI_KUBE_TOKEN_FILE':control_token,'EDGEAI_KUBE_CA_FILE':ca_file,
        'EDGEAI_STORAGE_URL':fixture.environment['EDGEAI_BACKUP_SOURCE_URL'],
        'EDGEAI_STORAGE_RUNNER_URL':fixture.environment['EDGEAI_BACKUP_SOURCE_URL'],
        'EDGEAI_MINIO_USER':fixture.environment['EDGEAI_BACKUP_SOURCE_USER'],
        'EDGEAI_MINIO_PASSWORD':fixture.environment['EDGEAI_BACKUP_SOURCE_PASSWORD'],
        'EDGEAI_ARTIFACT_BUCKET':fixture.bucket,
        'EDGEAI_API_TLS_ENABLED':'true','EDGEAI_API_TLS_PORT':str(tls_port),
        'EDGEAI_API_TLS_CERTIFICATE_FILE':str(fixture.root/'cert.pem'),'EDGEAI_API_TLS_PRIVATE_KEY_FILE':str(fixture.root/'key.pem'),
        'JAVA_TOOL_OPTIONS':'-Djavax.net.ssl.trustStore='+str(trust)+' -Djavax.net.ssl.trustStorePassword=changeit'})
    fixture.addCleanup(api.close)
    nonce=pg.sql('SELECT claim_nonce::text FROM edgeai.runtime_instance WHERE id='+literal(runtime['id'])+'::uuid',database)
    message='edgeai-runner-v1\n'+namespace+'\n'+runtime['attempt_id']+'\n'+str(runtime['epoch'])+'\n'+nonce
    token='v1.'+base64.urlsafe_b64encode(hmac.new(bytes.fromhex(signing_key),message.encode(),hashlib.sha256).digest()).decode().rstrip('=')
    origin='https://localhost:'+str(tls_port)
    def request(path,extra,expected,proof=pod_token):
        request=urllib.request.Request(origin+'/internal/v1/attempts/'+runtime['attempt_id']+'/'+path,method='POST',
            data=json.dumps({'epoch':runtime['epoch'],'podUid':pod['metadata']['uid'],**extra}).encode(),
            headers={'Authorization':'Bearer '+token,'X-EdgeAI-Pod-Token':proof,'Content-Type':'application/json'})
        try:response=urllib.request.urlopen(request,context=fixture.tls,timeout=15)
        except urllib.error.HTTPError as error:response=error
        with response:
            assert response.status==expected,'Actual TLS Runner '+path+' returned HTTP '+str(response.status)
            raw=response.read()
            return json.loads(raw) if raw else None
    # The source storage is intentionally inspected with its own explicit identity.
    from unittest.mock import patch
    origin_env={key.replace('EDGEAI_BACKUP_SOURCE_','EDGEAI_BACKUP_STORAGE_'):value
        for key,value in fixture.environment.items() if key.startswith('EDGEAI_BACKUP_SOURCE_')}
    try:
        with patch.dict(os.environ,{**fixture.environment,**origin_env}):
            store=Storage(SimpleNamespace(certificate_sha256=fixture.pin,timeout=60))
            path='/'+fixture.bucket+'/authority/runtime-start/'+runtime['id']+'.json'
            assert store.request('HEAD',path)[0]==404
            request('claim',{},401,'invalid-pod-proof')
            assert store.request('HEAD',path)[0]==404
            response=request('claim',{},200)
            assert response['attemptId']==runtime['attempt_id'] and response['epoch']==runtime['epoch'] and response['command']
            code,headers,raw=store.request('GET',path,max_bytes=8192);assert code==200
            version=header(headers,'x-amz-version-id');authority=json.loads(raw)
            assert authority['podUid']==pod['metadata']['uid'] and authority['offloadId']==(context['offloads'][0]['id'] if context['offloads'] else None)
            assert request('claim',{},200)['command']==response['command']
            code,headers,repeated=store.request('GET',path,max_bytes=8192)
            assert code==200 and header(headers,'x-amz-version-id')==version and repeated==raw
            actual=json.loads(pg.sql('SELECT to_jsonb(r)-\'claim_nonce\' FROM edgeai.runtime_instance r WHERE id='+literal(runtime['id'])+'::uuid',database))
            assert actual['producer_pod_uid']==authority['podUid'] and actual['node_uid']==authority['nodeUid']
            if authority['offloadId'] is not None:
                assert pg.sql('SELECT state FROM edgeai.task_offload WHERE id='+literal(authority['offloadId'])+'::uuid',database)=='SUCCEEDED'
            fixture.api_claim_verified=True
            if on_admitted is not None:on_admitted(request,authority,store)
            return authority
    finally:
        api.close();fixture.api_claim_source_stopped=api.process.poll() is not None
