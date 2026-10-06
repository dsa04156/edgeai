"""Owned TLS MinIO: root credential retirement, old signed URLs, interruption/resume and retained versions."""
import argparse
from copy import copy
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
import time
import traceback
from types import SimpleNamespace
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid
import xml.etree.ElementTree as ET

from postgres_backup import Blocked, ROOT, private_file
from recovery_storage_fence import Client, MARKER, canonical, execute, persist, policy, presigned_path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--minio-binary',type=Path,default=ROOT/'.tools/minio')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-storage-fence-test.json')
    args=parser.parse_args()
    work=ROOT/'.tools'/('recovery-storage-fence-test-'+uuid.uuid4().hex);work.mkdir(mode=0o700)
    env={k:v for k,v in os.environ.items() if not k.startswith(('MC_','MINIO_'))}
    env.update(MC_CONFIG_DIR=str(work/'mc'),MC_NO_COLOR='1',MC_DISABLE_PAGER='1')
    user='fence-probe-root';password=secrets.token_hex(24)
    processes=[];pending=[]
    report={'scope':'storage-root-credential-fence-tests','sourceMode':'SYNTHETIC','status':'RUNNING',
            'cases':[],'ownedProcessesStopped':False}
    def run(command,body=None):
        result=subprocess.run(command,input=body,capture_output=True,env=env,timeout=45)
        with private_file(work/(uuid.uuid4().hex+'.log')) as log: log.write(result.stdout);log.write(result.stderr)
        return result
    def mc(command,body=None):
        result=run([str(ROOT/'.tools/mc'),'--json',*command],body)
        assert result.returncode==0, 'Private fixture command failed'
        return result
    def passed(name):report['cases'].append(name);print('PASS: '+name,flush=True)
    def http(path,method='GET',body=None):
        try:
            with urlopen(Request(origin+path,data=body,method=method),context=tls,timeout=5) as r:return r.status,r.read()
        except HTTPError as r:return r.code,r.read()
    def start(root_override=None):
        process_env={**env,'MINIO_ROOT_USER':user,'MINIO_ROOT_PASSWORD':password}
        if root_override:process_env['MINIO_API_ROOT_ACCESS']=root_override
        with private_file(work/(uuid.uuid4().hex+'.log')) as log:
            p=subprocess.Popen([str(args.minio_binary.resolve()),'server',str(work/'data'),'--address','127.0.0.1:'+str(port),
                '--console-address','127.0.0.1:'+str(console),'--certs-dir',str(work/'certs'),'--quiet'],
                env=process_env,stdout=log,stderr=log)
        processes.append(p)
        for _ in range(200):
            assert p.poll() is None,'Owned MinIO exited'
            try:
                if http('/minio/health/live')[0]==200:return p
            except OSError:pass
            time.sleep(.1)
        raise AssertionError('Owned MinIO readiness timeout')
    code=1
    try:
        certs=work/'certs';certs.mkdir(mode=0o700)
        commands=[['openssl','req','-x509','-nodes','-newkey','rsa:2048','-days','1','-subj','/CN=root-fence-test',
            '-addext','basicConstraints=critical,CA:TRUE','-keyout',str(work/'ca.key'),'-out',str(work/'ca.crt')],
            ['openssl','req','-new','-nodes','-newkey','rsa:2048','-subj','/CN=localhost','-keyout',str(certs/'private.key'),'-out',str(work/'leaf.csr')]]
        for command in commands:assert run(command).returncode==0
        ext=work/'extensions';ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n')
        for path in [work/'ca.key',certs/'private.key']:path.chmod(0o600)
        assert run(['openssl','x509','-req','-in',str(work/'leaf.csr'),'-CA',str(work/'ca.crt'),'-CAkey',str(work/'ca.key'),
            '-set_serial','1','-days','1','-extfile',str(ext),'-out',str(certs/'public.crt')]).returncode==0
        trust=work/'mc/certs/CAs';trust.mkdir(parents=True,mode=0o700);shutil.copyfile(work/'ca.crt',trust/'root.crt')
        with socket.socket() as a,socket.socket() as b:
            a.bind(('127.0.0.1',0));b.bind(('127.0.0.1',0));port=a.getsockname()[1];console=b.getsockname()[1]
        origin='https://localhost:'+str(port);tls=ssl.create_default_context(cafile=str(work/'ca.crt'))
        env['MC_HOST_origin']='https://'+user+':'+password+'@localhost:'+str(port)
        p=start()
        deployment=json.loads(mc(['admin','info','origin']).stdout)['info']['deploymentID']
        with private_file(work/'root.password','w') as out:out.write(password)
        cfg=SimpleNamespace(endpoint=origin,ca_file=work/'ca.crt',root_user=user,root_password_file=work/'root.password',
            certificate_sha256=hashlib.sha256(ssl.PEM_cert_to_DER_cert((certs/'public.crt').read_text())).hexdigest(),
            deployment_id=deployment,recovery_id=str(uuid.uuid4()),state_directory=work/'state',region='us-east-1',timeout=90)
        mc(['mb','origin/edgeai-fence-probe']);mc(['version','enable','origin/edgeai-fence-probe'])
        old_put=presigned_path('PUT','/edgeai-fence-probe/item.bin',user,password,'localhost:'+str(port))
        old_get=presigned_path('GET','/edgeai-fence-probe/item.bin',user,password,'localhost:'+str(port))
        assert http(old_put,'PUT',b'before')[0]==200 and http(old_get)==(200,b'before')
        mc(['pipe','origin/edgeai-fence-probe/item.bin'],b'after')
        def snapshot(alias):
            versions=[json.loads(line) for line in mc(['ls','--versions',alias+'/edgeai-fence-probe']).stdout.splitlines()]
            return sorted((v['key'],v['versionId'],hashlib.sha256(mc(['cat','--version-id',v['versionId'],alias+'/edgeai-fence-probe/'+v['key']]).stdout).hexdigest()) for v in versions)
        original=snapshot('origin');assert len(original)==2
        passed('actual-root-and-already-issued-put-get-urls-work-before-fencing')
        def cli(label,expected,variant=None):
            c=variant or cfg
            command=[sys.executable,str(ROOT/'scripts/internal/recovery_storage_fence.py')]
            for name in ('endpoint','ca_file','root_user','root_password_file','certificate_sha256','deployment_id','recovery_id','state_directory','region','timeout'):
                command.extend(['--'+name.replace('_','-'),str(getattr(c,name))])
            output=work/label;command.extend(['--output',str(output)])
            result=run(command)
            assert result.returncode==expected,label+': unexpected exit'
            value=json.loads((output/'fence-report.json').read_text())
            assert not value['activated'] and not value['globalQuiescenceProven'] and not value['inFlightRequestsDrained']
            assert not (output/'fence-report.json').stat().st_mode&0o077
            return value
        for label,field,value in [('wrong-pin','certificate_sha256','0'*64),('wrong-installation','deployment_id',str(uuid.uuid4()))]:
            variant=copy(cfg);setattr(variant,field,value);variant.state_directory=work/(label+'-state')
            cli(label,1,variant)
            assert http(old_get)==(200,b'after') and snapshot('origin')==original
            passed(label+'-refused-with-root-and-versions-preserved')
        stranger='foreign-'+uuid.uuid4().hex
        mc(['admin','user','add','origin'],(stranger+'\n'+secrets.token_hex(32)+'\n').encode())
        cli('foreign-user',1);assert json.loads(mc(['admin','user','info','origin',stranger]).stdout)['accessKey']==stranger
        mc(['admin','user','rm','origin',stranger])
        passed('unrelated-iam-user-refused-and-preserved')
        mc(['admin','user','add','origin'],(stranger+'\n'+secrets.token_hex(32)+'\n').encode())
        mc(['admin','group','add','origin','fixture-group',stranger])
        mc(['admin','user','rm','origin',stranger])
        groups=mc(['admin','group','ls','origin']).stdout
        cli('foreign-group',1);assert mc(['admin','group','ls','origin']).stdout==groups
        mc(['admin','group','rm','origin','fixture-group'])
        passed('existing-iam-group-refused-and-preserved-even-with-no-user-members')
        service=json.loads(mc(['admin','user','svcacct','add','origin',user]).stdout)
        accounts=mc(['admin','user','svcacct','ls','origin',user]).stdout
        cli('root-service-account',1);assert mc(['admin','user','svcacct','ls','origin',user]).stdout==accounts
        mc(['admin','user','svcacct','rm','origin',service['accessKey']])
        passed('root-service-account-refused-and-preserved-for-separate-recovery-contract')
        mc(['anonymous','set','download','origin/edgeai-fence-probe'])
        cli('anonymous-policy',1);assert http('/edgeai-fence-probe/item.bin')==(200,b'after')
        mc(['anonymous','set','none','origin/edgeai-fence-probe'])
        passed('anonymous-bucket-policy-refused-and-preserved')
        foreign=copy(cfg);foreign.recovery_id=str(uuid.uuid4())
        foreign_policy=work/'foreign-policy.json';persist(foreign_policy,policy(foreign))
        mc(['admin','policy','create','origin',MARKER,str(foreign_policy)])
        before=json.loads(mc(['admin','policy','info','origin',MARKER]).stdout)
        assert canonical(before['policyInfo']['Policy'])==canonical(policy(foreign))
        cli('foreign-marker',1)
        # MinIO policy sets can be returned in a different order without a write.
        assert canonical(json.loads(mc(['admin','policy','info','origin',MARKER]).stdout))==canonical(before)
        mc(['admin','policy','rm','origin',MARKER])
        passed('another-recovery-policy-refused-without-overwrite')
        for key in ('late-complete.bin','late-abort.bin'):
            channel=tls.wrap_socket(socket.create_connection(('localhost',port),timeout=10),server_hostname='localhost')
            pending.append(channel)
            signed=presigned_path('PUT','/edgeai-fence-probe/'+key,user,password,'localhost:'+str(port))
            channel.sendall(('PUT '+signed+' HTTP/1.1\r\nHost: localhost:'+str(port)+
                '\r\nContent-Length: 65536\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n').encode())
            response=b''
            while b'\r\n\r\n' not in response:
                chunk=channel.recv(4096)
                assert chunk and len(response)<16384,'Admitted PUT response was lost or oversized'
                response+=chunk
            assert response.startswith(b'HTTP/1.1 100 Continue')
        passed('two-real-puts-reach-server-body-read-before-root-fencing')
        original_call=Client.call
        def fault_after_add(self,arguments,**kwargs):
            result=original_call(self,arguments,**kwargs)
            if arguments[:3]==['admin','user','add']:raise Blocked('Injected interruption after actual user creation')
            return result
        interrupted=copy(cfg);interrupted.output=work/'after-user';interrupted.output.mkdir(mode=0o700)
        with patch.object(Client,'call',fault_after_add):
            try:execute(interrupted,{})
            except Blocked:pass
            else:raise AssertionError('Expected actual mutation interruption')
        state=json.loads((cfg.state_directory/'recovery.json').read_text())
        created=json.loads(mc(['admin','user','info','origin',state['user']]).stdout)
        assert created.get('policyName','')=='' and http(old_get)==(200,b'after')
        assert not (cfg.state_directory/'confirmed.json').exists()
        passed('interruption-after-real-user-creation-retains-private-state-with-root-still-usable')
        def fault_after_disable(self,arguments,**kwargs):
            result=original_call(self,arguments,**kwargs)
            if arguments[:3]==['admin','config','set']:raise Blocked('Injected missing acknowledgement after actual disable')
            return result
        interrupted.output=work/'after-disable';interrupted.output.mkdir(mode=0o700)
        with patch.object(Client,'call',fault_after_disable):
            try:execute(interrupted,{})
            except Blocked:pass
            else:raise AssertionError('Expected actual disable interruption')
        assert http(old_put,'PUT',b'forbidden')[0]==403 and not (cfg.state_directory/'confirmed.json').exists()
        passed('same-state-resumes-user-grant-and-keeps-root-disabled-after-lost-acknowledgement')
        result=cli('resume',0)
        assert result['rootCredentialRejected'] and result['recoveryAdministratorVerified'] and result['bucketCount']==1
        env['MC_HOST_recovery']='https://'+state['user']+':'+state['password']+'@localhost:'+str(port)
        assert snapshot('recovery')==original
        passed('same-recovery-confirms-root-fence-with-both-original-version-ids-and-bytes-intact')
        for path,method,body in [(old_put,'PUT',b'forbidden'),(old_get,'GET',None)]:
            status,payload=http(path,method,body)
            assert status==403 and ET.fromstring(payload).findtext('Code')=='InvalidAccessKeyId'
        passed('already-issued-root-put-and-get-urls-are-rejected-by-real-s3-authentication')
        raw=mc(['admin','info','origin']);assert raw.returncode==0 and json.loads(raw.stdout)['status']=='error'
        check=copy(cfg);check.output=work/'status-check';check.output.mkdir(mode=0o700)
        client=Client(check,state,password)
        try:client.call(['admin','info','origin'])
        except Blocked:pass
        else:raise AssertionError('JSON error was treated as successful CLI exit')
        passed('actual-mc-zero-exit-with-error-response-is-not-success')
        def drain(label,expected):
            output=work/label
            value=run([sys.executable,str(ROOT/'scripts/internal/recovery_storage_drain.py'),
                '--state-directory',str(cfg.state_directory),'--ca-file',str(cfg.ca_file),
                '--root-password-file',str(cfg.root_password_file),'--output',str(output),'--timeout','10'])
            assert value.returncode==expected,label+': unexpected drain exit'
            result=json.loads((output/'drain-report.json').read_text())
            assert not result['globalQuiescenceProven'] and not result['activated']
            assert not (output/'drain-report.json').stat().st_mode&0o077
            return result
        blocked=drain('drain-two-pending',2)
        assert not blocked['inFlightRequestsDrained'] and blocked['remainingInFlight']==2
        assert snapshot('recovery')==original and http(old_put,'PUT',b'forbidden')[0]==403
        passed('drain-times-out-with-two-real-admitted-puts-and-keeps-root-fence')
        pending[0].sendall(b'z'*65536)
        response=b''
        while True:
            chunk=pending[0].recv(4096)
            if not chunk:break
            response+=chunk
        pending[0].close();assert response.startswith(b'HTTP/1.1 200 OK')
        assert mc(['cat','recovery/edgeai-fence-probe/late-complete.bin']).stdout==b'z'*65536
        after_late=snapshot('recovery')
        assert len(after_late)==3 and all(item in after_late for item in original)
        original=after_late
        blocked=drain('drain-one-pending',2)
        assert not blocked['inFlightRequestsDrained'] and blocked['remainingInFlight']==1
        passed('admitted-put-can-commit-after-root-disable-while-second-put-still-blocks-drain')
        pending[1].shutdown(socket.SHUT_RDWR);pending[1].close()
        drained=drain('drain-complete',0)
        assert drained['inFlightRequestsDrained'] and drained['freshZeroObservations']>=2
        assert drained['remainingInFlight']==drained['remainingQueued']==0 and snapshot('recovery')==original
        passed('client-disconnect-ends-last-put-and-fresh-counters-prove-drain-with-version-history')
        from recovery_storage_drain import execute as verify_drain, metrics_snapshot, TOTAL, INFLIGHT
        captured=client.one(['admin','prometheus','metrics','recovery','api','--api-version','v3'])
        observed=metrics_snapshot(captured)
        assert observed['inFlight']==0
        scientific=json.loads(json.dumps(captured))
        for family in scientific:
            if family['name']==TOTAL:
                for sample in family['metrics']:
                    if sample['labels']['name']=='ListBuckets':sample['value']='1e6'
        assert metrics_snapshot(scientific)['completedProbes']==1000000
        variants=[]
        variants.append([f for f in captured if f['name']!=TOTAL])
        variants.append(captured+[{'name':INFLIGHT,'type':'GAUGE','metrics':[{'labels':{'name':'PutObject','server':observed['server'],'type':'s3'},'value':'-1'}]}])
        variants.append(captured+[{'name':INFLIGHT,'type':'GAUGE','metrics':[{'labels':{'name':'PutObject','server':'other-server','type':'s3'},'value':'1'}]}])
        for document in variants:
            try:metrics_snapshot(document)
            except Blocked:pass
            else:raise AssertionError('Incomplete/invalid metric evidence was accepted')
        passed('missing-completion-counter-negative-gauge-and-mixed-server-metrics-are-refused')
        original_one=Client.one
        def multiple_servers(self,arguments,**kwargs):
            value=original_one(self,arguments,**kwargs)
            if arguments==['admin','info','recovery']:
                value['info']['servers'].append({**value['info']['servers'][0],'endpoint':'other-server'})
            return value
        multi=SimpleNamespace(state_directory=cfg.state_directory,ca_file=cfg.ca_file,
            root_password_file=cfg.root_password_file,timeout=10,output=work/'multiple-servers')
        multi.output.mkdir(mode=0o700)
        with patch.object(Client,'one',multiple_servers):
            try:verify_drain(multi,{})
            except Blocked:pass
            else:raise AssertionError('Single endpoint observation accepted a distributed installation')
        passed('multi-server-administration-response-is-refused-before-drain-certification')
        def stale_metrics(self,arguments,**kwargs):
            if arguments[:3]==['admin','prometheus','metrics']:return captured
            return original_one(self,arguments,**kwargs)
        stale=SimpleNamespace(state_directory=cfg.state_directory,ca_file=cfg.ca_file,
            root_password_file=cfg.root_password_file,timeout=10,output=work/'stale-metrics')
        stale.output.mkdir(mode=0o700)
        with patch.object(Client,'one',stale_metrics):
            try:verify_drain(stale,{})
            except Blocked:pass
            else:raise AssertionError('Repeated stale zero metrics were accepted')
        passed('unchanged-zero-metrics-cannot-pass-without-fresh-completed-root-probes')
        variant=copy(cfg);variant.recovery_id=str(uuid.uuid4())
        cli('different-recovery-state',1,variant)
        variant.state_directory=work/'other-state'
        cli('different-recovery-private-state',2,variant)
        assert snapshot('recovery')==original
        passed('different-recovery-cannot-adopt-existing-private-state-or-disabled-installation')
        p.kill();p.wait(10);p=start()
        cli('after-restart',0);assert snapshot('recovery')==original
        assert drain('drain-after-restart',0)['inFlightRequestsDrained']
        assert http(old_put,'PUT',b'forbidden')[0]==403
        passed('sigkill-restart-retains-deployment-identity-root-disable-recovery-policy-and-versions')
        # Reopening is an owned-fixture fault, never an automatic action of the recovery command.
        mc(['admin','config','set','recovery','api','root_access=on'])
        assert http(old_get)==(200,b'after')
        cli('externally-reopened',1);assert http(old_get)==(200,b'after')
        assert not drain('drain-reopened',2)['inFlightRequestsDrained']
        passed('external-reopening-after-confirmation-is-refused-without-pretending-the-fence-held')
        mc(['admin','config','set','recovery','api','root_access=off'])
        p.kill();p.wait(10);p=start('on')
        # Remove only this test's confirmation to exercise a first confirmation under a real environment override.
        confirmation=cfg.state_directory/'confirmed.json';confirmation.unlink()
        blocked=cli('environment-override',2)
        assert not blocked['rootCredentialRejected'] and http(old_get)==(200,b'after')
        passed('real-root-access-environment-override-cannot-pass-from-saved-config-alone')
        p.kill();p.wait(10);p=start()
        cli('after-override-removed',0);assert snapshot('recovery')==original
        for path in [cfg.state_directory/'recovery.json',cfg.state_directory/'confirmed.json']:
            assert path.stat().st_mode&0o077==0
        passed('same-state-confirms-after-override-removal-with-private-files-and-original-history')
        report.update(status='PASS',versionCount=3,originalVersionCount=2,
            minioBinarySha256=hashlib.sha256(args.minio_binary.read_bytes()).hexdigest(),
            oldPresignedPutRejected=True,oldPresignedGetRejected=True,restartPreserved=True,versionsPreserved=True,
            admittedPuts=2,lateCompletedPuts=1,disconnectedPuts=1,freshDrainVerified=True,staleMetricsRejected=True)
        code=0
    except Exception as error:
        report.update(status='FAIL',failureType=type(error).__name__,
            failureLocations=[Path(f.filename).name+':'+str(f.lineno) for f in traceback.extract_tb(error.__traceback__)])
        print('FAIL: storage fence test; private diagnostics at '+str(work),flush=True)
    finally:
        for channel in pending:channel.close()
        for p in processes:
            if p.poll() is None:
                p.terminate()
                try:p.wait(15)
                except subprocess.TimeoutExpired:p.kill();p.wait(5)
        report['ownedProcessesStopped']=all(p.poll() is not None for p in processes)
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(report['status']+': '+str(len(report['cases']))+' storage fence cases',flush=True)
    return code


if __name__=='__main__':raise SystemExit(main())
