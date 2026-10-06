"""Fence a dedicated EdgeAI Mosquitto broker by retiring controller credentials and disabling principals."""
import argparse
from copy import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import secrets
import socket
import ssl
import stat
import sys
import threading
import time
from urllib.parse import urlsplit
import uuid

import paho.mqtt.client as mqtt
from postgres_backup import Blocked, private_file, read_private

COMMAND = '$CONTROL/dynamic-security/v1'
MARKER = 'edgeai-recovery-fence-v1'
UUID = r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}'
PRINCIPAL = re.compile(r'edgeai-(?:device|task)-'+UUID+r'-[1-9][0-9]{0,18}')
DEFAULTS = dict.fromkeys(('publishClientSend','publishClientReceive','subscribe','unsubscribe'), False)


class AuthenticationRejected(Exception):
    pass


class Connection:
    def __init__(self, args, password):
        self.client = None
        self.deadline = getattr(args,'deadline',time.monotonic()+args.timeout)
        self.timeout = min(5, self.deadline-time.monotonic())
        if self.timeout<=0: raise Blocked('Broker recovery deadline exceeded')
        self.replies = queue.Queue(maxsize=16)
        self.connected, self.subscribed, self.disconnected = threading.Event(), threading.Event(), threading.Event()
        self.reason = None
        self.invalid = False
        endpoint = urlsplit(args.endpoint)
        tls = ssl.create_default_context(cafile=str(args.ca_file))
        # Check the operator-selected certificate before sending the credential; check the
        # actual MQTT TLS socket again before any administrative command.
        with socket.create_connection((endpoint.hostname,endpoint.port),timeout=self.timeout) as raw:
            with tls.wrap_socket(raw,server_hostname=endpoint.hostname) as channel:
                if hashlib.sha256(channel.getpeercert(binary_form=True)).hexdigest()!=args.certificate_sha256:
                    raise ValueError('Broker certificate identity differs')
        try:
            client=self.client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                client_id='edgeai-recovery-'+uuid.uuid4().hex,protocol=mqtt.MQTTv5,reconnect_on_failure=False)
            client.tls_set_context(tls)
            client.username_pw_set(args.admin_user,password)
            client.connect_timeout=self.timeout
            def connected(c,u,flags,reason,props):
                self.reason=reason.value;self.connected.set()
            def subscribed(c,u,mid,reasons,props):
                if not reasons or any(r.is_failure for r in reasons): self.invalid=True
                self.subscribed.set()
            def received(c,u,message):
                try:
                    if message.retain or len(message.payload)>65536: raise ValueError()
                    value=json.loads(message.payload)
                    if not isinstance(value,dict) or not isinstance(value.get('responses'),list): raise ValueError()
                    self.replies.put_nowait(value['responses'])
                except (ValueError,queue.Full): self.invalid=True
            client.on_connect=connected
            client.on_subscribe=subscribed
            client.on_disconnect=lambda c,u,flags,reason,props:self.disconnected.set()
            client.on_message=received
            client.connect(endpoint.hostname,endpoint.port,keepalive=15)
            if hashlib.sha256(client.socket().getpeercert(binary_form=True)).hexdigest()!=args.certificate_sha256:
                raise ValueError('Broker certificate changed')
            client.loop_start()
            if not self.connected.wait(self.timeout): raise Blocked('Broker authentication response unavailable')
            if self.reason in (134,135): raise AuthenticationRejected()
            if self.reason!=0: raise Blocked('Broker connection rejected for an unconfirmed reason')
            client.subscribe(COMMAND+'/response',qos=1)
            if not self.subscribed.wait(self.timeout) or self.invalid:
                raise Blocked('Broker administrative subscription rejected')
        except BaseException:
            self.close();raise

    def call(self, command, *, allowed_error=None, **values):
        timeout=min(self.timeout,self.deadline-time.monotonic())
        if timeout<=0: raise Blocked('Broker recovery deadline exceeded')
        correlation=uuid.uuid4().hex
        body=json.dumps({'commands':[{'command':command,'correlationData':correlation,**values}]}).encode()
        if len(body)>32768: raise ValueError('Broker command exceeds limit')
        self.client.publish(COMMAND,body,qos=1).wait_for_publish(timeout)
        deadline=min(self.deadline,time.monotonic()+timeout)
        while time.monotonic()<deadline:
            if self.invalid: raise ValueError('Invalid broker response')
            try: replies=self.replies.get(timeout=min(.1,max(.001,deadline-time.monotonic())))
            except queue.Empty:
                if self.disconnected.is_set(): raise Blocked('Broker disconnected before command acknowledgement')
                continue
            for reply in replies:
                if not isinstance(reply,dict): raise ValueError('Invalid broker response')
                if reply.get('correlationData')!=correlation: continue
                if reply.get('command')!=command: raise ValueError('Broker response command differs')
                if 'error' in reply and reply['error']!=allowed_error:
                    raise Blocked('Broker administrative command rejected')
                return reply
        raise Blocked('Broker command acknowledgement timed out')

    def close(self):
        if self.client is not None:
            try: self.client.disconnect()
            finally: self.client.loop_stop()

    def __enter__(self): return self
    def __exit__(self,*_): self.close()


def inventory(connection, digest):
    values=connection.call('getDefaultACLAccess')['data']['acls']
    if len(values)!=4 or {x['acltype']:x['allow'] for x in values}!=DEFAULTS:
        raise ValueError('Dedicated broker must retain all deny defaults')
    groups=connection.call('listGroups',verbose=False,count=1,offset=0)['data']
    if groups['totalCount']!=0 or groups['groups']:
        raise ValueError('Broker groups require a separate recovery contract')
    clients={}
    total=None
    while total is None or len(clients)<total:
        data=connection.call('listClients',verbose=True,count=32,offset=len(clients))['data']
        if type(data['totalCount']) is not int or not 1<=data['totalCount']<=10000:
            raise ValueError('Broker client inventory exceeds supported bounds')
        if total is not None and total!=data['totalCount']:
            raise Blocked('Broker client inventory changed during preflight')
        total=data['totalCount']
        if not data['clients']: raise Blocked('Incomplete broker client inventory')
        for client in data['clients']:
            name=client['username']
            if name in clients or client.get('groups') or type(client.get('disabled',False)) is not bool:
                raise ValueError('Duplicate or grouped broker client')
            if name!='edgeai-admin':
                if not (PRINCIPAL.fullmatch(name) and client.get('clientid')==name
                        and client.get('textname')=='edgeai-stream-v1'
                        and re.fullmatch(re.escape(digest)+r':[0-9a-f]{64}',client.get('textdescription',''))):
                    raise ValueError('Unrelated broker client is preserved')
            elif client.get('disabled',False) or client.get('clientid'):
                raise ValueError('Controller broker identity differs')
            clients[name]=client
        if len(clients)>total: raise ValueError('Inconsistent broker client count')
    if 'edgeai-admin' not in clients: raise ValueError('Controller broker identity missing')
    return clients


def marker(connection, expected, create=False):
    if create:
        connection.call('createRole',rolename=MARKER,textname=MARKER,textdescription=expected,
                        acls=[],allowed_error='Role already exists')
    response=connection.call('getRole',rolename=MARKER,allowed_error='Role not found')
    if 'error' in response:
        if create: raise Blocked('Recovery marker was not retained')
        return False
    value=response['data']['role']
    if value.get('textname')!=MARKER or value.get('textdescription')!=expected or value.get('acls'):
        raise ValueError('Another recovery marker or role is preserved')
    return True


def execute(args, report):
    endpoint=urlsplit(args.endpoint)
    if endpoint.scheme!='ssl' or not endpoint.hostname or not endpoint.port or endpoint.username or endpoint.password or endpoint.path or endpoint.query or endpoint.fragment:
        raise ValueError('Explicit TLS broker endpoint required')
    if not re.fullmatch(r'[0-9a-f]{64}',args.certificate_sha256) or not re.fullmatch(r'sha256:[0-9a-f]{64}',args.broker_digest):
        raise ValueError('Explicit broker certificate and configuration digests required')
    if args.admin_user!='edgeai-admin' or str(uuid.UUID(args.recovery_id))!=args.recovery_id or not 10<=args.timeout<=300:
        raise ValueError('Original controller identity, canonical recovery UUID and bounded timeout required')
    identity={'formatVersion':1,'endpoint':args.endpoint,'certificateSha256':args.certificate_sha256,
              'brokerDigest':args.broker_digest,'adminUser':args.admin_user,'recoveryId':args.recovery_id}
    directory=args.state_directory
    directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    info=directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_mode&0o077 or info.st_uid!=os.geteuid():
        raise ValueError('Recovery state directory must be private and owned')
    fd=os.open(directory/'.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise Blocked('Recovery state is already in use')
        path=directory/'recovery.json'
        if path.exists():
            with read_private(path) as source: state=json.load(source)
            if {k:state.get(k) for k in identity}!=identity or not re.fullmatch(r'[0-9a-f]{64}',state.get('password','')):
                raise ValueError('Recovery state identity differs')
        else:
            state={**identity,'password':secrets.token_hex(32)}
            with private_file(path,'w') as out:
                json.dump(state,out);out.write('\n');out.flush();os.fsync(out.fileno())
            directory_fd=os.open(directory,os.O_RDONLY|os.O_DIRECTORY)
            try: os.fsync(directory_fd)
            finally: os.close(directory_fd)
        with read_private(args.admin_password_file) as source:
            old_password=source.read(4097).decode()
        if not 1<=len(old_password)<=4096 or '\x00' in old_password or state['password']==old_password:
            raise ValueError('Separate original controller credential required')
        expected=args.recovery_id+'|'+args.broker_digest
        deadline=time.monotonic()+args.timeout
        connection_args=copy(args)
        connection_args.deadline=deadline
        connection=None
        try:
            try:
                connection=Connection(connection_args,state['password'])
                if not marker(connection,expected): raise ValueError('Rotated administrator has no recovery marker')
            except AuthenticationRejected:
                connection=Connection(connection_args,old_password)
                inventory(connection,args.broker_digest)
                marker(connection,expected)
                marker(connection,expected,create=True)
                report['markerRetained']=True
                # Mosquitto disconnects every connection for this username on password change.
                # An absent reply is not success; reconnect with the persisted replacement and
                # prove the original credential is rejected before disabling principals.
                try: connection.call('setClientPassword',username=args.admin_user,password=state['password'])
                except Blocked: pass
                connection.close();connection=None
                connection=Connection(connection_args,state['password'])
            if not marker(connection,expected): raise Blocked('Recovery marker missing')
            report['markerRetained']=True
            try:
                with Connection(connection_args,old_password): pass
            except AuthenticationRejected: report['oldAdminRejected']=True
            else: raise Blocked('Original controller credential is still accepted')
            report['controllerCredentialRetired']=True
            clients=inventory(connection,args.broker_digest)
            principals={k:v for k,v in clients.items() if k!=args.admin_user}
            report['principalCount']=len(principals)
            for name,value in principals.items():
                if time.monotonic()>=deadline: raise Blocked('Broker fencing deadline exceeded; partial fence retained')
                if not value.get('disabled',False): connection.call('disableClient',username=name)
            current=inventory(connection,args.broker_digest)
            if set(current)!=set(clients) or any(not value.get('disabled',False) for name,value in current.items() if name!=args.admin_user):
                raise Blocked('Broker principal inventory or disabled state changed')
            if not marker(connection,expected): raise Blocked('Recovery marker changed')
            try:
                with Connection(connection_args,old_password): pass
            except AuthenticationRejected: pass
            else: raise Blocked('Original controller credential was restored during verification')
            report.update(status='SOURCE_BROKER_CLIENTS_DISABLED',disabledPrincipals=len(principals))
        finally:
            if connection is not None: connection.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--endpoint',required=True)
    parser.add_argument('--ca-file',type=Path,required=True)
    parser.add_argument('--certificate-sha256',required=True)
    parser.add_argument('--broker-digest',required=True)
    parser.add_argument('--admin-user',default='edgeai-admin')
    parser.add_argument('--admin-password-file',type=Path,required=True)
    parser.add_argument('--recovery-id',required=True)
    parser.add_argument('--state-directory',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--timeout',type=int,default=60)
    args=parser.parse_args()
    args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    report={'scope':'mqtt-source-client-fence','status':'RUNNING','recoveryId':args.recovery_id,
            'markerRetained':False,'controllerCredentialRetired':False,'oldAdminRejected':False,
            'globalQuiescenceProven':False,'activated':False}
    code=1
    try:
        execute(args,report);code=0
    except Exception as error:
        code=2 if isinstance(error,Blocked) else 1
        report.update(status='BLOCKED' if code==2 else 'FAIL',failureType=type(error).__name__,fenceStateUnconfirmed=True)
    finally:
        with private_file(args.output/'fence-report.json','w') as out: json.dump(report,out,indent=2);out.write('\n')
    print(report['status']+': broker recovery; private state and report retained')
    return code


if __name__=='__main__':
    sys.exit(main())
