"""Owned real TLS MinIO replicas and Mosquitto for Device recovery integration tests."""
import hashlib
import json
import os
from pathlib import Path
import runpy
import secrets
import selectors
import shutil
import socket
import ssl
import subprocess
import sys
import time
from types import SimpleNamespace
import urllib.request
import uuid
from unittest.mock import patch

from postgres_backup import ROOT, Blocked, private_file
import private_material_backup as material
import recovery_device_readiness as readiness
import recovery_mqtt_fence as mqtt
from storage_backup import Client, backup
from edgeai_runner.stream_protocol import Frame

Peer=runpy.run_path(str(ROOT/'scripts/test-recovery-mqtt-fence.py'))['Peer']


class Fixtures:
    def __init__(self,directory,binary,digest):
        self.directory=directory;self.binary=binary.absolute();self.digest=digest
        self.processes=[];self.peers=[];self.broker=None
        self.original_env=os.environ.copy();self.env={k:v for k,v in os.environ.items() if not k.startswith('MC_')}
        self.env.update(MC_CONFIG_DIR=str(directory/'mc'),MC_NO_COLOR='1',MC_DISABLE_PAGER='1',
            EDGEAI_BACKUP_CA_FILE=str(directory/'ca.crt'))
        self.bucket='fixture-checkpoints'

    def run(self,command,body=None):
        result=subprocess.run(list(map(str,command)),input=body,capture_output=True,env=self.env,timeout=300)
        with private_file(self.directory/(uuid.uuid4().hex+'.log')) as log:log.write(result.stdout+result.stderr)
        assert result.returncode==0,'Owned authority fixture command failed; private diagnostics retained'
        return result.stdout

    def mc(self,args,body=None):return self.run([ROOT/'.tools/mc','--json',*args],body)

    def storage(self,name):
        certs=self.directory/(name+'-certs');certs.mkdir(mode=0o700)
        (certs/'CAs').mkdir(mode=0o700);shutil.copyfile(self.directory/'ca.crt',certs/'CAs/root.crt')
        extensions=certs/'extensions';extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n')
        self.run(['openssl','req','-new','-nodes','-newkey','rsa:2048','-subj','/CN=localhost','-keyout',certs/'private.key','-out',certs/'leaf.csr'])
        (certs/'private.key').chmod(0o600)
        self.run(['openssl','x509','-req','-in',certs/'leaf.csr','-CA',self.directory/'ca.crt','-CAkey',self.directory/'ca.key',
            '-set_serial',str(secrets.randbits(127)+1),'-days','1','-extfile',extensions,'-out',certs/'public.crt'])
        with socket.socket() as api,socket.socket() as console:
            api.bind(('127.0.0.1',0));console.bind(('127.0.0.1',0));port=api.getsockname()[1];console_port=console.getsockname()[1]
        user='device-recovery';password=secrets.token_hex(24);url='https://localhost:'+str(port)
        self.env['MC_HOST_'+name]='https://'+user+':'+password+'@localhost:'+str(port)
        prefix='EDGEAI_BACKUP_SOURCE_' if name=='origin' else 'EDGEAI_BACKUP_STORAGE_'
        self.env.update({prefix+'URL':url,prefix+'USER':user,prefix+'PASSWORD':password})
        with private_file(self.directory/(name+'.log')) as log:
            process=subprocess.Popen([str(self.binary),'server',str(self.directory/(name+'-data')),
                '--address','127.0.0.1:'+str(port),'--console-address','127.0.0.1:'+str(console_port),
                '--certs-dir',str(certs),'--quiet'],stdout=log,stderr=log,env={**self.env,'MINIO_ROOT_USER':user,'MINIO_ROOT_PASSWORD':password})
        self.processes.append(process);deadline=time.monotonic()+30;tls=ssl.create_default_context(cafile=str(self.directory/'ca.crt'))
        while time.monotonic()<deadline:
            assert process.poll() is None,'Owned storage exited'
            try:
                with urllib.request.urlopen(url+'/minio/health/live',context=tls,timeout=1) as response:
                    if response.status==200:return process
            except OSError:time.sleep(.1)
        raise AssertionError('Owned storage readiness timed out')

    def start(self):
        self.run(['openssl','req','-x509','-nodes','-newkey','rsa:2048','-days','1','-subj','/CN=device-recovery-storage',
            '-addext','basicConstraints=critical,CA:TRUE','-keyout',self.directory/'ca.key','-out',self.directory/'ca.crt'])
        (self.directory/'ca.key').chmod(0o600)
        ca=self.directory/'mc/certs/CAs';ca.mkdir(mode=0o700,parents=True);shutil.copyfile(self.directory/'ca.crt',ca/'root.crt')
        self.origin=self.storage('origin');self.replica=self.storage('replica')
        os.environ.update({k:v for k,v in self.env.items() if k.startswith('EDGEAI_BACKUP_')})
        self.mc(['mb','origin/'+self.bucket]);self.mc(['version','enable','origin/'+self.bucket])
        broker=self.directory/'broker';broker.mkdir(mode=0o700)
        with private_file(self.directory/'broker-fixture.log') as log:
            self.broker=subprocess.Popen([sys.executable,str(ROOT/'backend/app/src/test/fixtures/stream_broker.py'),str(broker)],
                stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True)
        with selectors.DefaultSelector() as selector:
            selector.register(self.broker.stdout,selectors.EVENT_READ);assert selector.select(30),'Owned broker readiness timed out'
            line=self.broker.stdout.readline().strip();assert line.isdigit() and self.broker.poll() is None
        ca=broker/'server.crt'
        self.cfg=SimpleNamespace(endpoint='ssl://localhost:'+line,ca_file=ca,
            certificate_sha256=hashlib.sha256(ssl.PEM_cert_to_DER_cert(ca.read_text())).hexdigest(),
            broker_digest=self.digest,admin_user='edgeai-admin',admin_password_file=broker/'admin.password',
            recovery_id=str(uuid.uuid4()),state_directory=self.directory/'mqtt-state',timeout=30)
        self.password=(broker/'admin.password').read_text()

    def upload(self,key,wire):
        self.mc(['pipe','--attr','sha256='+hashlib.sha256(wire).hexdigest(),'origin/'+self.bucket+'/'+key],wire)
        return json.loads(self.mc(['stat','origin/'+self.bucket+'/'+key]))['versionID']

    def seal_storage(self):
        self.bundle=self.directory/'storage-backup';self.bundle.mkdir(mode=0o700)
        self.client=Client(self.bundle,source=True);backup(self.client,[self.bucket],120)
        self.origin.terminate();self.origin.wait(15)
        for key in tuple(os.environ):
            if key.startswith('EDGEAI_BACKUP_SOURCE_'):del os.environ[key]
        for key in tuple(self.env):
            if key.startswith('EDGEAI_BACKUP_SOURCE_') or key=='MC_HOST_origin':del self.env[key]
        self.client.env.pop('MC_HOST_origin',None)

    def fence_device(self,actor,attempt,binding):
        names=['edgeai-device-'+actor.id+'-'+str(actor.epoch),'edgeai-task-'+attempt+'-1']
        self.device_name=names[0];self.credentials={name:secrets.token_hex(32) for name in names}
        role='edgeai-generation-'+uuid.uuid4().hex;topic='edgeai/streams/'+binding.route_id+'/1/frames'
        with mqtt.Connection(self.cfg,self.password) as admin:
            admin.call('createRole',rolename=role,textname='edgeai-stream-v1',textdescription='sha256:'+'c'*64,
                acls=[{'acltype':kind,'topic':topic,'allow':True,'priority':0} for kind in ('publishClientSend','publishClientReceive','subscribeLiteral','unsubscribeLiteral')])
            for name,password in self.credentials.items():
                admin.call('createClient',username=name,clientid=name,password=password,textname='edgeai-stream-v1',
                    textdescription=self.digest+':'+'a'*64,roles=[{'rolename':role,'priority':0}])
        sender,receiver=[Peer(self.cfg,name,password) for name,password in self.credentials.items()];self.peers.extend([sender,receiver])
        assert sender.reason==receiver.reason==0
        receiver.client.subscribe(topic,qos=1);assert receiver.subscribed.wait(5)
        wire=Frame(binding,1,'DATA',b'1','application/json').encode()
        sender.client.publish(topic,wire,qos=1).wait_for_publish(5);assert receiver.messages.get(timeout=5)==wire
        report={};mqtt.execute(self.cfg,report)
        assert report['status']=='SOURCE_BROKER_CLIENTS_DISABLED' and report['disabledPrincipals']==2
        assert sender.disconnected.wait(5) and receiver.disconnected.wait(5)
        for name,password in self.credentials.items():
            peer=Peer(self.cfg,name,password);self.peers.append(peer);assert peer.reason in (134,135)
        self.replacement=json.loads((self.cfg.state_directory/'recovery.json').read_text())['password']

    def exercise(self,pg,options,fingerprint,passed,transport,mismatched_restore):
        args=SimpleNamespace(**vars(options),storage_input=self.bundle,mqtt_state_directory=self.cfg.state_directory,
            mqtt_ca_file=self.cfg.ca_file,mqtt_original_password_file=self.cfg.admin_password_file,
            recovery_id=self.cfg.recovery_id,timeout=30)
        def files():return {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.journal_restore.rglob('*') if p.is_file()}
        pristine=fingerprint(args.database);journal_files=files()
        def verify(variant=None):
            result=readiness.verify(pg,self.client,variant or args)
            assert fingerprint(args.database)==pristine and files()==journal_files
            assert result['status']=='DEVICE_DATA_AND_ORIGINAL_BROKER_VERIFIED'
            assert result['checkpointObjectsVerified'] and result['oldBrokerAuthorityRetired']
            assert all(result[k] is False for k in ('activated','databaseModified','journalModified','brokerModified',
                'producerProcessQuiescenceProven','globalQuiescenceProven'))
            return result
        def refused(operation,kind=Blocked):
            try:operation()
            except kind:return
            raise AssertionError('Invalid combined recovery was accepted')
        def admin(command,**values):
            with mqtt.Connection(self.cfg,self.replacement) as connection:return connection.call(command,**values)
        output=self.directory/'combined-cli'
        command=[sys.executable,'scripts/recovery_device_readiness.py','--transport',transport,'--output',str(output)]
        for key,value in vars(args).items():command+=['--'+key.replace('_','-'),str(value)]
        self.run(command)
        result=json.loads((output/'readiness.json').read_text())
        assert result['storage']['referenceCounts']=={'result_artifact':0,'stream_checkpoint':2}
        assert len(result['checkpoints'])==1 and result['broker']['disabledPrincipals']==2
        assert result['broker']['devicePrincipalPresent'] and self.origin.poll() is not None
        passed('real-tls-checkpoint-replica-and-disabled-original-device-authority-match-after-source-loss')
        variant=SimpleNamespace(**vars(args));variant.database,variant.restore_report=mismatched_restore
        mismatched_before=fingerprint(variant.database)
        assert readiness.journal.compare(pg,variant)['conflicts']==[]
        # Every fixed version and SHA still exists. The decoded checkpoint state
        # deliberately differs from the SQL receipt, with every DB constraint active.
        assert readiness.references.verify(pg,self.client,variant.database,variant.restore_report,variant.storage_input)['verifiedVersionCount']==3
        refused(lambda:readiness.verify(pg,self.client,variant))
        assert fingerprint(variant.database)==mismatched_before and files()==journal_files
        passed('real-restored-checkpoint-receipt-with-wrong-state-summary-is-refused-despite-matching-s3-version-and-sha')
        commands=[];call=mqtt.Connection.call
        def observe(self,command,**values):commands.append(command);return call(self,command,**values)
        with patch.object(mqtt.Connection,'call',observe):verify()
        assert commands and set(commands)=={'getRole','getDefaultACLAccess','listGroups','listClients'}
        passed('combined-verification-sends-only-read-only-broker-commands-and-preserves-all-database-and-journal-bytes')
        references=json.loads((self.bundle/'manifest.json').read_text())['versions']
        historical=next(v for v in references if v['sha256']==result['checkpoints'][0]['sha256'])
        self.mc(['pipe','replica/'+historical['bucket']+'/'+historical['key']],b'unrelated newer version')
        verify()
        passed('newer-object-version-does-not-replace-the-pinned-consumer-checkpoint')
        admin('enableClient',username=self.device_name)
        refused(lambda:verify())
        assert admin('getClient',username=self.device_name)['data']['client'].get('disabled',False) is False
        admin('disableClient',username=self.device_name)
        passed('reenabled-original-device-is-refused-without-repairing-or-mutating-broker-policy')
        original=readiness.verify_checkpoint_bytes
        def changed(*a,**kw):
            value=original(*a,**kw);admin('enableClient',username=self.device_name);return value
        with patch.object(readiness,'verify_checkpoint_bytes',side_effect=changed):refused(lambda:verify())
        admin('disableClient',username=self.device_name)
        passed('actual-principal-reenable-after-checkpoint-read-invalidates-earlier-fence-observation')
        variant=SimpleNamespace(**vars(args));variant.recovery_id=str(uuid.uuid4());refused(lambda:verify(variant),ValueError)
        state_path=self.cfg.state_directory/'recovery.json';state=json.loads(state_path.read_text());changed_state=dict(state,certificateSha256='0'*64)
        temporary=self.directory/'changed-state.json';material.write_json(temporary,changed_state);os.replace(temporary,state_path)
        refused(lambda:verify(),ValueError)
        material.write_json(temporary,state);os.replace(temporary,state_path)
        passed('another-recovery-operation-and-wrong-live-tls-pin-cannot-reuse-private-fence-state')
        wrong=self.directory/'wrong-storage';wrong.mkdir(mode=0o700)
        manifest=json.loads((self.bundle/'manifest.json').read_text());manifest['targetDeploymentId']=str(uuid.uuid4())
        material.write_json(wrong/'manifest.json',manifest)
        variant=SimpleNamespace(**vars(args));variant.storage_input=wrong;refused(lambda:verify(variant),ValueError)
        passed('storage-manifest-from-another-deployment-is-refused')
        original=readiness.references.verify;calls=0
        def changed_db(*a,**kw):
            nonlocal calls
            value=original(*a,**kw);calls+=1
            if calls==1:pg.sql("UPDATE edgeai.device SET display_name='Changed during object verification'",args.database)
            return value
        with patch.object(readiness.references,'verify',side_effect=changed_db):refused(lambda:verify())
        pg.sql("UPDATE edgeai.device SET display_name='Journal comparison'",args.database)
        passed('actual-database-change-during-object-verification-invalidates-the-combined-result')
        try:admin('setClientPassword',username='edgeai-admin',password=self.password)
        except Blocked:pass
        refused(lambda:verify())
        with mqtt.Connection(self.cfg,self.password) as connection:
            try:connection.call('setClientPassword',username='edgeai-admin',password=self.replacement)
            except Blocked:pass
        verify()
        passed('restoring-the-old-administrator-invalidates-a-previously-successful-fence')
        # Delete an actually referenced historical checkpoint, never merely an unreferenced newest version.
        catalog=readiness.journal.database_inventory(pg,args.database,args.restore_report,
            readiness.journal.query(args.run_id,readiness.journal.restored_journal(args.journal_restore)[1][0].producer))
        lost=catalog['routes'][0]['checkpoint']
        self.mc(['rm','--force','--version-id',lost['object_version'],'replica/'+lost['bucket']+'/'+lost['object_key']])
        refused(lambda:verify(),RuntimeError)
        assert fingerprint(args.database)==pristine and files()==journal_files
        passed('deleted-fixed-checkpoint-version-is-not-hidden-by-an-old-verification-report-or-a-later-version')

    def close(self):
        for peer in self.peers:peer.close()
        if self.broker is not None:
            if self.broker.poll() is None:self.broker.terminate()
            try:self.broker.wait(15)
            except subprocess.TimeoutExpired:self.broker.kill();self.broker.wait(5)
            self.broker.stdin.close();self.broker.stdout.close()
        for process in self.processes:
            if process.poll() is None:process.terminate()
            try:process.wait(15)
            except subprocess.TimeoutExpired:process.kill();process.wait(5)
        os.environ.clear();os.environ.update(self.original_env)
        return all(p.poll() is not None for p in self.processes) and (self.broker is None or self.broker.poll() is not None)
