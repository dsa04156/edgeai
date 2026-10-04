"""Real public STREAM metadata, restored PostgreSQL and encrypted SQLite Device snapshots.

Runtime claims, broker activation and checkpoint receipt insertion are explicit SQL fixtures.
No Kubernetes, broker or S3 authority is granted by this metadata-only test.
"""
import argparse
from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import runpy
import secrets
import shutil
import subprocess
import sys
import traceback
from types import SimpleNamespace
from unittest.mock import patch
import uuid

import recovery_device_journal as recovery
import device_journal_backup as journals
import private_material_backup as material
from postgres_backup import ROOT, Blocked, Postgres, backup, restore, identifier, literal, private_file
from edgeai_runner.stream_checkpoint import capture, confirm
from edgeai_runner.stream_journal import Journal, JournalError, Emission
from edgeai_runner.stream_protocol import Binding, Producer, Frame
from edgeai_runner import stream_source_completion as completion

Api = runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport',choices=['native','compose'],default='native')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-device-journal-test.json')
    parser.add_argument('--verify-authority',action='store_true')
    parser.add_argument('--retire-routes',action='store_true')
    parser.add_argument('--minio-binary',type=Path,default=ROOT/'.tools/minio')
    args = parser.parse_args(); identity = uuid.uuid4().hex
    if args.retire_routes and not args.verify_authority:parser.error('--retire-routes requires --verify-authority')
    work = ROOT/'.tools'/('recovery-device-journal-test-'+identity); work.mkdir(mode=0o700)
    pg = Postgres(args.transport,diagnostics=work/'postgres'); age = material.Age()
    owned, apis = {}, []; fixtures=None; source = 'edgeai_backup_journal_'+identity
    report = {'status':'RUNNING','scope':'restored-device-journal-database-comparison-tests',
        'sourceMode':'SYNTHETIC','runtimeBoundary':'SQL_FIXTURE_WITH_ACTIVE_CONSTRAINTS',
        'publicStreamRun':False,'cases':[],'activated':False,'ownedDatabasesRemoved':False,'ownedApisStopped':False}

    def passed(name): report['cases'].append(name); print('PASS: '+name,flush=True)
    def directory(name): path=work/name; path.mkdir(mode=0o700); return path
    def remember(db):
        owned[db] = pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(db),'postgres'); assert owned[db]
    def drop(db):
        assert owned[db] == pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(db),'postgres')
        pg.sql('DROP DATABASE '+identifier(db),'postgres'); del owned[db]
    def insert(table,values):
        def value(v): return 'NULL' if v is None else str(v) if type(v) is int else literal(v)
        pg.sql('INSERT INTO edgeai.'+table+' ('+','.join(values)+') VALUES ('+','.join(map(value,values.values()))+')',source)
    def fingerprint(db):
        tables=json.loads(pg.sql("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='edgeai'",db))
        sql='SELECT jsonb_build_object('+','.join(literal(t)+",(SELECT encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text,'' ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex') FROM edgeai."+identifier(t)+' t)' for t in tables)+')'
        return json.loads(pg.sql(sql,db))
    def restore_database(bundle,name):
        target='edgeai_restore_journal_'+name+'_'+identity
        tool=Postgres(args.transport,diagnostics=work/('restore-'+name)); restore(tool,bundle,target); remember(target)
        return target,tool.directory/'restore-report.json'
    def compare(restored,target,receipt,reasons=(),run=None,cli=False):
        before=fingerprint(target); files={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in restored.rglob('*') if p.is_file()}
        options=SimpleNamespace(database=target,restore_report=receipt,journal_restore=restored,run_id=run or run_id)
        if cli:
            output=work/('comparison-'+uuid.uuid4().hex)
            result=subprocess.run([sys.executable,'scripts/recovery_device_journal.py','--transport',args.transport,
                '--database',target,'--restore-report',str(receipt),'--journal-restore',str(restored),
                '--run-id',options.run_id,'--output',str(output)],capture_output=True,timeout=60)
            with private_file(work/('cli-'+uuid.uuid4().hex+'.log')) as log: log.write(result.stdout+result.stderr)
            assert result.returncode==(2 if reasons else 0),'Unexpected comparison exit; private diagnostics retained'
            result=json.loads((output/'comparison.json').read_text())
        else: result=recovery.compare(pg,options)
        assert {c['reason'] for c in result['conflicts']}==set(reasons)
        assert result['status']==('DEVICE_JOURNAL_CONFLICTS' if reasons else 'DEVICE_JOURNAL_METADATA_MATCHED')
        assert all(result[k] is False for k in ('databaseModified','journalModified','activated','checkpointObjectsVerified','producerQuiescenceProven'))
        assert fingerprint(target)==before
        assert files=={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in restored.rglob('*') if p.is_file()}
        return result
    def source_snapshot(name,generated=3,ack=(1,2),selected=None,end=False,intent=False,retain_source=False):
        selected=bindings if selected is None else selected; live=directory(name+'-live')
        with Journal(live/'journal',[],selected,create=True) as journal:
            for sequence in range(1,generated+1):
                terminal=end and sequence==generated
                journal.commit(sequence-1,[],str(sequence).encode(),[Emission(b.route_id,b'' if terminal else str(sequence).encode(),
                    None if terminal else 'application/json','END' if terminal else 'DATA') for b in selected])
            for b,n in zip(selected,ack):
                if n: journal.acknowledge(b,n)
            if intent:
                assignments={b.route_id:SimpleNamespace(generation_id=generations[b.route_id],binding=b) for b in selected}
                completion.write(live,run_id,actor,assignments,journal)
        bundle=directory(name+'-backup'); journals.backup(age,live,recipient,bundle)
        if not retain_source: shutil.rmtree(live)
        restored=directory(name+'-restored'); journals.restore(age,bundle,key,restored)
        return (restored,live) if retain_source else restored
    def blocked(operation, exception=Blocked):
        try: operation()
        except exception: return
        raise AssertionError('Changed or unbound recovery was accepted')
    code=1
    try:
        pg.check_versions('postgres')
        if args.verify_authority:
            from recovery_device_test_support import Fixtures
            fixtures=Fixtures(directory('authority'),args.minio_binary,'sha256:'+'b'*64);fixtures.start()
            report['scope']='device-recovery-data-authority-prerequisites-tests'
        key=work/'identity'
        with private_file(key) as output:
            result=subprocess.run([str(age.paths['age-keygen'])],stdout=output,stderr=subprocess.PIPE,timeout=30)
        assert result.returncode==0
        recipient=subprocess.check_output([str(age.paths['age-keygen']),'-y',str(key)],stderr=subprocess.PIPE).decode().strip()
        signing=work/'signing-key'
        with private_file(signing,'w') as output: output.write(secrets.token_hex(32))
        pg.sql('CREATE DATABASE '+identifier(source),'postgres'); remember(source)
        # Disable runtime workers and defer the next stream scan beyond the lifetime of this API.
        # Empty initial scans run before readiness. Broker/Kubernetes endpoints are loopback only.
        env={k:'true' for k in ('EDGEAI_RUNTIME_ENABLED','EDGEAI_STREAM_ENABLED','EDGEAI_STREAM_BINDINGS_ENABLED','EDGEAI_STREAM_RUNS_ENABLED')}
        env.update(EDGEAI_RUNTIME_WORKER_ENABLED='false',EDGEAI_STREAM_RECONCILE_MS='3600000',
            EDGEAI_STREAM_BROKER_URL='tcp://127.0.0.1:1',EDGEAI_STREAM_BROKER_DIGEST='sha256:'+'b'*64,
            EDGEAI_STREAM_LEASE_SECONDS='120',EDGEAI_STREAM_PRINCIPAL_KEY_FILE=str(signing),
            EDGEAI_STREAM_DEVICE_KEY_FILE=str(signing),EDGEAI_RUNNER_KEY_FILE=str(signing),
            EDGEAI_STREAM_ADMIN_PASSWORD_FILE=str(signing),EDGEAI_STREAM_CA_FILE='',
            EDGEAI_KUBE_API_URL='http://127.0.0.1:1',EDGEAI_KUBE_CA_FILE='',EDGEAI_KUBE_TOKEN_FILE='',
            EDGEAI_STORAGE_URL='http://127.0.0.1:1',EDGEAI_STORAGE_RUNNER_URL='http://127.0.0.1:1')
        api=Api(source,work,extra_env=env); apis.append(api)
        spec=json.loads((ROOT/'contracts/profiles/service-stream.example.json').read_text())
        spec['stream']['inputs']={p:{'mediaType':'application/json','maxPayloadBytes':4096} for p in ('a','b')}
        spec['stream']['outputs']={}
        profile=api.request('POST','profiles/SERVICE',{'key':'journal-'+identity,'version':'1.0.0','spec':spec},201)
        dp=api.request('POST','profiles/DEVICE',{'key':'journal-device-'+identity,'version':'1.0.0','spec':{'protocol':'mqtt'}},201)
        device=api.request('POST','devices',{'key':'journal-device-'+identity,'displayName':'Journal comparison',
            'profileVersionId':dp['id'],'sourceMode':'SYNTHETIC'},201)
        session=api.request('POST','devices/'+device['id']+'/sessions',{'bootId':str(uuid.uuid4())},201)
        workflow=api.request('POST','workflows',{'key':'journal-'+identity,'displayName':'Journal comparison'},201)
        version=api.request('POST','workflows/'+workflow['id']+'/versions',{'version':'1.0.0',
            'tasks':[{'key':'consumer','serviceProfileVersionId':profile['id'],'parameters':{}}],'dependencies':[]},201)
        run=api.request('POST','workflow-runs',{'workflowVersionId':version['id'],'execution':{'mode':'AUTO'},'parameters':{},
            'streamInputs':[{'deviceId':device['id'],'sourcePort':'samples','toTask':'consumer','toPort':p,'maxPayloadBytes':4096} for p in ('a','b')]},201,str(uuid.uuid4()))
        run_id=run['id']; api.close()
        assert pg.sql('SELECT count(*) FROM edgeai.route_generation',source)=='0'
        assert pg.sql('SELECT count(*) FROM edgeai.stream_device_binding',source)=='2'
        report['publicStreamRun']=True
        task=json.loads(pg.sql("SELECT jsonb_build_object('task',t.id,'attempt',a.id,'runtime',r.id) FROM edgeai.task t JOIN edgeai.task_attempt a ON a.task_id=t.id JOIN edgeai.runtime_instance r ON r.attempt_id=a.id",source))
        task['pod']=str(uuid.uuid4())
        pg.sql("UPDATE edgeai.workflow_run SET state='RUNNING'; UPDATE edgeai.task SET state='RUNNING'; UPDATE edgeai.task_attempt SET state='RUNNING'; "
            "UPDATE edgeai.runtime_instance SET observed_state='RUNNING',job_uid="+literal(str(uuid.uuid4()))+",producer_pod_uid="+literal(task['pod'])+
            ",node_uid="+literal(str(uuid.uuid4()))+",node_name='fixture-node',expires_at=now()+interval '1 hour'",source)
        route_ids=json.loads(pg.sql('SELECT json_agg(id ORDER BY consumer_port) FROM edgeai.data_route',source)); assert len(route_ids)==2
        actor=Producer('DEVICE_SESSION',session['id'],session['epoch'],device['id'])
        bindings=[Binding(r,1,actor) for r in route_ids]; generations={}
        for b in bindings:
            gid=str(uuid.uuid4()); generations[b.route_id]=gid; now=pg.sql('SELECT now()::text',source)
            insert('route_generation',{'id':gid,'route_id':b.route_id,'run_id':run_id,'generation':1,'source_device_id':device['id'],
                'consumer_task_id':task['task'],'producer_session_id':actor.id,'producer_epoch':actor.epoch,'consumer_attempt_id':task['attempt'],
                'consumer_epoch':1,'broker_digest':'sha256:'+'b'*64,'policy_digest':'sha256:'+'c'*64,'request_digest':'sha256:'+'d'*64,
                'created_at':now,'updated_at':now,'lease_until':pg.sql('SELECT ('+literal(now)+"::timestamptz+interval '120 seconds')::text",source)})
        pg.sql('UPDATE edgeai.route_generation SET activated_at=now(),updated_at=now()',source)
        early=work/'db-early'; backup(pg,source,early)
        with Journal(work/'consumer',bindings,[],create=True,durability='EXTERNAL') as journal:
            previous=None
            for sequence in (1,2,3,4):
                for b in bindings: journal.receive(Frame(b,sequence,'END' if sequence==4 else 'DATA',b'' if sequence==4 else str(sequence).encode(),None if sequence==4 else 'application/json'))
                state=str(sequence).encode(); journal.commit(sequence-1,journal.pending(),state)
                snapshot=capture(journal,'a'*64); doc=snapshot.document(); cid=str(uuid.uuid4())
                object_key='tasks/'+task['task']+'/attempts/'+task['attempt']+'/stream-checkpoint/'+snapshot.sha256
                object_version=fixtures.upload(object_key,snapshot.wire) if fixtures else str(uuid.uuid4())
                insert('stream_checkpoint',{'id':cid,'run_id':run_id,'task_id':task['task'],'attempt_id':task['attempt'],
                    'runtime_id':task['runtime'],'epoch':1,'producer_pod_uid':task['pod'],'service_profile_version_id':profile['id'],
                    'previous_id':previous,'serial':doc['serial'],'state_revision':doc['revision'],'sha256':snapshot.sha256,
                    'execution_sha256':'a'*64,'bytes':len(snapshot.wire),'generation_ids':'{'+','.join(generations.values())+'}',
                    'summary_json':json.dumps({'manifest':doc['manifest'],'revision':doc['revision'],
                        'routes':[{k:v for k,v in r.items() if k!='frames'} for r in doc['routes']],
                        'stateSha256':'0'*64 if fixtures and sequence==3 else hashlib.sha256(state).hexdigest(),'stateBytes':len(state)}),
                    'bucket':'fixture-checkpoints','object_key':object_key,
                    'object_version':object_version,'created_at':pg.sql('SELECT now()::text',source)})
                previous=cid; confirm(journal,snapshot.serial,snapshot.sha256)
                if sequence==2: middle=work/'db-middle'; backup(pg,source,middle)
                if fixtures and sequence==3: mismatched=work/'db-mismatched'; backup(pg,source,mismatched)
        for gid in generations.values(): insert('stream_device_completion',{'generation_id':gid,'sequence':4,'created_at':pg.sql('SELECT now()::text',source)})
        terminal=work/'db-terminal'; backup(pg,source,terminal)
        if fixtures:fixtures.seal_storage()
        target,receipt=restore_database(middle,'mid'); empty,empty_receipt=restore_database(early,'early'); ended,ended_receipt=restore_database(terminal,'end')
        mismatched_restore=restore_database(mismatched,'mis') if fixtures else None
        route_restore=restore_database(middle,'routes') if args.retire_routes else None
        drop(source); report.update(sourceDatabaseRemoved=True,restoredDatabaseCount=(5 if args.retire_routes else 4) if fixtures else 3)
        report['tableCount']=len(fingerprint(target)); assert report['tableCount']>=44
        base=source_snapshot('base'); result=compare(base,target,receipt,cli=True)
        assert len(result['routes'])==2 and {r['sourceAcknowledged'] for r in result['routes']}=={1,2}
        assert all(r['replayFromSequence']==3 for r in result['routes'])
        passed('public-stream-pins-and-independent-fanout-acks-match-real-restored-database-after-source-deletion')
        compare(source_snapshot('gap',ack=(3,3)),target,receipt,{'ACKNOWLEDGED_SOURCE_FRAMES_MISSING_FROM_CONSUMER_RECOVERY'},cli=True)
        passed('source-acknowledged-and-deleted-frames-beyond-consumer-checkpoint-block-recovery')
        compare(source_snapshot('behind',generated=1,ack=(0,0)),target,receipt,{'CONSUMER_AHEAD_OF_DEVICE_SNAPSHOT'})
        passed('consumer-checkpoint-ahead-of-source-snapshot-blocks-sequence-reuse')
        compare(source_snapshot('unacked',ack=(0,0)),empty,empty_receipt)
        compare(base,empty,empty_receipt,{'ACKNOWLEDGED_SOURCE_FRAMES_MISSING_FROM_CONSUMER_RECOVERY'})
        passed('no-consumer-checkpoint-requires-every-source-frame-to-remain-replayable')
        compare(source_snapshot('subset',ack=(1,),selected=bindings[:1]),target,receipt,{'COMPLETE_DEVICE_ROUTE_SET_DIFFERS'})
        passed('partial-fanout-route-set-is-refused')
        other=replace(actor,id=str(uuid.uuid4()))
        compare(source_snapshot('session',selected=[replace(b,producer=other) for b in bindings]),target,receipt,
            {'DEVICE_SESSION_IDENTITY_MISMATCH','IMMUTABLE_DEVICE_PIN_MISMATCH','GENERATION_PRODUCER_MISMATCH','CONSUMER_CHECKPOINT_BINDING_MISMATCH'})
        passed('different-device-session-cannot-inherit-old-route-and-checkpoint-authority')
        compare(source_snapshot('generation',selected=[replace(b,generation=2) for b in bindings]),target,receipt,{'JOURNAL_GENERATION_MISSING_FROM_DATABASE'})
        passed('unrecorded-route-generation-is-refused')
        compare(base,target,receipt,{'RUN_OR_FROZEN_STREAM_MEMBERSHIP_MISSING','COMPLETE_DEVICE_ROUTE_SET_DIFFERS'},run=str(uuid.uuid4()))
        passed('unrelated-run-cannot-reuse-device-snapshot')
        done=source_snapshot('done',generated=4,ack=(4,4),end=True,intent=True); compare(done,ended,ended_receipt)
        compare(base,ended,ended_receipt,{'CONSUMER_AHEAD_OF_DEVICE_SNAPSHOT','END_POSITION_MISMATCH','RECORDED_DEVICE_COMPLETION_DIFFERS'})
        passed('terminal-end-and-completion-intent-match-the-exact-generation-and-reject-earlier-source')
        blocked(lambda:recovery.compare(pg,SimpleNamespace(database=target,restore_report=empty_receipt,journal_restore=base,run_id=run_id)),ValueError)
        passed('restore-receipt-must-match-exact-database-oid-and-marker')
        options=SimpleNamespace(database=target,restore_report=receipt,journal_restore=base,run_id=run_id)
        original=recovery.database_inventory; calls=0
        def racing(*a,**kw):
            nonlocal calls
            value=original(*a,**kw); calls+=1
            if calls==1: pg.sql("UPDATE edgeai.device SET display_name='Concurrent change'",target)
            return value
        with patch.object(recovery,'database_inventory',side_effect=racing): blocked(lambda:recovery.compare(pg,options))
        pg.sql("UPDATE edgeai.device SET display_name='Journal comparison'",target)
        passed('actual-database-write-between-read-only-observations-invalidates-comparison')
        marker=base/'source/journal/recovery.json'; prior=marker.read_bytes(); calls=0
        def marker_race(*a,**kw):
            nonlocal calls
            value=original(*a,**kw); calls+=1
            if calls==2:
                changed=json.loads(prior); changed['bundleId']=uuid.uuid4().hex
                temporary=base/'replacement.json'; material.write_json(temporary,changed); os.replace(temporary,marker)
            return value
        with patch.object(recovery,'database_inventory',side_effect=marker_race): blocked(lambda:recovery.compare(pg,options))
        with private_file(base/'original.json') as output: output.write(prior)
        os.replace(base/'original.json',marker)
        passed('actual-marker-replacement-during-comparison-is-refused')
        operation=str(uuid.uuid4())
        offload_sql='INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,idempotency_key,request_digest,namespace,' \
            'state,drain_deadline,start_timeout_seconds,remote_provider_key,remote_configuration_digest,remote_source_mode,created_at,updated_at) ' \
            'SELECT '+literal(operation)+'::uuid,task_id,run_id,attempt_id,gen_random_uuid(),'+literal('sha256:'+'1'*64)+ \
            ",namespace,'DRAINING',now()+interval '1 minute',60,'reference',"+literal('sha256:'+'2'*64)+",'SYNTHETIC',now(),now() FROM edgeai.runtime_instance"
        calls=0
        def transfer_race(*a,**kw):
            nonlocal calls
            value=original(*a,**kw); calls+=1
            if calls==1: pg.sql(offload_sql,target)
            return value
        with patch.object(recovery,'database_inventory',side_effect=transfer_race): blocked(lambda:recovery.compare(pg,options))
        for state in ('DRAINING','CANCELLING'):
            pg.sql('UPDATE edgeai.task_offload SET state='+literal(state),target)
            compare(base,target,receipt,{'ACTIVE_TRANSFER_REQUIRES_RECONCILIATION'})
        pg.sql('DELETE FROM edgeai.task_offload WHERE id='+literal(operation)+'::uuid',target)
        passed('transfer-inserted-between-observations-invalidates-guard-and-active-transfer-blocks-comparison')
        db_marker=pg.sql("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname="+literal(target),'postgres')
        pg.sql('COMMENT ON DATABASE '+identifier(target)+" IS 'unrelated-restore'",'postgres')
        blocked(lambda:recovery.compare(pg,options),ValueError)
        pg.sql('COMMENT ON DATABASE '+identifier(target)+' IS '+literal(db_marker),'postgres')
        passed('actual-database-marker-replacement-cannot-reuse-an-old-restore-receipt')
        if fixtures:
            fixtures.fence_device(actor,task['attempt'],bindings[0])
            retained_restore,retained_source=source_snapshot('retired-owner',retain_source=True)
            fixtures.exercise(pg,options,fingerprint,passed,args.transport,mismatched_restore,(retained_restore,retained_source))
            shutil.rmtree(retained_source)
            report.update(sourceStorageStopped=fixtures.origin.poll() is not None,brokerConnectionsRetired=2,
                fixedStorageVersions=4,verifiedCheckpointObjects=1,brokerAndStorageTls=True,
                sourceOwnerRetirementVerified=True,retiredSourceRemoved=not retained_source.exists(),
                minioBinarySha256=hashlib.sha256(args.minio_binary.read_bytes()).hexdigest())
            if args.retire_routes:
                from test_recovery_stream_retirement import exercise
                report.update(exercise(fixtures,pg,route_restore,fingerprint,passed,args.transport))
        pg.sql('UPDATE edgeai.device_session SET closed_at=now()',target)
        compare(base,target,receipt,{'DEVICE_SESSION_NO_LONGER_CURRENT'})
        passed('closed-session-remains-ineligible-even-when-data-cursors-match')
        for restored in (base,done):
            try: Journal(restored/'source/journal',[],bindings)
            except JournalError: pass
            else: raise AssertionError('Comparison activated a quarantined source')
        passed('successful-comparison-does-not-remove-quarantine-or-open-device-producer')
        report.update(status='PASS',jarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest(),
            databaseTablesPreserved=True,journalFilesPreserved=True,sourceVolumesRemoved=True,checkpointObjectsVerified=fixtures is not None,
            serverMajor=int(pg.sql('SHOW server_version_num',target))//10000)
        code=0
    except Exception as error:
        report.update(status='FAIL',failureType=type(error).__name__,failureFrames=[{'file':Path(f.filename).name,'line':f.lineno} for f in traceback.extract_tb(error.__traceback__)])
        print('FAIL: Device recovery comparison; '+type(error).__name__+'; private diagnostics retained',file=sys.stderr)
    finally:
        if fixtures:
            try:report['ownedAuthorityProcessesStopped']=fixtures.close()
            except Exception as error:report.update(status='FAIL',authorityCleanupFailureType=type(error).__name__);code=1
            report['ownedAuthorityClientsStopped']=all(peer.client._thread is None for peer in fixtures.peers)
        for api in apis: api.close()
        report['ownedApisStopped']=all(api.process.poll() is not None for api in apis)
        try:
            for db in list(owned): drop(db)
            report['ownedDatabasesRemoved']=not owned
        except Exception as error: report.update(status='FAIL',cleanupFailureType=type(error).__name__); code=1
        material.write_json(args.report,report)
    return code


if __name__=='__main__': raise SystemExit(main())
