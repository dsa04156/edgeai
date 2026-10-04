"""Verify completion of S3 requests after a confirmed root fence on one MinIO server."""
import argparse
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time
from types import SimpleNamespace
from urllib.parse import urlsplit
import uuid

from postgres_backup import Blocked, read_private
from recovery_storage_fence import Client, persist, policy

TOTAL='minio_api_requests_total'
INFLIGHT='minio_api_requests_inflight_total'
WAITING='minio_api_requests_waiting_total'
IDENTITY=('formatVersion','endpoint','certificateSha256','deploymentId','recoveryId','rootUser','region','originalPasswordSha256')


def metrics_snapshot(document):
    if not isinstance(document,list) or not 1<=len(document)<=100:
        raise Blocked('Storage metrics are unavailable or incomplete')
    families={}
    for family in document:
        if not isinstance(family,dict) or not isinstance(family.get('name'),str) or family['name'] in families:
            raise Blocked('Duplicate or invalid storage metrics')
        families[family['name']]=family
    values={};servers=set()
    for name in (TOTAL,INFLIGHT,WAITING):
        if name not in families:
            if name==TOTAL: raise Blocked('Storage completion counters are absent')
            values[name]={};continue
        family=families[name]
        if family.get('type')!=('COUNTER' if name==TOTAL else 'GAUGE') or not isinstance(family.get('metrics'),list):
            raise Blocked('Unexpected storage metric type')
        rows={}
        for sample in family['metrics']:
            if not isinstance(sample,dict):raise Blocked('Invalid storage metric sample')
            labels=sample.get('labels',{})
            expected={'server','type'}|({'name'} if name!=WAITING else set())
            if not isinstance(labels,dict) or set(labels)!=expected or labels['type']!='s3' or not isinstance(labels['server'],str) or not labels['server']:
                raise Blocked('Storage metric identity is incomplete')
            servers.add(labels['server'])
            operation=labels.get('name','waiting')
            if not isinstance(operation,str) or not re.fullmatch('[A-Za-z][A-Za-z0-9]{0,63}',operation) or operation in rows:
                raise Blocked('Duplicate or invalid storage operation counter')
            value=sample.get('value')
            if not isinstance(value,str) or not 1<=len(value)<=64:raise Blocked('Invalid storage counter value')
            try:number=Decimal(value)
            except InvalidOperation:raise Blocked('Invalid storage counter value') from None
            if not number.is_finite() or not 0<=number<=2**53 or number!=number.to_integral_value():
                raise Blocked('Invalid storage counter value')
            rows[operation]=int(number)
        values[name]=rows
    if len(servers)!=1 or values[TOTAL].get('ListBuckets',0)<1:
        raise Blocked('Exactly one server with a root-probe completion counter is required')
    return {'server':next(iter(servers)),'completedProbes':values[TOTAL]['ListBuckets'],
            'inFlight':sum(values[INFLIGHT].values()),'waiting':sum(values[WAITING].values())}


def server_identity(client):
    info=client.one(['admin','info','recovery'])['info']
    servers=info.get('servers')
    if info.get('deploymentID')!=client.args.deployment_id: raise ValueError('Storage deployment identity differs')
    if not isinstance(servers,list) or len(servers)!=1 or servers[0].get('state')!='online':
        raise Blocked('Only a single healthy MinIO server is supported by this drain verifier')
    server=servers[0]
    endpoint=server.get('endpoint')
    if not isinstance(endpoint,str) or not endpoint: raise Blocked('MinIO server endpoint identity missing')
    return {key:server.get(key) for key in ('endpoint','version','commitID')}


def execute(args,report):
    try:return verify(args,report)
    except (OSError,subprocess.TimeoutExpired):
        raise Blocked('Storage drain observations are unavailable or exceeded the deadline') from None


def verify(args,report):
    directory=args.state_directory
    info=directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_mode&0o077 or info.st_uid!=os.geteuid():
        raise ValueError('Private owned root-fence state directory required')
    if not 10<=args.timeout<=300: raise ValueError('Drain timeout must be 10 to 300 seconds')
    with read_private(directory/'.lock') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_SH|fcntl.LOCK_NB)
        except BlockingIOError:raise Blocked('Root-fence state is being changed')
        with read_private(directory/'recovery.json') as source:state=json.load(source)
        with read_private(directory/'confirmed.json') as source:confirmed=json.load(source)
        if confirmed!={key:state.get(key) for key in IDENTITY} or confirmed.get('formatVersion')!=1:
            raise ValueError('Root-fence confirmation does not match recovery state')
        for key in ('recoveryId','deploymentId'):
            if str(uuid.UUID(state[key]))!=state[key]:raise ValueError('Invalid recovery/deployment identity')
        if state.get('user')!='edgeai-recovery-'+uuid.UUID(state['recoveryId']).hex or not re.fullmatch('[0-9a-f]{64}',state.get('password','')):
            raise ValueError('Invalid recovery credential identity')
        endpoint=urlsplit(state['endpoint'])
        if endpoint.scheme!='https' or not endpoint.hostname or endpoint.username or endpoint.password or endpoint.path not in ('','/') or endpoint.query or endpoint.fragment:
            raise ValueError('HTTPS storage origin required')
        if not re.fullmatch('[0-9a-f]{64}',state['certificateSha256']) or not re.fullmatch('[a-z0-9-]+',state['region']):
            raise ValueError('Invalid certificate or region identity')
        with read_private(args.root_password_file) as source:password=source.read(4097).decode()
        if hashlib.sha256(password.encode()).hexdigest()!=state['originalPasswordSha256']:
            raise ValueError('Original root credential differs from confirmed fence')
        config=SimpleNamespace(endpoint=state['endpoint'],ca_file=args.ca_file,certificate_sha256=state['certificateSha256'],
            deployment_id=state['deploymentId'],recovery_id=state['recoveryId'],root_user=state['rootUser'],
            region=state['region'],timeout=args.timeout,output=args.output)
        report['recoveryId']=config.recovery_id
        client=Client(config,state,password)
        original_server=server_identity(client)
        expected=policy(config);client.marker(expected)
        client.inventory('recovery',state['user'])
        if not client.root_denied(): raise Blocked('Original root access has been reopened')
        report['rootFenceVerified']=True
        def sample():
            return metrics_snapshot(client.one(['admin','prometheus','metrics','recovery','api','--api-version','v3']))
        previous=sample();identity=previous['server']
        maximum=previous['inFlight'];observations=1;fresh_zeroes=0
        while True:
            client.timeout()
            if not client.root_denied():raise Blocked('Original root access changed while draining')
            current=sample();observations+=1;maximum=max(maximum,current['inFlight'])
            if current['server']!=identity:raise Blocked('Metric server identity changed while draining')
            report.update(maxObservedInFlight=maximum,metricObservations=observations,
                          remainingInFlight=current['inFlight'],remainingQueued=current['waiting'])
            # This cumulative counter includes the completed, denied GET / probe. The
            # misleading incoming_total gauge resets on collection and is not used.
            if current['completedProbes']<=previous['completedProbes']:
                fresh_zeroes=0
            elif current['inFlight']==0 and current['waiting']==0:
                fresh_zeroes+=1
            else:fresh_zeroes=0
            previous=current
            if fresh_zeroes>=2:
                if server_identity(client)!=original_server:raise Blocked('MinIO server identity changed')
                client.marker(expected)
                if not client.root_denied():raise Blocked('Original root access changed during final verification')
                report.update(status='SOURCE_STORAGE_REQUESTS_DRAINED',inFlightRequestsDrained=True,
                              freshZeroObservations=fresh_zeroes,verifiedAt=datetime.now(timezone.utc).isoformat())
                return
            time.sleep(min(.2,client.timeout()))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('state-directory','ca-file','root-password-file','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--timeout',type=int,default=120)
    args=parser.parse_args();args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    report={'scope':'single-minio-s3-request-drain','status':'RUNNING','rootFenceVerified':False,
            'inFlightRequestsDrained':False,'globalQuiescenceProven':False,'activated':False}
    code=1
    try:execute(args,report);code=0
    except Exception as error:
        code=2 if isinstance(error,Blocked) else 1
        report.update(status='BLOCKED' if code==2 else 'FAIL',failureType=type(error).__name__)
    finally:persist(args.output/'drain-report.json',report)
    print(report['status']+': single-server S3 drain verification; private report retained')
    return code


if __name__=='__main__':raise SystemExit(main())
