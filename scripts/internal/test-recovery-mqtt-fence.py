"""Real isolated TLS Mosquitto: credential retirement, live disconnect, durable client fencing and resume."""
import argparse
from copy import copy
import hashlib
import json
import os
from pathlib import Path
import queue
import secrets
import selectors
import ssl
import subprocess
import sys
import threading
import time
import traceback
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import urlsplit
import uuid

import paho.mqtt.client as mqtt
from postgres_backup import Blocked, ROOT, private_file
from recovery_mqtt_fence import AuthenticationRejected, Connection, MARKER, execute


class Peer:
    def __init__(self,args,name,password):
        self.connected,self.disconnected,self.subscribed=threading.Event(),threading.Event(),threading.Event()
        self.reason=None
        self.messages=queue.Queue()
        self.client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id=name,protocol=mqtt.MQTTv5,reconnect_on_failure=False)
        self.client.tls_set_context(ssl.create_default_context(cafile=str(args.ca_file)))
        self.client.username_pw_set(name,password)
        def connected(c,u,flags,reason,props): self.reason=reason.value;self.connected.set()
        self.client.on_connect=connected
        self.client.on_disconnect=lambda c,u,flags,reason,props:self.disconnected.set()
        self.client.on_subscribe=lambda c,u,mid,reasons,props:self.subscribed.set() if all(not r.is_failure for r in reasons) else None
        self.client.on_message=lambda c,u,message:self.messages.put(message.payload)
        endpoint=urlsplit(args.endpoint)
        try:
            self.client.connect(endpoint.hostname,endpoint.port,keepalive=15)
            self.client.loop_start()
            assert self.connected.wait(5)
        except BaseException:
            self.close();raise

    def close(self): self.client.disconnect();self.client.loop_stop()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-mqtt-fence-test.json')
    args=parser.parse_args()
    work=ROOT/'.tools'/('recovery-mqtt-fence-test-'+uuid.uuid4().hex)
    work.mkdir(mode=0o700)
    broker=work/'broker';broker.mkdir(mode=0o700)
    process=None
    peers=[]
    report={'scope':'mqtt-source-client-fence-tests','sourceMode':'SYNTHETIC','status':'RUNNING',
            'cases':[],'ownedBrokerStopped':False,'ownedClientsStopped':False}
    def passed(name): report['cases'].append(name);print('PASS: '+name,flush=True)
    def port_line():
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout,selectors.EVENT_READ)
            assert selector.select(30), 'Owned broker did not report readiness'
            line=process.stdout.readline().strip()
            assert line.isdigit() and process.poll() is None
            return int(line)
    code=1
    try:
        with private_file(work/'fixture.log') as log:
            process=subprocess.Popen([sys.executable,str(ROOT/'backend/app/src/test/fixtures/stream_broker.py'),str(broker)],
                stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True)
        port=port_line()
        ca=broker/'server.crt'
        password=(broker/'admin.password').read_text()
        digest='sha256:'+hashlib.sha256(b'isolated-recovery-mqtt-test').hexdigest()
        cfg=SimpleNamespace(endpoint='ssl://localhost:'+str(port),ca_file=ca,
            certificate_sha256=hashlib.sha256(ssl.PEM_cert_to_DER_cert(ca.read_text())).hexdigest(),
            broker_digest=digest,admin_user='edgeai-admin',admin_password_file=broker/'admin.password',
            recovery_id=str(uuid.uuid4()),state_directory=work/'state',timeout=30)
        credentials={}
        passive=[]
        topic='edgeai/streams/'+str(uuid.uuid4())+'/1/frames'
        role='edgeai-generation-'+str(uuid.uuid4())+'-producer'
        with Connection(cfg,password) as admin:
            admin.call('createRole',rolename=role,textname='edgeai-stream-v1',textdescription='sha256:'+'a'*64,
                acls=[{'acltype':kind,'topic':topic,'allow':True,'priority':0}
                      for kind in ('publishClientSend','publishClientReceive','subscribeLiteral','unsubscribeLiteral')])
            original_role=admin.call('getRole',rolename=role)['data']['role']
            for kind in ('device','task'):
                name='edgeai-'+kind+'-'+str(uuid.uuid4())+'-1'
                credentials[name]=secrets.token_hex(32)
                admin.call('createClient',username=name,clientid=name,password=credentials[name],
                    textname='edgeai-stream-v1',textdescription=digest+':'+'b'*64,roles=[{'rolename':role,'priority':0}])
            for _ in range(33):
                name='edgeai-task-'+str(uuid.uuid4())+'-1'
                passive.append(name)
                admin.call('createClient',username=name,clientid=name,password=secrets.token_hex(32),
                    textname='edgeai-stream-v1',textdescription=digest+':'+'b'*64)
        sender,receiver=[Peer(cfg,name,pwd) for name,pwd in credentials.items()]
        peers.extend([sender,receiver])
        assert sender.reason==receiver.reason==0
        receiver.client.subscribe(topic,qos=1);assert receiver.subscribed.wait(5)
        sender.client.publish(topic,b'owned-before-fence',qos=1).wait_for_publish(5)
        assert receiver.messages.get(timeout=5)==b'owned-before-fence'
        passed('live-device-and-task-authenticate-and-exchange-real-tls-payload')

        def cli(label, expected, variant=None):
            value=variant or cfg
            output=work/label
            command=[sys.executable,str(ROOT/'scripts/internal/recovery_mqtt_fence.py'),'--endpoint',value.endpoint,
                '--ca-file',str(value.ca_file),'--certificate-sha256',value.certificate_sha256,
                '--broker-digest',value.broker_digest,'--admin-password-file',str(value.admin_password_file),
                '--recovery-id',value.recovery_id,'--state-directory',str(value.state_directory),
                '--output',str(output),'--timeout','30']
            result=subprocess.run(command,capture_output=True,timeout=90)
            with private_file(work/(label+'.log')) as log: log.write(result.stdout);log.write(result.stderr)
            assert result.returncode==expected, label+': unexpected exit; private diagnostics retained'
            result=json.loads((output/'fence-report.json').read_text())
            assert result['activated'] is False and result['globalQuiescenceProven'] is False
            assert not (output/'fence-report.json').stat().st_mode&0o077
            return result

        for label,field,value in [('wrong-certificate','certificate_sha256','0'*64),
                                 ('wrong-broker','broker_digest','sha256:'+'0'*64),
                                 ('untrusted-ca','ca_file',broker/'untrusted.crt')]:
            variant=copy(cfg);setattr(variant,field,value);variant.state_directory=work/(label+'-state')
            result=cli(label,1,variant)
            assert not result['controllerCredentialRetired']
            with Connection(cfg,password) as admin:
                assert 'error' in admin.call('getRole',rolename=MARKER,allowed_error='Role not found')
            assert not sender.disconnected.is_set() and not receiver.disconnected.is_set()
            passed(label+'-refused-without-disconnecting-existing-principals')

        with Connection(cfg,password) as admin:
            admin.call('createClient',username='unrelated-client',password=secrets.token_hex(32))
        result=cli('unrelated-client',1)
        with Connection(cfg,password) as admin:
            assert admin.call('getClient',username='unrelated-client')['data']['client']['username']=='unrelated-client'
            assert 'error' in admin.call('getRole',rolename=MARKER,allowed_error='Role not found')
            admin.call('deleteClient',username='unrelated-client')
        passed('unrelated-client-preserved-and-recovery-refused-before-mutation')

        with Connection(cfg,password) as admin:
            admin.call('createGroup',groupname='unrelated-group')
        cli('unrelated-group',1)
        with Connection(cfg,password) as admin:
            assert admin.call('getGroup',groupname='unrelated-group')['data']['group']['groupname']=='unrelated-group'
            assert 'error' in admin.call('getRole',rolename=MARKER,allowed_error='Role not found')
            admin.call('deleteGroup',groupname='unrelated-group')
        passed('unrelated-group-preserved-and-recovery-refused-before-mutation')

        with Connection(cfg,password) as admin:
            admin.call('setDefaultACLAccess',acls=[{'acltype':'publishClientReceive','allow':True}])
        cli('default-allow',1)
        with Connection(cfg,password) as admin:
            defaults=admin.call('getDefaultACLAccess')['data']['acls']
            assert next(a['allow'] for a in defaults if a['acltype']=='publishClientReceive') is True
            assert 'error' in admin.call('getRole',rolename=MARKER,allowed_error='Role not found')
            admin.call('setDefaultACLAccess',acls=[{'acltype':'publishClientReceive','allow':False}])
        passed('non-deny-default-policy-preserved-and-recovery-refused-before-mutation')

        with Connection(cfg,password) as admin:
            admin.call('createRole',rolename=MARKER,textname=MARKER,textdescription='another-operation',acls=[])
        cli('other-marker',1)
        with Connection(cfg,password) as admin:
            assert admin.call('getRole',rolename=MARKER)['data']['role']['textdescription']=='another-operation'
            admin.call('deleteRole',rolename=MARKER)
        passed('another-operation-marker-preserved-before-password-change')

        old_admin=Connection(cfg,password)
        partial={}
        call=Connection.call
        def interrupt_before_principal(self,command,**values):
            if command=='disableClient': raise Blocked('Test interruption after administrator retirement')
            return call(self,command,**values)
        try:
            with patch.object(Connection,'call',interrupt_before_principal):
                try: execute(cfg,partial)
                except Blocked: pass
                else: raise AssertionError('Injected interruption was not reached')
            assert partial['controllerCredentialRetired'] and partial['oldAdminRejected'] and partial['markerRetained']
            assert old_admin.disconnected.wait(5)
            assert not sender.disconnected.is_set() and not receiver.disconnected.is_set()
        finally: old_admin.close()
        passed('interruption-after-controller-password-retirement-preserves-private-resume-state')

        fenced=cli('resumed',0)
        assert fenced['status']=='SOURCE_BROKER_CLIENTS_DISABLED' and fenced['disabledPrincipals']==35
        assert sender.disconnected.wait(5) and receiver.disconnected.wait(5)
        passed('same-operation-resumes-and-kicks-existing-device-and-task-connections')
        for name,pwd in credentials.items():
            denied=Peer(cfg,name,pwd);peers.append(denied)
            assert denied.reason in (134,135)
        try:
            with Connection(cfg,password): pass
        except AuthenticationRejected: pass
        else: raise AssertionError('Old controller credential accepted')
        passed('old-controller-device-and-task-credentials-cannot-reconnect')

        private_state=json.loads((cfg.state_directory/'recovery.json').read_text())
        replacement=private_state['password']
        assert replacement!=password and not (cfg.state_directory/'recovery.json').stat().st_mode&0o077
        with Connection(cfg,replacement) as admin:
            assert admin.call('getRole',rolename=role)['data']['role']==original_role
            for name in credentials:
                assert admin.call('getClient',username=name)['data']['client']['disabled'] is True
        passed('generation-role-history-and-acls-preserved-with-private-replacement-credential')
        with Connection(cfg,replacement) as admin:
            for name in passive:
                assert admin.call('getClient',username=name)['data']['client']['disabled'] is True
        passed('paged-inventory-disables-all-35-principals-including-inactive-accounts')

        variant=copy(cfg);variant.recovery_id=str(uuid.uuid4())
        cli('different-operation',1,variant)
        passed('another-operation-cannot-adopt-existing-recovery-state')

        process.stdin.write('restart\n');process.stdin.flush()
        assert port_line()==port
        again=cli('after-broker-restart',0)
        assert again['disabledPrincipals']==35 and again['oldAdminRejected']
        with Connection(cfg,replacement) as admin:
            assert admin.call('getRole',rolename=role)['data']['role']==original_role
        passed('broker-sigkill-restart-retains-credential-retirement-disabled-principals-and-role-history')
        report.update(disabledPrincipals=35,livePrincipalDisconnects=2,controllerDisconnectConfirmed=True,
                      originalRolePreserved=True,restartPreserved=True)
        public=json.dumps(report)+json.dumps(fenced)+json.dumps(again)
        assert all(secret not in public for secret in [password,replacement,*credentials.values()])
        code=0
    except Exception as error:
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as out: traceback.print_exc(file=out)
        print('FAIL: MQTT recovery acceptance; private diagnostics retained',flush=True)
    finally:
        for peer in peers: peer.close()
        report['ownedClientsStopped']=all(not peer.client.is_connected() for peer in peers)
        if process:
            if process.poll() is None: process.terminate()
            try: process.wait(10)
            except subprocess.TimeoutExpired: process.kill();process.wait(5)
            process.stdin.close();process.stdout.close()
            report['ownedBrokerStopped']=process.returncode==0
            if not report['ownedBrokerStopped']: code=1
        report['status']='PASS' if code==0 else 'FAIL'
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with private_file(args.report,'w') as out: json.dump(report,out,indent=2);out.write('\n')
    print(report['status']+': actual TLS MQTT recovery; '+str(len(report['cases']))+' cases',flush=True)
    return code


if __name__=='__main__':
    sys.exit(main())
