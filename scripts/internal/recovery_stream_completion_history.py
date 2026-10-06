"""Read original checkpoint receipt ancestry from an independent completion backup.

This proves receipt/payload consistency only. Producer retirement, original start
authority, work contracts and database adoption remain the caller's responsibility.
"""
from datetime import datetime
import hashlib
import json
import re

from postgres_backup import Blocked
from recovery_remote_fence import unique
from recovery_remote_inventory import uid
from recovery_remote_storage import header, version
from recovery_stream_results import checkpoint_object
from storage_backup import validate_manifest

MEDIA_TYPE='application/vnd.edgeai.stream-checkpoint-authority+json'
MAX_BYTES=128*1024
FIELDS={'id','run_id','task_id','attempt_id','runtime_id','epoch','producer_pod_uid','service_profile_version_id',
        'previous_id','serial','state_revision','sha256','execution_sha256','bytes','generation_ids','summary_json',
        'bucket','object_key','object_version','created_at','handover_from_id'}


def instant(value):
    if not isinstance(value,str) or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|[+-]\d\d:\d\d)',value):
        raise Blocked('Checkpoint receipt requires an original microsecond ISO8601 instant')
    try:return datetime.fromisoformat(value)
    except ValueError as error:raise Blocked('Invalid checkpoint receipt instant') from error


def same_receipt(a,b):
    return (isinstance(a,dict) and isinstance(b,dict) and set(a)==set(b)==FIELDS and
            {k:v for k,v in a.items() if k!='created_at'}=={k:v for k,v in b.items() if k!='created_at'} and
            instant(a['created_at'])==instant(b['created_at']))


def read_history(store,manifest,completion,bucket):
    validate_manifest(manifest)
    if (bucket not in manifest['buckets'] or store.identity()!=manifest['targetDeploymentId'] or
            not isinstance(completion,dict) or completion.get('apiVersion')!='edgeai.stream.completion/v1'):
        raise Blocked('Completion ancestry requires its independent backup installation')
    store.versioned(bucket);uid(completion['runId'])
    terminals=completion.get('checkpoints')
    if (not isinstance(terminals,list) or not 1<=len(terminals)<=128 or
            any(not isinstance(c,dict) or set(c)!=FIELDS for c in terminals) or
            len({c['task_id'] for c in terminals})!=len(terminals)):
        raise Blocked('Completion ancestry requires one original terminal receipt per task')
    receipts={};objects={};payloads={}
    def read(identity):
        uid(identity)
        key='authority/stream-checkpoint/'+identity+'.json'
        matches=[v for v in manifest['versions'] if v['bucket']==bucket and v['key']==key]
        if len(matches)!=1 or not 2<=matches[0]['bytes']<=MAX_BYTES:
            raise Blocked('Original checkpoint receipt is absent or ambiguous in the backup')
        item=matches[0]
        code,heads,_=store.request('HEAD','/'+bucket+'/'+key)
        if code!=200 or header(heads,'x-amz-version-id')!=version(item['versionId']):
            raise Blocked('Original checkpoint receipt head differs from the backup')
        code,heads,body=store.request('GET','/'+bucket+'/'+key,{'versionId':item['versionId']},max_bytes=MAX_BYTES)
        if (code!=200 or header(heads,'x-amz-version-id')!=item['versionId'] or header(heads,'content-type')!=MEDIA_TYPE or
                header(heads,'content-length')!=str(item['bytes']) or len(body)!=item['bytes'] or
                hashlib.sha256(body).hexdigest()!=item['sha256'] or any(k.lower() in ('content-encoding','transfer-encoding') for k,v in heads)):
            raise Blocked('Original checkpoint receipt bytes differ from the backup')
        try:document=json.loads(body,object_pairs_hook=unique)
        except (ValueError,UnicodeError) as error:raise Blocked('Invalid checkpoint receipt JSON') from error
        if (not isinstance(document,dict) or set(document)!={'apiVersion','id','runId','namespace','checkpoint'} or
                document['apiVersion']!='edgeai.stream.checkpoint-authority/v1' or document['id']!=identity or
                document['runId']!=completion['runId'] or document['namespace']!=completion['namespace']):
            raise Blocked('Checkpoint receipt belongs to another original scope')
        cp=document['checkpoint']
        if not isinstance(cp,dict) or set(cp)!=FIELDS or cp['id']!=identity or cp['run_id']!=completion['runId']:
            raise Blocked('Checkpoint receipt identity differs')
        for k in ('task_id','attempt_id','runtime_id','producer_pod_uid','service_profile_version_id'):uid(cp[k])
        for k in ('previous_id','handover_from_id'):
            if cp[k] is not None:uid(cp[k])
        if (any(type(cp[k]) is not int or cp[k]<0 for k in ('serial','state_revision','epoch','bytes')) or cp['epoch']<1 or
                cp['state_revision']>cp['serial'] or cp['serial']>9007199254740991 or cp['bytes']<1):
            raise Blocked('Invalid original checkpoint receipt counters')
        instant(cp['created_at']);objects[identity]=item
        payloads[identity]=checkpoint_object(store,manifest,cp,terminal=False)
        return cp
    for terminal in terminals:
        current=read(terminal['id'])
        if not same_receipt(current,terminal):raise Blocked('Terminal checkpoint receipt conflicts with the completion grant')
        seen=set()
        while True:
            if current['id'] in seen:raise Blocked('Checkpoint receipt ancestry contains a cycle')
            seen.add(current['id']);receipts[current['id']]=current
            if current['previous_id'] is None:
                if current['handover_from_id'] is not None:raise Blocked('First checkpoint cannot inherit a missing predecessor')
                break
            previous=receipts.get(current['previous_id']) or read(current['previous_id'])
            if (previous['task_id']!=terminal['task_id'] or previous['serial']>=current['serial'] or
                    previous['state_revision']>current['state_revision'] or previous['epoch']>current['epoch'] or
                    previous['execution_sha256']!=current['execution_sha256'] or
                    previous['service_profile_version_id']!=current['service_profile_version_id'] or
                    instant(previous['created_at'])>instant(current['created_at'])):
                raise Blocked('Checkpoint ancestry changes the original task, contract or order')
            if current['handover_from_id'] is None:
                if any(current[k]!=previous[k] for k in ('attempt_id','runtime_id','epoch','producer_pod_uid','generation_ids')):
                    raise Blocked('Checkpoint ancestry requires an explicit original handover')
            elif (current['handover_from_id']!=previous['id'] or current['serial']!=previous['serial']+1 or
                    current['state_revision']!=previous['state_revision']):
                raise Blocked('Checkpoint handover does not retain its original predecessor and computation')
            current=previous
    for item in objects.values():
        code,heads,_=store.request('HEAD','/'+bucket+'/'+item['key'])
        if code!=200 or header(heads,'x-amz-version-id')!=item['versionId']:
            raise Blocked('Checkpoint receipt head changed during observation')
    if store.identity()!=manifest['targetDeploymentId']:raise Blocked('Checkpoint backup identity changed during observation')
    store.versioned(bucket)
    return {'receipts':sorted(receipts.values(),key=lambda c:(c['task_id'],c['serial'])),
            'objects':sorted(objects.values(),key=lambda o:o['key']),
            'payloads':sorted(payloads.values(),key=lambda o:o['checkpointId'])}
