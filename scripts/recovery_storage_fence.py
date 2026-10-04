"""Retire root S3 access on an explicitly identified, dedicated MinIO installation."""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import ssl
import stat
import subprocess
import time
from urllib.parse import quote, urlsplit
import uuid
import xml.etree.ElementTree as ET

from postgres_backup import Blocked, ROOT, private_file, read_private
from storage_backup import MC_SHA256

MARKER='edgeai-recovery-root-fence-v1'


def canonical(value):
    if isinstance(value,dict): return {k:canonical(v) for k,v in value.items()}
    if isinstance(value,list): return sorted((canonical(v) for v in value),key=lambda v:json.dumps(v,sort_keys=True))
    return value


def policy(args):
    return {'Version':'2012-10-17','Statement':[
        {'Sid':'EdgeAIRecovery'+uuid.UUID(args.recovery_id).hex+uuid.UUID(args.deployment_id).hex,
         'Effect':'Allow','Action':['admin:*']},
        {'Effect':'Allow','Action':['s3:Get*','s3:List*'],'Resource':['arn:aws:s3:::*']}]}


def persist(path,value):
    with private_file(path,'w') as out:
        json.dump(value,out);out.write('\n');out.flush();os.fsync(out.fileno())
    fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
    try: os.fsync(fd)
    finally: os.close(fd)


def presigned_path(method,path,user,password,host,region='us-east-1'):
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    scope=stamp[:8]+'/'+region+'/s3/aws4_request'
    fields={'X-Amz-Algorithm':'AWS4-HMAC-SHA256','X-Amz-Credential':user+'/'+scope,
            'X-Amz-Date':stamp,'X-Amz-Expires':'600','X-Amz-SignedHeaders':'host'}
    query='&'.join(quote(k,safe='')+'='+quote(v,safe='') for k,v in sorted(fields.items()))
    request='\n'.join([method,path,query,'host:'+host+'\n','host','UNSIGNED-PAYLOAD'])
    message='AWS4-HMAC-SHA256\n'+stamp+'\n'+scope+'\n'+hashlib.sha256(request.encode()).hexdigest()
    key=('AWS4'+password).encode()
    for value in [stamp[:8],region,'s3','aws4_request']: key=hmac.new(key,value.encode(),hashlib.sha256).digest()
    return path+'?'+query+'&X-Amz-Signature='+hmac.new(key,message.encode(),hashlib.sha256).hexdigest()


class Client:
    def __init__(self,args,state,old_password):
        self.args=args
        self.deadline=time.monotonic()+args.timeout
        self.endpoint=urlsplit(args.endpoint)
        self.tls=ssl.create_default_context(cafile=str(args.ca_file))
        self.credentials={'origin':(args.root_user,old_password),'recovery':(state['user'],state['password'])}
        binary=ROOT/'.tools/mc'
        if not binary.is_file(): raise Blocked('Install the pinned MinIO client first')
        if hashlib.sha256(binary.read_bytes()).hexdigest()!=MC_SHA256: raise ValueError('MinIO client checksum differs')
        self.binary=str(binary)
        self.env={k:v for k,v in os.environ.items() if not k.startswith('MC_')}
        config=args.output/'mc';config.mkdir(mode=0o700)
        ca=config/'certs/CAs';ca.mkdir(parents=True,mode=0o700);shutil.copyfile(args.ca_file,ca/'root.crt')
        self.env.update(MC_CONFIG_DIR=str(config),MC_NO_COLOR='1',MC_DISABLE_PAGER='1')
        for alias,(user,password) in self.credentials.items():
            self.env['MC_HOST_'+alias]='https://'+quote(user,safe='')+':'+quote(password,safe='')+'@'+self.endpoint.netloc

    def timeout(self):
        remaining=self.deadline-time.monotonic()
        if remaining<=0: raise Blocked('Storage fencing deadline exceeded')
        return min(15,remaining)

    def check_certificate(self):
        with socket.create_connection((self.endpoint.hostname,self.endpoint.port or 443),timeout=self.timeout()) as raw:
            with self.tls.wrap_socket(raw,server_hostname=self.endpoint.hostname) as channel:
                if hashlib.sha256(channel.getpeercert(binary_form=True)).hexdigest()!=self.args.certificate_sha256:
                    raise ValueError('Storage certificate identity differs')

    def call(self,arguments,body=None,allowed_error=None):
        self.check_certificate()
        result=subprocess.run([self.binary,'--json',*arguments],input=body,capture_output=True,
                              env=self.env,timeout=self.timeout())
        with private_file(self.args.output/('client-'+uuid.uuid4().hex+'.log')) as log:
            log.write(result.stdout);log.write(result.stderr)
        if len(result.stdout)>8*1024*1024: raise ValueError('Storage response exceeds inventory bound')
        values=[json.loads(line) for line in result.stdout.splitlines()]
        # In the pinned mc, admin info can return exit 0 with status:error.
        for value in values:
            if isinstance(value,dict) and value.get('status')=='error':
                error=value.get('error')
                for key in ('cause','error','Code'):
                    error=error.get(key) if isinstance(error,dict) else None
                code=error
                if allowed_error and code==allowed_error and len(values)==1: return None
                raise Blocked('Storage command rejected; private response retained')
        if result.returncode: raise Blocked('Storage command failed; private response retained')
        return values

    def one(self,arguments,**kwargs):
        values=self.call(arguments,**kwargs)
        if values is None: return None
        if len(values)!=1: raise ValueError('Expected one storage response')
        return values[0]

    def deployment(self,alias):
        if self.one(['admin','info',alias])['info']['deploymentID']!=self.args.deployment_id:
            raise ValueError('Storage deployment identity differs')

    def root_denied(self):
        user,password=self.credentials['origin']
        path=presigned_path('GET','/',user,password,self.endpoint.netloc,self.args.region)
        channel=http.client.HTTPSConnection(self.endpoint.hostname,self.endpoint.port or 443,
                                            context=self.tls,timeout=self.timeout())
        try:
            channel.connect()
            if hashlib.sha256(channel.sock.getpeercert(binary_form=True)).hexdigest()!=self.args.certificate_sha256:
                raise ValueError('Storage certificate identity differs')
            channel.request('GET',path)
            response=channel.getresponse();body=response.read(1024*1024+1)
            if len(body)>1024*1024: raise ValueError('Root probe response exceeds bound')
            if response.status==200: return False
            if response.status==403 and ET.fromstring(body).findtext('Code')=='InvalidAccessKeyId': return True
            raise Blocked('Root S3 access was not conclusively accepted or disabled')
        finally: channel.close()

    def marker(self,expected):
        value=self.one(['admin','policy','info','recovery',MARKER])
        if canonical(value['policyInfo']['Policy'])!=canonical(expected):
            raise ValueError('Another recovery policy is preserved')

    def inventory(self,alias,user):
        users=self.call(['admin','user','ls',alias])
        if len(users)>1 or any(v.get('accessKey')!=user or v.get('userStatus')!='enabled'
                or v.get('policyName','') not in ('',MARKER) for v in users):
            raise ValueError('Unrelated IAM users are preserved')
        groups=self.call(['admin','group','ls',alias])
        if groups!=[{'status':'success'}]: raise ValueError('IAM groups require a separate recovery contract')
        for name in [self.args.root_user]+([user] if users else []):
            if self.call(['admin','user','svcacct','ls',alias,name]):
                raise ValueError('Service accounts require a separate recovery contract')
        if self.one(['admin','replicate','info',alias])!={'enabled':False}:
            raise ValueError('Site replication requires a separate recovery contract')
        buckets=self.call(['ls',alias])
        if len(buckets)>1000: raise ValueError('Storage bucket inventory exceeds bound')
        for bucket in buckets:
            if bucket.get('type')!='folder': raise ValueError('Invalid bucket inventory')
            value=self.one(['anonymous','get-json',alias+'/'+bucket['key']])
            if value.get('permission')!='private': raise ValueError('Anonymous bucket policy is preserved')
        return bool(users),len(buckets)


def execute(args,report):
    endpoint=urlsplit(args.endpoint)
    if endpoint.scheme!='https' or not endpoint.hostname or endpoint.username or endpoint.password or endpoint.path not in ('','/') or endpoint.query or endpoint.fragment:
        raise ValueError('Explicit credential-free HTTPS storage origin required')
    if not re.fullmatch('[0-9a-f]{64}',args.certificate_sha256) or not re.fullmatch('[a-z0-9-]+',args.region):
        raise ValueError('Certificate SHA256 and storage region required')
    if any(str(uuid.UUID(value))!=value for value in (args.deployment_id,args.recovery_id)) or not 10<=args.timeout<=300:
        raise ValueError('Canonical deployment/recovery UUIDs and bounded timeout required')
    if not re.fullmatch('[A-Za-z0-9_-]{3,64}',args.root_user): raise ValueError('Explicit root identity required')
    with read_private(args.root_password_file) as source: old_password=source.read(4097).decode()
    if not 8<=len(old_password)<=4096 or any(c in old_password for c in '\n\r\0'):
        raise ValueError('Private original root password required without a trailing newline')
    identity={'formatVersion':1,'endpoint':args.endpoint,'certificateSha256':args.certificate_sha256,
        'deploymentId':args.deployment_id,'recoveryId':args.recovery_id,'rootUser':args.root_user,'region':args.region,
        'originalPasswordSha256':hashlib.sha256(old_password.encode()).hexdigest()}
    directory=args.state_directory;directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    info=directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_mode&0o077 or info.st_uid!=os.geteuid():
        raise ValueError('Recovery directory must be private and owned')
    fd=os.open(directory/'.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise Blocked('Recovery state already in use')
        path=directory/'recovery.json'
        user='edgeai-recovery-'+uuid.UUID(args.recovery_id).hex
        if path.exists():
            with read_private(path) as source: state=json.load(source)
            if {k:state.get(k) for k in identity}!=identity or state.get('user')!=user or not re.fullmatch('[0-9a-f]{64}',state.get('password','')):
                raise ValueError('Recovery state identity differs')
        else:
            state={**identity,'user':user,'password':secrets.token_hex(32)};persist(path,state)
        client=Client(args,state,old_password)
        confirmed=directory/'confirmed.json'
        denied=client.root_denied()
        if confirmed.exists():
            with read_private(confirmed) as source: previous=json.load(source)
            if previous!=identity or not denied: raise ValueError('Previously confirmed root fence changed')
        expected=policy(args)
        if not denied:
            client.deployment('origin')
            exists,buckets=client.inventory('origin',user)
            current=client.one(['admin','policy','info','origin',MARKER],allowed_error='XMinioAdminNoSuchPolicy')
            if current is not None and canonical(current['policyInfo']['Policy'])!=canonical(expected):
                raise ValueError('Another recovery policy is preserved')
            if current is None:
                if exists: raise ValueError('Existing recovery user has no ownership policy')
                policy_path=args.output/'policy.json';persist(policy_path,expected)
                client.call(['admin','policy','create','origin',MARKER,str(policy_path)])
            if not exists:
                client.call(['admin','user','add','origin'],body=(user+'\n'+state['password']+'\n').encode())
            value=client.one(['admin','user','info','origin',user])
            if value.get('policyName','')=='': client.call(['admin','policy','attach','origin',MARKER,'--user',user])
        client.deployment('recovery');client.marker(expected)
        exists,buckets=client.inventory('recovery',user)
        if not exists: raise Blocked('Recovery administrator is absent')
        report['recoveryAdministratorVerified']=True
        if not denied: client.call(['admin','config','set','recovery','api','root_access=off'])
        config=client.one(['admin','config','get','recovery','api'])
        root_values=[kv['value'] for section in config['config'] for kv in section['kv'] if kv['key']=='root_access']
        if root_values!=['off'] or not client.root_denied():
            raise Blocked('Root S3 access remains enabled or unconfirmed; inspect environment overrides')
        client.deployment('recovery');client.marker(expected);client.inventory('recovery',user)
        if not client.root_denied(): raise Blocked('Root access changed during final verification')
        if not confirmed.exists(): persist(confirmed,identity)
        report.update(status='SOURCE_STORAGE_ROOT_DISABLED',rootCredentialRejected=True,
                      bucketCount=buckets,recoveryPolicyRetained=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('endpoint','certificate-sha256','deployment-id','recovery-id','root-user'):
        parser.add_argument('--'+name,required=True)
    for name in ('ca-file','root-password-file','state-directory','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--region',default='us-east-1')
    parser.add_argument('--timeout',type=int,default=120)
    args=parser.parse_args();args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    report={'scope':'storage-root-credential-fence','status':'RUNNING','recoveryId':args.recovery_id,
        'recoveryAdministratorVerified':False,'rootCredentialRejected':False,
        'globalQuiescenceProven':False,'inFlightRequestsDrained':False,'activated':False}
    code=1
    try: execute(args,report);code=0
    except Exception as error:
        code=2 if isinstance(error,Blocked) else 1
        report.update(status='BLOCKED' if code==2 else 'FAIL',failureType=type(error).__name__,fenceStateUnconfirmed=True)
    finally: persist(args.output/'fence-report.json',report)
    print(report['status']+': source storage root fence; private recovery state retained')
    return code


if __name__=='__main__': raise SystemExit(main())
