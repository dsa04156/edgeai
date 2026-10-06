"""Actual mixed-group completion journal and copied references in a quarantined restore."""
from datetime import datetime
import hashlib
import json
from types import SimpleNamespace

from postgres_backup import Blocked,literal
from recovery_references import verify,reference_inventory
from recovery_remote_storage import Storage,header


def check(pg,database,receipt,bundle,client,pin,passed,report):
    manifest=json.loads((bundle/'manifest.json').read_text())
    assert pg.sql('SELECT count(*) FROM edgeai.stream_completion_publication',database)=='1'
    raw=pg.sql('SELECT document::text FROM edgeai.stream_completion_publication',database);doc=json.loads(raw)
    objects=[v for v in manifest['versions'] if v['key']=='authority/stream-completion/'+doc['id']+'.json']
    assert len(objects)==1;item=objects[0]
    storage=Storage(SimpleNamespace(certificate_sha256=pin,timeout=60))
    code,headers,body=storage.request('GET','/'+item['bucket']+'/'+item['key'],{'versionId':item['versionId']},max_bytes=16777216)
    assert code==200 and header(headers,'content-type')=='application/vnd.edgeai.stream-completion+json'
    assert header(headers,'x-amz-version-id')==item['versionId'] and len(body)==item['bytes']
    assert hashlib.sha256(body).hexdigest()==item['sha256'] and body==raw.encode()
    passed('original mixed completion authority survives source removal as one exact independently stored S3 version')
    assert len(doc['attemptIds'])==len(doc['taskCompletions'])==len(doc['checkpoints'])==2
    assert {p['kind'] for p in doc['producers']}=={'KUBERNETES','VD'}
    # SQL snapshots retain their original ISO8601 offsets; compare instants, not UTC spelling.
    assert all(datetime.fromisoformat(c['granted_at'])==datetime.fromisoformat(doc['grantedAt']) for c in doc['taskCompletions']+doc['deviceCompletions'])
    for cp in doc['checkpoints']:
        actual=json.loads(pg.sql('SELECT to_jsonb(c) FROM edgeai.stream_checkpoint c WHERE id='+literal(cp['id']),database))
        assert datetime.fromisoformat(cp['created_at'])==datetime.fromisoformat(actual['created_at'])
        assert {k:v for k,v in cp.items() if k!='created_at'}=={k:v for k,v in actual.items() if k!='created_at'}
    for nonce in json.loads(pg.sql('SELECT json_agg(claim_nonce::text) FROM edgeai.runtime_instance',database)):assert nonce not in raw
    passed('independent completion retains both original actors, same grant instant and terminal checkpoints without claim secrets')
    before=pg.sql('SELECT to_jsonb(p)::text FROM edgeai.stream_completion_publication p',database)
    verified=verify(pg,client,database,receipt,bundle)
    assert verified['referenceCounts']=={'result_artifact':0,'stream_checkpoint':5,'stream_completion_checkpoint':2}
    assert verified['verifiedVersionCount']==5 and pg.sql('SELECT to_jsonb(p)::text FROM edgeai.stream_completion_publication p',database)==before
    passed('real TLS backup verifies both copied completion references and all five original checkpoint versions')
    def corrupt(value):
        # Deliberate corruption of this owned inactive test restore only; restore exactly in finally.
        pg.sql('BEGIN; ALTER TABLE edgeai.stream_completion_publication DISABLE TRIGGER stream_completion_publication_immutable; '
            'UPDATE edgeai.stream_completion_publication SET document='+value+'; '
            'ALTER TABLE edgeai.stream_completion_publication ENABLE TRIGGER stream_completion_publication_immutable; COMMIT;',database)
    try:
        corrupt("document-'checkpoints'")
        try:reference_inventory(pg,database)
        except Blocked as error:assert 'checkpoint reference inventory' in str(error)
        else:raise AssertionError('Missing completion checkpoint list was accepted')
    finally:corrupt(literal(raw)+'::jsonb')
    assert pg.sql('SELECT to_jsonb(p)::text FROM edgeai.stream_completion_publication p',database)==before
    passed('malformed completion inventory is refused and the owned test corruption is restored exactly')
    try:
        corrupt("jsonb_set(document,'{checkpoints,0,object_version}', '\"missing-completion-version\"'::jsonb)")
        try:verify(pg,client,database,receipt,bundle)
        except ValueError as error:assert 'stream_completion_checkpoint version is absent' in str(error)
        else:raise AssertionError('Conflicting copied checkpoint escaped verification')
    finally:corrupt(literal(raw)+'::jsonb')
    assert pg.sql('SELECT to_jsonb(p)::text FROM edgeai.stream_completion_publication p',database)==before
    passed('a contradictory copied checkpoint version is refused even when the original checkpoint table remains valid')
    report.update(actualCompletionJournals=1,completionCheckpointReferences=2,independentCompletionBytesVerified=True)
    from recovery_stream_completion_history import read_history,same_receipt
    history=read_history(storage,manifest,doc,item['bucket'])
    expected=json.loads(pg.sql('SELECT json_agg(to_jsonb(c) ORDER BY task_id,serial) FROM edgeai.stream_checkpoint c',database))
    assert len(history['receipts'])==len(history['objects'])==len(history['payloads'])==len(expected)==5
    assert all(same_receipt(a,b) for a,b in zip(history['receipts'],expected))
    assert pg.sql('SELECT to_jsonb(p)::text FROM edgeai.stream_completion_publication p',database)==before
    passed('independent receipt journals recover all five original checkpoint identities, times and ancestry after source removal')
    ancestor=next(c for c in history['receipts'] if c['id'] not in {t['id'] for t in doc['checkpoints']})
    missing_key='authority/stream-checkpoint/'+ancestor['id']+'.json'
    missing=json.loads(json.dumps(manifest));missing['versions']=[v for v in missing['versions'] if v['key']!=missing_key]
    try:read_history(storage,missing,doc,item['bucket'])
    except Blocked as error:assert 'receipt is absent or ambiguous' in str(error)
    else:raise AssertionError('Missing original checkpoint receipt was inferred from payload bytes')
    assert pg.sql('SELECT to_jsonb(p)::text FROM edgeai.stream_completion_publication p',database)==before
    passed('an absent original receipt blocks ancestry recovery even when every checkpoint payload exists')
    changed=json.loads(json.dumps(doc));changed['checkpoints'][0]['created_at']='2000-01-01T00:00:00Z'
    try:read_history(storage,manifest,changed,item['bucket'])
    except Blocked as error:assert 'conflicts with the completion grant' in str(error)
    else:raise AssertionError('Altered original checkpoint time was accepted')
    passed('a changed terminal receipt time cannot replace the original independently preserved timestamp')
    report.update(actualCheckpointAuthorityJournals=5,originalCheckpointAncestryVerified=True)
