"""Exercise version-preserving backup against two owned TLS MinIO processes."""
import argparse
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
import urllib.request
import uuid

from postgres_backup import private_file

ROOT=Path(__file__).resolve().parents[1]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--minio-binary',type=Path,default=ROOT/'.tools/minio')
    p.add_argument('--report',type=Path,default=ROOT/'.tools/storage-backup-test.json')
    a=p.parse_args()
    work=ROOT/'.tools'/('storage-backup-test-'+uuid.uuid4().hex);work.mkdir(mode=0o700)
    env={k:v for k,v in os.environ.items() if not k.startswith('MC_')}
    env.update(MC_CONFIG_DIR=str(work/'mc'),MC_NO_COLOR='1',MC_DISABLE_PAGER='1',EDGEAI_BACKUP_CA_FILE=str(work/'ca.crt'))
    processes=[];identities={}
    report={'scope':'minio-object-versions','sourceMode':'SYNTHETIC','status':'RUNNING','cases':[],'ownedProcessesStopped':False}
    def run(command,body=None,expected=0):
        r=subprocess.run(command,input=body,capture_output=True,env=env,timeout=240)
        with private_file(work/('command-'+uuid.uuid4().hex+'.log')) as log:log.write(r.stdout);log.write(r.stderr)
        assert r.returncode==expected,'Unexpected command exit; private diagnostics retained'
        return r
    def mc(args,body=None):return run([str(ROOT/'.tools/mc'),'--json',*args],body)
    def cli(action,path,expected=0):
        args=['--bucket','edgeai-backup-test','--output',str(path)] if action=='backup' else ['--input',str(path)]
        return run([sys.executable,'scripts/storage_backup.py',action,*args],expected=expected)
    def start(name):
        if name not in identities:
            identities[name]=('backup-probe',secrets.token_hex(24))
            certs=work/(name+'-certs');certs.mkdir(mode=0o700)
            ca_dir=certs/'CAs';ca_dir.mkdir(mode=0o700);shutil.copyfile(work/'ca.crt',ca_dir/'root.crt')
            extensions=certs/'extensions';extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n')
            run(['openssl','req','-new','-nodes','-newkey','rsa:2048','-subj','/CN=localhost','-keyout',str(certs/'private.key'),'-out',str(certs/'leaf.csr')])
            (certs/'private.key').chmod(0o600)
            run(['openssl','x509','-req','-in',str(certs/'leaf.csr'),'-CA',str(work/'ca.crt'),'-CAkey',str(work/'ca.key'),
                 '-set_serial',str(secrets.randbits(127)+1),'-days','1','-extfile',str(extensions),'-out',str(certs/'public.crt')])
        user,password=identities[name]
        with socket.socket() as api,socket.socket() as console:
            api.bind(('127.0.0.1',0));console.bind(('127.0.0.1',0));port=api.getsockname()[1];console_port=console.getsockname()[1]
        origin='https://localhost:'+str(port)
        env['MC_HOST_'+name]='https://'+user+':'+password+'@localhost:'+str(port)
        prefix='EDGEAI_BACKUP_SOURCE_' if name=='origin' else 'EDGEAI_BACKUP_STORAGE_'
        env.update({prefix+'URL':origin,prefix+'USER':user,prefix+'PASSWORD':password})
        with private_file(work/(name+'-'+uuid.uuid4().hex+'.log')) as log:
            proc=subprocess.Popen([str(a.minio_binary.resolve()),'server',str(work/(name+'-data')),'--address','127.0.0.1:'+str(port),
                '--console-address','127.0.0.1:'+str(console_port),'--certs-dir',str(work/(name+'-certs')),'--quiet'],stdout=log,stderr=log,
                env={**os.environ,'MINIO_ROOT_USER':user,'MINIO_ROOT_PASSWORD':password,'MINIO_SCANNER_SPEED':'slowest'})
        processes.append(proc)
        tls=ssl.create_default_context(cafile=str(work/'ca.crt'));deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            assert proc.poll() is None,'Owned MinIO stopped'
            try:
                with urllib.request.urlopen(origin+'/minio/health/live',context=tls,timeout=1) as r:
                    if r.status==200:return proc
            except OSError:time.sleep(.1)
        raise AssertionError('Owned TLS MinIO readiness timeout')
    def write(key,body):mc(['pipe','--attr','sha256='+hashlib.sha256(body).hexdigest(),'origin/edgeai-backup-test/'+key],body)
    def passed(name):report['cases'].append(name);print('PASS: '+name,flush=True)
    code=1
    try:
        run(['openssl','req','-x509','-nodes','-newkey','rsa:2048','-days','1','-subj','/CN=backup-test-ca',
             '-addext','basicConstraints=critical,CA:TRUE','-keyout',str(work/'ca.key'),'-out',str(work/'ca.crt')])
        (work/'ca.key').chmod(0o600)
        ca_dir=work/'mc/certs/CAs';ca_dir.mkdir(mode=0o700,parents=True);shutil.copyfile(work/'ca.crt',ca_dir/'root.crt')
        original=start('origin');replica=start('replica')
        mc(['mb','origin/edgeai-backup-test']);mc(['version','enable','origin/edgeai-backup-test'])
        write('checkpoint.bin',b'older checkpoint');write('checkpoint.bin',b'newer checkpoint')
        write('empty.bin',b'');write('result/한글.bin',secrets.token_bytes(256*1024))
        bundle=work/'snapshot'
        cli('backup',bundle)
        manifest=json.loads((bundle/'manifest.json').read_text())
        assert len(manifest['versions'])==4 and manifest['sourceDeploymentId']!=manifest['targetDeploymentId']
        assert all((bundle/name).stat().st_mode & 0o077==0 for name in ['manifest.json','backup-report.json'])
        backup=json.loads((bundle/'backup-report.json').read_text())
        assert backup['ownedRulesRemoved'] and backup['status']=='BACKED_UP_OBJECT_VERSIONS' and backup['resyncsStarted']==1
        cli('verify',bundle)
        report.update(versionCount=4,bytes=backup['bytes'],tls=True,scannerSpeed='slowest',explicitResyncsStarted=1,
            minioBinarySha256=hashlib.sha256(a.minio_binary.read_bytes()).hexdigest())
        passed('actual TLS replication preserves historical and current version IDs, empty/Unicode objects and SHA-256 bytes')
        before_files=sorted(p.name for p in bundle.iterdir())
        cli('backup',bundle,1)
        assert sorted(p.name for p in bundle.iterdir())==before_files
        assert json.loads((bundle/'manifest.json').read_text())==manifest
        passed('existing backup directory is refused without adding diagnostics or changing its manifest')
        cli('backup',work/'existing-target',1)
        cli('verify',bundle)
        passed('existing backup bucket is refused without overwriting retained versions')
        saved={k:env[k] for k in ['EDGEAI_BACKUP_STORAGE_URL','EDGEAI_BACKUP_STORAGE_USER','EDGEAI_BACKUP_STORAGE_PASSWORD']}
        for suffix in ['URL','USER','PASSWORD']:env['EDGEAI_BACKUP_STORAGE_'+suffix]=env['EDGEAI_BACKUP_SOURCE_'+suffix]
        cli('backup',work/'same-server',1)
        cli('verify',bundle,1)
        env.update(saved)
        passed('same backup source/destination and verification against a different installation are refused')
        mc(['replicate','add','origin/edgeai-backup-test','--remote-bucket','replica/edgeai-backup-test','--id','preexisting-rule',
            '--priority','1','--replicate','existing-objects','--disable-proxy','--sync'])
        before=mc(['replicate','export','origin/edgeai-backup-test']).stdout
        cli('backup',work/'preexisting-replication',1)
        assert mc(['replicate','export','origin/edgeai-backup-test']).stdout==before
        refused_resync=work/'unowned-resync';refused_resync.mkdir(mode=0o700)
        check_resync="import sys;from pathlib import Path;sys.path.insert(0,'scripts');from storage_backup import Client;Client(Path(sys.argv[1]),source=True).start_owned_resync('edgeai-backup-test','not-the-created-rule')"
        run([sys.executable,'-c',check_resync,str(refused_resync)],expected=1)
        assert mc(['replicate','export','origin/edgeai-backup-test']).stdout==before
        mc(['replicate','remove','origin/edgeai-backup-test','--all','--force'])
        passed('preexisting replication configuration is refused and preserved')
        mc(['mb','origin/edgeai-backup-failure']);mc(['version','enable','origin/edgeai-backup-failure'])
        mc(['pipe','origin/edgeai-backup-failure/item.bin'],b'injected verification outage')
        fault=work/'verification-outage';fault.mkdir(mode=0o700)
        fixture='''import sys
from pathlib import Path
sys.path.insert(0,'scripts')
from storage_backup import Client,backup
class VerificationOutage(Client):
    def digest(self,alias,item):
        if alias=='replica':raise RuntimeError('Injected backup verifier outage')
        return super().digest(alias,item)
try:backup(VerificationOutage(Path(sys.argv[1]),source=True),['edgeai-backup-failure'],1)
except TimeoutError:pass
else:raise AssertionError('Injected verifier outage was accepted')
'''
        run([sys.executable,'-c',fixture,str(fault)])
        failed=json.loads((fault/'backup-report.json').read_text())
        assert failed['status']=='FAIL' and failed['ownedRulesRemoved'] and not (fault/'manifest.json').exists()
        assert not json.loads(mc(['replicate','export','origin/edgeai-backup-failure']).stdout)['config']['Rules']
        cli('verify',bundle)
        passed('injected verifier outage removes its actual replication rule and publishes no successful manifest')
        orphan_bucket='edgeai-backup-orphan'
        for alias in ['origin','replica']:
            mc(['mb',alias+'/'+orphan_bucket]);mc(['version','enable',alias+'/'+orphan_bucket])
        # mc registers a remote target before rejecting priority=-1, leaving a real unattached target.
        run([str(ROOT/'.tools/mc'),'--json','replicate','add','origin/'+orphan_bucket,'--remote-bucket','replica/'+orphan_bucket,
             '--id','unattached-target','--priority','-1','--replicate','existing-objects'],expected=1)
        inspect_dir=work/'target-inspection';inspect_dir.mkdir(mode=0o700)
        inspect="import sys,json;from pathlib import Path;sys.path.insert(0,'scripts');from storage_backup import Client;print(json.dumps(sorted(Client(Path(sys.argv[1]),source=True).remote_target_arns(sys.argv[2]))))"
        before_targets=json.loads(run([sys.executable,'-c',inspect,str(inspect_dir),orphan_bucket]).stdout)
        assert len(before_targets)==1
        rejected=work/'orphan-refused'
        run([sys.executable,'scripts/storage_backup.py','backup','--bucket',orphan_bucket,'--output',str(rejected)],expected=1)
        failure_files=list(rejected.glob('failure-*.json'));assert len(failure_files)==1
        assert 'preexisting remote targets' in json.loads(failure_files[0].read_text())['message']
        assert json.loads(run([sys.executable,'-c',inspect,str(inspect_dir),orphan_bucket]).stdout)==before_targets
        mc(['replicate','remove','origin/'+orphan_bucket,'--all','--force'])
        assert not json.loads(run([sys.executable,'-c',inspect,str(inspect_dir),orphan_bucket]).stdout)
        passed('a real unattached preexisting remote target is detected and preserved before backup')
        item=manifest['versions'][0]
        mc(['rm','--force','--version-id',item['versionId'],'origin/edgeai-backup-test/'+item['key']])
        write('checkpoint.bin',b'after the captured backup')
        cli('verify',bundle)
        current=[json.loads(line) for line in mc(['ls','--versions','--recursive','replica/edgeai-backup-test']).stdout.splitlines()]
        assert len(current)==4
        passed('source permanent deletion and later writes do not change the frozen backup')
        original.terminate();original.wait(15);replica.terminate();replica.wait(15)
        replica=start('replica')
        cli('verify',bundle)
        passed('backup MinIO restart retains its identity and exact versions with the source process offline')
        corrupt=work/'corrupt';corrupt.mkdir(mode=0o700)
        changed=json.loads((bundle/'manifest.json').read_text());changed['versions'][0]['sha256']='0'*64
        with private_file(corrupt/'manifest.json','w') as f:json.dump(changed,f)
        cli('verify',corrupt,1);cli('verify',bundle)
        passed('incorrect expected checksum is rejected without modifying retained data')
        mc(['rm','--force','--version-id',item['versionId'],'replica/edgeai-backup-test/'+item['key']])
        cli('verify',bundle,1)
        passed('missing backup version cannot pass verification while the source is offline')
        report['status'],code='PASS',0
    except Exception as error:
        report.update(status='FAIL',failureType=type(error).__name__,
            failureLocations=[Path(f.filename).name+':'+str(f.lineno) for f in traceback.extract_tb(error.__traceback__)])
        print('FAIL: storage backup test; private diagnostics at '+str(work),flush=True)
    finally:
        for proc in processes:
            if proc.poll() is None:
                proc.terminate()
                try:proc.wait(15)
                except subprocess.TimeoutExpired:proc.kill();proc.wait(5)
        report['ownedProcessesStopped']=all(p.poll() is not None for p in processes)
        a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
    print(report['status']+': '+str(len(report['cases']))+' storage backup cases; private fixture data retained',flush=True)
    return code


if __name__=='__main__':raise SystemExit(main())
