"""Copy immutable object versions to a distinct MinIO installation and verify their bytes."""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import tempfile
import time
from urllib.parse import quote, urlsplit
import urllib.request
import uuid

from postgres_backup import Blocked, private_file, read_private

ROOT = Path(__file__).resolve().parents[1]
MC_SHA256 = '01f866e9c5f9b87c2b09116fa5d7c06695b106242d829a8bb32990c00312e891'


def endpoint(prefix):
    values = [os.environ.get(prefix+suffix) for suffix in ['URL','USER','PASSWORD']]
    if not all(values): raise Blocked('Explicit storage URL, USER and PASSWORD environment is required')
    url, user, password = values
    parsed = urlsplit(url)
    if parsed.scheme not in ['http','https'] or not parsed.hostname or parsed.username or parsed.password or parsed.path not in ['', '/'] or parsed.query or parsed.fragment:
        raise ValueError('Storage URL must be a credential-free HTTP(S) origin')
    if parsed.scheme == 'http':
        try: loopback = ipaddress.ip_address(parsed.hostname).is_loopback
        except ValueError: loopback = parsed.hostname == 'localhost'
        if not loopback: raise ValueError('Cleartext storage is allowed only for loopback test endpoints')
    return url.rstrip('/'), parsed.scheme+'://'+quote(user,safe='')+':'+quote(password,safe='')+'@'+parsed.netloc


class Client:
    def __init__(self, directory, source=False):
        self.directory = directory
        self.binary = ROOT/'.tools/mc'
        if not self.binary.is_file(): raise Blocked('Run bash scripts/install-minio-client.sh first')
        if hashlib.sha256(self.binary.read_bytes()).hexdigest() != MC_SHA256:
            raise ValueError('MinIO client does not match the pinned checksum')
        config = directory/('mc-'+uuid.uuid4().hex)
        config.mkdir(mode=0o700)
        self.env = {k:v for k,v in os.environ.items() if not k.startswith('MC_')}
        self.env.update(MC_CONFIG_DIR=str(config), MC_NO_COLOR='1', MC_DISABLE_PAGER='1')
        self.target_url, self.env['MC_HOST_replica'] = endpoint('EDGEAI_BACKUP_STORAGE_')
        self.source_url = None
        if source: self.source_url, self.env['MC_HOST_origin'] = endpoint('EDGEAI_BACKUP_SOURCE_')
        ca = os.environ.get('EDGEAI_BACKUP_CA_FILE')
        if ca:
            ca_dir = config/'certs/CAs'; ca_dir.mkdir(mode=0o700,parents=True)
            shutil.copyfile(ca, ca_dir/'backup-ca.crt')
        self.log = directory/('client-'+uuid.uuid4().hex+'.log')
        with private_file(self.log): pass

    def call(self, arguments, destination=None, check=True, timeout=60):
        with self.log.open('ab') as log:
            result = subprocess.run([str(self.binary),'--json',*arguments], env=self.env,
                stdout=destination if destination is not None else subprocess.PIPE, stderr=log, timeout=timeout)
            if destination is None: log.write(result.stdout)
        if check and result.returncode: raise RuntimeError('Storage operation failed; private diagnostics retained')
        return result

    def json(self, arguments):
        return json.loads(self.call(arguments).stdout)

    def deployment(self, alias):
        identity = self.json(['admin','info',alias])['info']['deploymentID']
        if not identity: raise ValueError('Storage deployment identity is absent')
        return identity

    def versions(self, alias, bucket):
        result = []
        raw = self.call(['ls','--versions','--recursive',alias+'/'+bucket]).stdout
        for line in raw.splitlines():
            item = json.loads(line)
            if item.get('status') != 'success': raise ValueError('Invalid object inventory')
            if item.get('isDeleteMarker'): continue
            if item.get('type') != 'file': raise ValueError('Unexpected object inventory entry')
            version = item.get('versionId')
            if not version or version == 'null': raise ValueError('Unversioned objects cannot form this backup')
            result.append({'bucket':bucket,'key':item['key'],'versionId':version,'bytes':item['size']})
        return result

    def digest(self, alias, item):
        with tempfile.TemporaryFile(dir=self.directory) as content:
            self.call(['cat','--version-id',item['versionId'],alias+'/'+item['bucket']+'/'+item['key']],
                      destination=content,timeout=300)
            size = content.tell(); content.seek(0)
            digest = hashlib.file_digest(content,'sha256').hexdigest()
        if size != item['bytes']: raise ValueError('Object version length differs from inventory')
        return digest

    def configuration(self, bucket):
        result = self.call(['replicate','export','origin/'+bucket], check=False)
        value = json.loads(result.stdout)
        if result.returncode:
            error = value.get('error',{})
            # Verify the precise S3 error; authentication and network failures are not "no config".
            cause = error.get('cause',{}).get('error',{})
            if cause.get('Code') == 'ReplicationConfigurationNotFoundError': return None
            raise RuntimeError('Cannot read source replication configuration; private diagnostics retained')
        configuration = value['config']
        return configuration if configuration.get('Rules') else None

    def remote_target_arns(self, bucket):
        # The pinned mc has no command for unattached targets. Read the scoped admin API;
        # keep any credential-bearing fields in memory and return only the target identities.
        parsed=urlsplit(self.source_url)
        path='/minio/admin/v3/list-remote-targets'
        query='bucket='+quote(bucket,safe='')+'&type='
        now=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ');day=now[:8]
        region=os.environ.get('EDGEAI_BACKUP_SOURCE_REGION','us-east-1')
        if not re.fullmatch('[a-z0-9-]+',region):raise ValueError('Invalid source region')
        payload=hashlib.sha256(b'').hexdigest()
        headers={'host':parsed.netloc,'x-amz-content-sha256':payload,'x-amz-date':now}
        signed=';'.join(sorted(headers))
        canonical='\n'.join(['GET',path,query,''.join(k+':'+headers[k]+'\n' for k in sorted(headers)),signed,payload])
        scope=day+'/'+region+'/s3/aws4_request'
        signing='\n'.join(['AWS4-HMAC-SHA256',now,scope,hashlib.sha256(canonical.encode()).hexdigest()])
        key=('AWS4'+os.environ['EDGEAI_BACKUP_SOURCE_PASSWORD']).encode()
        for value in [day,region,'s3','aws4_request']:key=hmac.new(key,value.encode(),hashlib.sha256).digest()
        signature=hmac.new(key,signing.encode(),hashlib.sha256).hexdigest()
        headers['Authorization']='AWS4-HMAC-SHA256 Credential='+os.environ['EDGEAI_BACKUP_SOURCE_USER']+'/'+scope+', SignedHeaders='+signed+', Signature='+signature
        tls=ssl.create_default_context()
        if os.environ.get('EDGEAI_BACKUP_CA_FILE'):tls.load_verify_locations(os.environ['EDGEAI_BACKUP_CA_FILE'])
        request=urllib.request.Request(self.source_url+path+'?'+query,headers=headers)
        with urllib.request.urlopen(request,context=tls,timeout=15) as response:targets=json.load(response)
        return {target['arn'] for target in targets or []}

    def remove_owned_rule(self, bucket, rule_id):
        current = self.configuration(bucket)
        targets=self.remote_target_arns(bucket)
        if current is None:
            if targets:raise RuntimeError('Unattached remote targets remain; refusing unproven ownership cleanup')
            return
        if len(current['Rules']) != 1 or current['Rules'][0]['ID'] != rule_id:
            raise RuntimeError('Replication configuration changed; refusing to remove another rule')
        if targets != {current['Rules'][0]['Destination']['Bucket']}:
            raise RuntimeError('Remote targets changed; refusing to delete another target')
        # The pinned client cannot remove its final rule by ID (it submits an invalid empty rule list).
        # Only a verified sole rule created by this operation can use DeleteBucketReplication.
        self.call(['replicate','remove','origin/'+bucket,'--all','--force'])
        if self.configuration(bucket) is not None or self.remote_target_arns(bucket): raise RuntimeError('Owned replication configuration remains')


def validate_manifest(manifest):
    if manifest.get('formatVersion') != 1 or manifest.get('scope') != 'minio-object-versions':
        raise ValueError('Unsupported storage manifest')
    seen = set()
    for item in manifest['versions']:
        if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]',item['bucket']): raise ValueError('Invalid bucket')
        if not isinstance(item['key'],str) or not item['key'] or '\x00' in item['key']: raise ValueError('Invalid key')
        if not isinstance(item['versionId'],str) or not item['versionId'] or item['versionId']=='null': raise ValueError('Invalid version')
        if type(item['bytes']) is not int or item['bytes'] < 0 or not re.fullmatch('[a-f0-9]{64}',item['sha256']): raise ValueError('Invalid content proof')
        identity = item['bucket'],item['key'],item['versionId']
        if identity in seen: raise ValueError('Duplicate object version')
        seen.add(identity)


def backup(client, buckets, timeout):
    source_id, target_id = client.deployment('origin'), client.deployment('replica')
    if source_id == target_id: raise ValueError('Source and backup must be different MinIO installations')
    if len(set(buckets)) != len(buckets): raise ValueError('Duplicate bucket')
    inventory = []
    for bucket in buckets:
        if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]',bucket): raise ValueError('Invalid bucket')
        if client.configuration(bucket) is not None: raise ValueError('Source already has replication rules; explicit integration is required')
        if client.remote_target_arns(bucket):raise ValueError('Source has preexisting remote targets; explicit integration is required')
        # An existing target is never adopted or overwritten, even if empty.
        targets = [json.loads(line)['key'].rstrip('/') for line in client.call(['ls','replica']).stdout.splitlines()]
        if bucket in targets: raise ValueError('Backup bucket already exists')
        if client.json(['version','info','origin/'+bucket]).get('versioning',{}).get('status') != 'Enabled':
            raise ValueError('Source bucket versioning is not enabled')
        for item in client.versions('origin',bucket):
            item['sha256'] = client.digest('origin',item)
            inventory.append(item)
    report = {'status':'RUNNING','scope':'minio-object-versions','createdBuckets':[],'ownedRulesRemoved':False}
    rules = []
    manifest = {'formatVersion':1,'scope':'minio-object-versions','sourceDeploymentId':source_id,
        'targetDeploymentId':target_id,'buckets':buckets,'versions':inventory,
        'inventoryFinishedAt':datetime.now(timezone.utc).isoformat(),
        'excluded':['database-snapshot','bucket-delete-marker-visibility','IAM-and-TLS-identities','broker-and-device-journals','live-runtime-reconciliation']}
    validate_manifest(manifest)
    try:
        for bucket in buckets:
            client.call(['mb','replica/'+bucket]); report['createdBuckets'].append(bucket)
            client.call(['version','enable','replica/'+bucket])
            rule_id = 'edgeai-backup-'+uuid.uuid4().hex
            rules.append((bucket,rule_id))
            client.call(['replicate','add','origin/'+bucket,'--remote-bucket','replica/'+bucket,'--id',rule_id,
                         '--priority','1','--replicate','existing-objects','--disable-proxy','--sync'])
        deadline = time.monotonic()+timeout
        for item in inventory:
            while True:
                try:
                    actual = client.digest('replica',item)
                    if actual != item['sha256']: raise ValueError('Backup object checksum differs from captured source version')
                    break
                except RuntimeError:
                    if time.monotonic() >= deadline: raise TimeoutError('Captured object versions did not reach the backup in time')
                    time.sleep(.5)
        for bucket,rule_id in rules: client.remove_owned_rule(bucket,rule_id)
        report['ownedRulesRemoved'] = True
        manifest['verifiedAt'] = datetime.now(timezone.utc).isoformat()
        with private_file(client.directory/'manifest.json','w') as f:
            json.dump(manifest,f,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())
        report.update(status='BACKED_UP_OBJECT_VERSIONS',versionCount=len(inventory),bytes=sum(v['bytes'] for v in inventory))
    finally:
        if report['status'] == 'RUNNING': report['status'] = 'FAIL'
        if not report['ownedRulesRemoved']:
            failures = []
            for bucket,rule_id in rules:
                try: client.remove_owned_rule(bucket,rule_id)
                except Exception as error: failures.append(type(error).__name__)
            report['ownedRulesRemoved'] = not failures
            report['cleanupFailureTypes'] = failures
            report['status'] = 'FAIL'
        with private_file(client.directory/'backup-report.json','w') as f: json.dump(report,f,indent=2)
    return report


def verify(client, manifest):
    validate_manifest(manifest)
    if client.deployment('replica') != manifest['targetDeploymentId']: raise ValueError('Backup deployment identity differs')
    for item in manifest['versions']:
        if client.digest('replica',item) != item['sha256']: raise ValueError('Backup object checksum differs')
    return {'scope':'minio-object-versions','status':'VERIFIED_OBJECT_VERSIONS','versionCount':len(manifest['versions'])}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['backup','verify'])
    p.add_argument('--bucket',action='append',default=[])
    p.add_argument('--output',type=Path)
    p.add_argument('--input',type=Path)
    p.add_argument('--timeout-seconds',type=int,default=180)
    a=p.parse_args()
    if a.action=='backup' and (not a.bucket or a.output is None): p.error('backup requires --bucket and a new --output directory')
    if a.action=='verify' and a.input is None: p.error('verify requires --input bundle-directory')
    if not 1 <= a.timeout_seconds <= 3600: p.error('timeout must be 1..3600 seconds')
    output=None
    output_created=False
    try:
        output = a.output.absolute() if a.action=='backup' else ROOT/'.tools'/('storage-backup-verify-'+uuid.uuid4().hex)
        output.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        output.mkdir(mode=0o700,exist_ok=False)
        output_created=True
        client=Client(output,source=a.action=='backup')
        if a.action=='backup':
            # Serialize this CLI on this host; replication admins on other hosts must coordinate too.
            lock=ROOT/'.tools'/('storage-backup-lock-'+hashlib.sha256(client.source_url.encode()).hexdigest())
            descriptor=os.open(lock,os.O_CREAT|os.O_WRONLY|os.O_NOFOLLOW,0o600)
            with os.fdopen(descriptor,'w') as lease:
                fcntl.flock(lease,fcntl.LOCK_EX|fcntl.LOCK_NB)
                report=backup(client,a.bucket,a.timeout_seconds)
        else:
            with read_private(a.input/'manifest.json') as f: manifest=json.load(f)
            report=verify(client,manifest)
            with private_file(output/'verification-report.json','w') as f: json.dump(report,f,indent=2)
        print(report['status']+': '+str(report['versionCount'])+' immutable versions; full platform recovery remains separate')
        return 0
    except Blocked as error: print('BLOCKED: '+str(error));return 2
    except Exception as error:
        if output_created:
            try:
                with private_file(output/('failure-'+uuid.uuid4().hex+'.json'),'w') as f:
                    json.dump({'status':'FAIL','failureType':type(error).__name__,'message':str(error)},f)
            except OSError: pass
        print('FAIL: storage backup '+a.action+' ('+type(error).__name__+'); private diagnostics retained');return 1
    finally:
        if output_created:print('Private diagnostics: '+str(output))


if __name__=='__main__': raise SystemExit(main())
