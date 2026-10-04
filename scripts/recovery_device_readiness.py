"""Join restored Device data with live immutable checkpoint bytes and retired original MQTT authority.

Observations never authorize activation: original process retirement and whole-environment recovery remain separate.
"""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import os
from pathlib import Path
import re
import tempfile
import time
from types import SimpleNamespace
from urllib.parse import urlsplit
import uuid

from postgres_backup import Blocked, Postgres
import private_material_backup as material
import recovery_device_journal as journal
import recovery_references as references
import recovery_mqtt_fence as mqtt
from storage_backup import Client
from edgeai_runner import stream_checkpoint as checkpoint


def observe_broker(args, broker_digest, actor):
    directory=args.mqtt_state_directory.absolute(); journal.journals.private_directory(directory)
    state,state_sha=journal.read_json(directory/'recovery.json')
    required={'formatVersion','endpoint','certificateSha256','brokerDigest','adminUser','recoveryId','password'}
    if (set(state)!=required or type(state['formatVersion']) is not int or state['formatVersion']!=1 or
            state['brokerDigest']!=broker_digest or state['adminUser']!='edgeai-admin' or
            state['recoveryId']!=args.recovery_id or str(uuid.UUID(args.recovery_id))!=args.recovery_id or
            not re.fullmatch('[0-9a-f]{64}',state['certificateSha256']) or
            not re.fullmatch('[0-9a-f]{64}',state['password']) or not 10<=args.timeout<=300):
        raise ValueError('Exact private MQTT recovery identity required')
    endpoint=urlsplit(state['endpoint'])
    if (endpoint.scheme!='ssl' or not endpoint.hostname or not endpoint.port or endpoint.username or endpoint.password or
            endpoint.path or endpoint.query or endpoint.fragment): raise ValueError('Original TLS broker endpoint required')
    with material.private_input(args.mqtt_original_password_file,4096) as source: old=source.read().decode()
    if not old or '\x00' in old or old==state['password']: raise ValueError('Separate original administrator credential required')
    cfg=SimpleNamespace(endpoint=state['endpoint'],ca_file=args.mqtt_ca_file,certificate_sha256=state['certificateSha256'],
        admin_user=state['adminUser'],timeout=args.timeout,deadline=time.monotonic()+args.timeout)
    expected=args.recovery_id+'|'+broker_digest
    fd=os.open(directory/'.lock',os.O_RDWR|os.O_NOFOLLOW)
    with os.fdopen(fd,'r+') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise Blocked('MQTT recovery state is currently in use')
        if journal.read_json(directory/'recovery.json')[1]!=state_sha: raise Blocked('MQTT recovery state changed')
        try:
            with mqtt.Connection(cfg,state['password']) as connection:
                if not mqtt.marker(connection,expected): raise Blocked('Original broker recovery marker is absent')
                before=mqtt.inventory(connection,broker_digest)
                if any(not row.get('disabled',False) for name,row in before.items() if name!='edgeai-admin'):
                    raise Blocked('Original broker still permits an EdgeAI principal')
                try:
                    with mqtt.Connection(cfg,old): pass
                except mqtt.AuthenticationRejected: pass
                else: raise Blocked('Original broker administrator can still authenticate')
                after=mqtt.inventory(connection,broker_digest)
                if before!=after or not mqtt.marker(connection,expected): raise Blocked('Broker policy changed during observation')
        except mqtt.AuthenticationRejected:
            raise Blocked('Persisted recovery administrator is no longer accepted') from None
        if journal.read_json(directory/'recovery.json')[1]!=state_sha: raise Blocked('MQTT recovery state changed')
    name='edgeai-device-'+actor.id+'-'+str(actor.epoch)
    return {'scope':'fresh-original-mqtt-authority-observation','recoveryId':args.recovery_id,
        'endpoint':state['endpoint'],'certificateSha256':state['certificateSha256'],'brokerDigest':broker_digest,
        'recoveryStateSha256':state_sha,'principalInventorySha256':hashlib.sha256(journal.canonical(before)).hexdigest(),
        'disabledPrincipals':len(before)-1,'devicePrincipalPresent':name in before,
        'devicePrincipalAbsentOrDisabled':name not in before or before[name].get('disabled') is True,
        'oldAdministratorRejected':True,'brokerModified':False}


def verify_checkpoint_bytes(client,catalog):
    selected={row['checkpoint']['id']:row['checkpoint'] for row in catalog['routes'] if row['checkpoint'] is not None}
    verified=[]
    for identity,row in sorted(selected.items()):
        if not 0<row['bytes']<=checkpoint.MAX_BYTES: raise ValueError('Checkpoint size exceeds supported bounds')
        with tempfile.TemporaryFile(dir=client.directory) as content:
            client.call(['cat','--version-id',row['object_version'],'replica/'+row['bucket']+'/'+row['object_key']],
                destination=content,timeout=300)
            if content.tell()!=row['bytes']: raise Blocked('Checkpoint version length changed')
            content.seek(0); wire=content.read(checkpoint.MAX_BYTES+1)
        if hashlib.sha256(wire).hexdigest()!=row['sha256']: raise Blocked('Checkpoint version bytes changed')
        value=checkpoint.validate(wire); state=checkpoint.state_bytes(value['stateBase64'],value['manifest']['limits']['max_state_bytes'])
        summary={'manifest':value['manifest'],'revision':value['revision'],
            'routes':[{k:v for k,v in r.items() if k!='frames'} for r in value['routes']],
            'stateSha256':hashlib.sha256(state).hexdigest(),'stateBytes':len(state)}
        if (value['serial']!=row['serial'] or value['revision']!=row['state_revision'] or
                value['executionSha256']!=row['execution_sha256'] or summary!=row['summary_json']):
            raise Blocked('Checkpoint bytes do not describe the restored consumer metadata')
        verified.append({'checkpointId':identity,'sha256':row['sha256'],'bytes':row['bytes'],
            'versionId':row['object_version'],'serial':row['serial']})
    return verified


def stable(value,excluded): return {k:v for k,v in value.items() if k not in excluded}


def verify(pg,client,args):
    before=journal.compare(pg,args)
    if before['conflicts']: raise Blocked('Device journal and restored database have unresolved conflicts')
    _,bindings,_=journal.restored_journal(args.journal_restore); actor=bindings[0].producer
    sql=journal.query(args.run_id,actor)
    catalog=journal.database_inventory(pg,args.database,args.restore_report,sql)
    if hashlib.sha256(journal.canonical(catalog['guard'])).hexdigest()!=before['databaseGuardSha256']:
        raise Blocked('Restored database changed before external verification')
    broker=observe_broker(args,before['brokerDigest'],actor)
    storage=references.verify(pg,client,args.database,args.restore_report,args.storage_input)
    checkpoints=verify_checkpoint_bytes(client,catalog)
    current_storage=references.verify(pg,client,args.database,args.restore_report,args.storage_input)
    current_broker=observe_broker(args,before['brokerDigest'],actor)
    after=journal.compare(pg,args)
    if (stable(storage,{'verifiedAt','databaseSnapshot'})!=stable(current_storage,{'verifiedAt','databaseSnapshot'}) or
            broker!=current_broker or stable(before,{'verifiedAt'})!=stable(after,{'verifiedAt'})):
        raise Blocked('Recovery inputs or original broker authority changed across verification')
    return {'formatVersion':1,'scope':'device-recovery-data-authority-prerequisites',
        'status':'DEVICE_DATA_AND_ORIGINAL_BROKER_VERIFIED','recoveryId':args.recovery_id,
        'database':before,'storage':current_storage,'broker':current_broker,'checkpoints':checkpoints,
        'checkpointObjectsVerified':True,'oldBrokerAuthorityRetired':True,'databaseModified':False,'journalModified':False,
        'brokerModified':False,'activated':False,'producerProcessQuiescenceProven':False,'globalQuiescenceProven':False,
        'verifiedAt':datetime.now(timezone.utc).isoformat(),
        'excluded':['original-device-process-retirement','other-producer-retirement','new-broker-grants','service-activation']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('database','run-id','recovery-id'):parser.add_argument('--'+name,required=True)
    for name in ('restore-report','journal-restore','storage-input','mqtt-state-directory','mqtt-ca-file','mqtt-original-password-file','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--timeout',type=int,default=60)
    parser.add_argument('--transport',choices=['native','compose'],default='native');parser.add_argument('--pg-bin',type=Path)
    args=parser.parse_args();created=False
    try:
        args.output.mkdir(mode=0o700);created=True
        result=verify(Postgres(args.transport,args.pg_bin,diagnostics=args.output/'postgres'),Client(args.output),args)
        material.write_json(args.output/'readiness.json',result)
        print(result['status']+': fixed checkpoint bytes and original broker observed; source remains quarantined')
        return 0
    except Exception as error:
        blocked=isinstance(error,Blocked)
        if created:material.write_json(args.output/'failure.json',{'status':'BLOCKED' if blocked else 'FAIL',
            'failureType':type(error).__name__,'activated':False,'databaseModified':False,'journalModified':False,'brokerModified':False})
        print(('BLOCKED' if blocked else 'FAIL')+': Device recovery prerequisites; private diagnostics retained')
        return 2 if blocked else 1


if __name__=='__main__':raise SystemExit(main())
