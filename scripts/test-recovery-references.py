"""Real restored PostgreSQL and independent TLS MinIO; runtime/broker receipts are explicit SQL fixtures."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import runpy
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
import traceback
import urllib.request
import uuid

from postgres_backup import Postgres, ROOT, identifier, literal, private_file

sys.path.insert(0,str(ROOT/'runner'))
from edgeai_runner.stream_checkpoint import capture, confirm
from edgeai_runner.stream_journal import Journal, Limits
from edgeai_runner.stream_protocol import Binding, Producer, Frame

Api = runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport',choices=['native','compose'],default='native')
    parser.add_argument('--minio-binary',type=Path,default=ROOT/'.tools/minio')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-references-test.json')
    args = parser.parse_args()
    identity = uuid.uuid4().hex
    work = ROOT/'.tools'/('recovery-references-test-'+identity); work.mkdir(mode=0o700)
    env = {key:value for key,value in os.environ.items() if not key.startswith('MC_')}
    env.update(MC_CONFIG_DIR=str(work/'mc'),MC_NO_COLOR='1',MC_DISABLE_PAGER='1',EDGEAI_BACKUP_CA_FILE=str(work/'ca.crt'))
    processes, apis, owned = [], [], {}
    pg = Postgres(args.transport,diagnostics=work/'database')
    source, target = 'edgeai_backup_refs_'+identity, 'edgeai_restore_refs_'+identity
    bucket = 'edgeai-recovery-refs'
    report = {'scope':'restored-database-storage-references','sourceMode':'SYNTHETIC',
        'runtimeBoundary':'SQL_FIXTURE_WITH_ACTIVE_CONSTRAINTS','status':'RUNNING','cases':[],
        'ownedProcessesStopped':False,'ownedDatabasesRemoved':False}

    def run(command,body=None,expected=0,environment=None):
        result = subprocess.run(list(map(str,command)),input=body,capture_output=True,
            env=environment or env,timeout=360)
        with private_file(work/('command-'+uuid.uuid4().hex+'.log')) as log:
            log.write(result.stdout); log.write(result.stderr)
        assert result.returncode == expected,'Unexpected command exit; private diagnostics retained'
        return result

    def mc(arguments,body=None):
        return run([ROOT/'.tools/mc','--json',*arguments],body)

    def start_storage(name):
        certs = work/(name+'-certs'); certs.mkdir(mode=0o700)
        ca_dir = certs/'CAs'; ca_dir.mkdir(mode=0o700); shutil.copyfile(work/'ca.crt',ca_dir/'root.crt')
        extensions = certs/'extensions'
        extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n')
        run(['openssl','req','-new','-nodes','-newkey','rsa:2048','-subj','/CN=localhost',
             '-keyout',certs/'private.key','-out',certs/'leaf.csr'])
        (certs/'private.key').chmod(0o600)
        run(['openssl','x509','-req','-in',certs/'leaf.csr','-CA',work/'ca.crt','-CAkey',work/'ca.key',
             '-set_serial',str(secrets.randbits(127)+1),'-days','1','-extfile',extensions,'-out',certs/'public.crt'])
        user, password = 'recovery-probe',secrets.token_hex(24)
        with socket.socket() as api, socket.socket() as console:
            api.bind(('127.0.0.1',0)); console.bind(('127.0.0.1',0))
            port, console_port = api.getsockname()[1],console.getsockname()[1]
        origin = 'https://localhost:'+str(port)
        env['MC_HOST_'+name] = 'https://'+user+':'+password+'@localhost:'+str(port)
        prefix = 'EDGEAI_BACKUP_SOURCE_' if name == 'origin' else 'EDGEAI_BACKUP_STORAGE_'
        env.update({prefix+'URL':origin,prefix+'USER':user,prefix+'PASSWORD':password})
        with private_file(work/(name+'.log')) as log:
            process = subprocess.Popen([str(args.minio_binary.resolve()),'server',str(work/(name+'-data')),
                '--address','127.0.0.1:'+str(port),'--console-address','127.0.0.1:'+str(console_port),
                '--certs-dir',str(certs),'--quiet'],stdout=log,stderr=log,
                env={**os.environ,'MINIO_ROOT_USER':user,'MINIO_ROOT_PASSWORD':password})
        processes.append(process)
        tls = ssl.create_default_context(cafile=str(work/'ca.crt')); deadline = time.monotonic()+30
        while time.monotonic() < deadline:
            assert process.poll() is None,'Owned MinIO exited'
            try:
                with urllib.request.urlopen(origin+'/minio/health/live',context=tls,timeout=1) as response:
                    if response.status == 200: return process
            except OSError: time.sleep(.1)
        raise AssertionError('Owned MinIO readiness timed out')

    def remember(database):
        owned[database] = pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(database),'postgres')
        assert owned[database]

    def drop(database):
        assert pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(database),'postgres') == owned[database]
        pg.sql('DROP DATABASE '+identifier(database),'postgres'); del owned[database]

    def upload(key,body):
        digest = hashlib.sha256(body).hexdigest()
        mc(['pipe','--attr','sha256='+digest,'origin/'+bucket+'/'+key],body)
        version = json.loads(mc(['stat','origin/'+bucket+'/'+key]).stdout)['versionID']
        return {'bucket':bucket,'key':key,'versionId':version,'bytes':len(body),'sha256':digest}

    def insert(table,values):
        def value(item):
            if item is None: return 'NULL'
            if type(item) is int: return str(item)
            return literal(item)
        pg.sql('INSERT INTO edgeai.'+table+' ('+','.join(values)+') VALUES ('+
            ','.join(value(item) for item in values.values())+')',source)

    def private_json(path,value):
        with private_file(path,'w') as output: json.dump(value,output)

    def passed(name):
        report['cases'].append(name); print('PASS: '+name,flush=True)

    code = 1
    try:
        pg.check_versions('postgres')
        run(['openssl','req','-x509','-nodes','-newkey','rsa:2048','-days','1','-subj','/CN=recovery-reference-test',
            '-addext','basicConstraints=critical,CA:TRUE','-keyout',work/'ca.key','-out',work/'ca.crt'])
        (work/'ca.key').chmod(0o600)
        ca_dir = work/'mc/certs/CAs'; ca_dir.mkdir(mode=0o700,parents=True); shutil.copyfile(work/'ca.crt',ca_dir/'root.crt')
        original = start_storage('origin'); start_storage('replica')
        mc(['mb','origin/'+bucket]); mc(['version','enable','origin/'+bucket])
        pg.sql('CREATE DATABASE '+identifier(source),'postgres'); remember(source)
        api = Api(source,work); apis.append(api)
        profile = api.request('POST','profiles/SERVICE',{'key':'recovery-'+identity,'version':'1.0.0',
            'spec':json.loads((ROOT/'contracts/profiles/service-execution.example.json').read_text())},201)
        device_profile = api.request('POST','profiles/DEVICE',{'key':'recovery-device-'+identity,'version':'1.0.0','spec':{'protocol':'synthetic'}},201)
        device = api.request('POST','devices',{'key':'recovery-device-'+identity,'displayName':'Recovery fixture',
            'profileVersionId':device_profile['id'],'sourceMode':'SYNTHETIC'},201)
        session = api.request('POST','devices/'+device['id']+'/sessions',{'bootId':str(uuid.uuid4())},201)
        workflow = api.request('POST','workflows',{'key':'recovery-'+identity,'displayName':'Recovery fixture'},201)
        version = api.request('POST','workflows/'+workflow['id']+'/versions',{'version':'1.0.0',
            'tasks':[{'key':name,'serviceProfileVersionId':profile['id'],'parameters':{}} for name in ['result','stream']],
            'dependencies':[]},201)
        workflow_run = api.request('POST','workflow-runs',{'workflowVersionId':version['id'],
            'execution':{'mode':'AUTO'},'parameters':{}},201,str(uuid.uuid4()))
        run_id = workflow_run['id']; api.close()
        tasks = json.loads(pg.sql("SELECT json_object_agg(d.task_key,json_build_object('task',t.id,'attempt',a.id)) "
            'FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id JOIN edgeai.task_attempt a ON a.task_id=t.id',source))
        # Public registry/run API above is real; Pod claims and broker activation below are explicit fixtures.
        pg.sql("UPDATE edgeai.workflow_run SET state='RUNNING'; UPDATE edgeai.task SET state='RUNNING'; "
               "UPDATE edgeai.task_attempt SET state='RUNNING'",source)
        for task in tasks.values():
            task.update(runtime=str(uuid.uuid4()),pod=str(uuid.uuid4()))
            insert('runtime_instance',{'id':task['runtime'],'attempt_id':task['attempt'],'task_id':task['task'],
                'run_id':run_id,'epoch':1,'namespace':'recovery-fixture','job_name':'edgeai-'+task['attempt'],
                'claim_nonce':str(uuid.uuid4()),'desired_state':'RUNNING','observed_state':'RUNNING',
                'job_uid':str(uuid.uuid4()),'producer_pod_uid':task['pod'],'node_uid':str(uuid.uuid4()),
                'node_name':'fixture-node','expires_at':'2099-01-01T00:00:00Z','created_at':'2026-01-01T00:00:00Z','updated_at':'2026-01-01T00:00:00Z'})
        task = tasks['result']; result_id = str(uuid.uuid4())
        insert('task_result',{'id':result_id,'task_id':task['task'],'attempt_id':task['attempt'],'runtime_id':task['runtime'],
            'epoch':1,'producer_pod_uid':task['pod'],'manifest_digest':'sha256:'+'a'*64,'created_at':'2026-01-01T00:00:00Z'})
        artifacts = [upload('result/한글.bin',b'previous immutable result'),upload('result/empty.bin',b'')]
        upload('result/한글.bin',b'new latest version must not replace the referenced older version')
        for index,item in enumerate(artifacts):
            insert('result_artifact',{'id':str(uuid.uuid4()),'result_id':result_id,'port':'output'+str(index),
                'bucket':bucket,'object_key':item['key'],'object_version':item['versionId'],
                'sha256':item['sha256'],'bytes':item['bytes'],'media_type':'application/octet-stream'})
        pg.sql('UPDATE edgeai.task_result SET committed=true',source)
        task = tasks['stream']; route_id, generation_id = str(uuid.uuid4()),str(uuid.uuid4())
        now = pg.sql('SELECT now()::text',source)
        insert('data_route',{'id':route_id,'run_id':run_id,'source_device_id':device['id'],
            'source_profile_version_id':device_profile['id'],'source_mode':'SYNTHETIC','source_port':'samples',
            'consumer_task_id':task['task'],'consumer_port':'input','media_type':'application/json','max_payload_bytes':4096,'created_at':now})
        insert('route_generation',{'id':generation_id,'route_id':route_id,'run_id':run_id,'generation':1,
            'source_device_id':device['id'],'consumer_task_id':task['task'],'producer_session_id':session['id'],
            'producer_epoch':session['epoch'],'consumer_attempt_id':task['attempt'],'consumer_epoch':1,
            'broker_digest':'sha256:'+'b'*64,'policy_digest':'sha256:'+'c'*64,'request_digest':'sha256:'+'d'*64,
            'created_at':now,'updated_at':now,'lease_until':pg.sql("SELECT ("+literal(now)+"::timestamptz+interval '120 seconds')::text",source)})
        pg.sql('UPDATE edgeai.route_generation SET activated_at=now(),updated_at=now()',source)
        binding = Binding(route_id,1,Producer.parse({'kind':'DEVICE_SESSION','deviceId':device['id'],
            'sessionId':session['id'],'epoch':session['epoch']}))
        checkpoints, previous = [], None
        with Journal(work/'journal',[binding],[],Limits(max_frames=8),create=True,durability='EXTERNAL') as journal:
            for sequence in [1,2]:
                state = str(sequence*9).encode()
                journal.receive(Frame(binding,sequence,'DATA',state,'application/json'))
                journal.commit(sequence-1,journal.pending(),state)
                snapshot = capture(journal,'a'*64); doc = snapshot.document()
                item = upload('tasks/'+task['task']+'/attempts/'+task['attempt']+'/stream-checkpoint/'+snapshot.sha256,snapshot.wire)
                checkpoint_id = str(uuid.uuid4())
                summary = {'manifest':doc['manifest'],'revision':doc['revision'],
                    'routes':[{key:value for key,value in row.items() if key != 'frames'} for row in doc['routes']],
                    'stateSha256':hashlib.sha256(state).hexdigest(),'stateBytes':len(state)}
                insert('stream_checkpoint',{'id':checkpoint_id,'run_id':run_id,'task_id':task['task'],
                    'attempt_id':task['attempt'],'runtime_id':task['runtime'],'epoch':1,'producer_pod_uid':task['pod'],
                    'service_profile_version_id':profile['id'],'previous_id':previous,'serial':doc['serial'],
                    'state_revision':doc['revision'],'sha256':snapshot.sha256,'execution_sha256':'a'*64,'bytes':len(snapshot.wire),
                    'generation_ids':'{'+generation_id+'}','summary_json':json.dumps(summary),'bucket':bucket,
                    'object_key':item['key'],'object_version':item['versionId'],'created_at':pg.sql('SELECT now()::text',source)})
                previous = checkpoint_id; checkpoints.append(item); confirm(journal,snapshot.serial,snapshot.sha256)
        db_bundle, storage_bundle = work/'database-snapshot',work/'storage-snapshot'
        run([sys.executable,'scripts/postgres_backup.py','backup','--transport',args.transport,'--database',source,'--output',db_bundle])
        run([sys.executable,'scripts/storage_backup.py','backup','--bucket',bucket,'--output',storage_bundle])
        restored = run([sys.executable,'scripts/postgres_backup.py','restore','--transport',args.transport,
            '--input',db_bundle,'--target-database',target]); remember(target)
        restore_path = Path(restored.stdout.decode().split('Private diagnostics: ')[1].strip())/'restore-report.json'
        restore_report = json.loads(restore_path.read_text()); manifest = json.loads((storage_bundle/'manifest.json').read_text())
        original.terminate(); original.wait(15); drop(source)
        # The verifier has neither source DB nor source storage credentials available.
        independent_env = {key:value for key,value in env.items() if not key.startswith('EDGEAI_BACKUP_SOURCE_') and key != 'MC_HOST_origin'}
        fingerprint_sql = "SELECT json_build_object('artifacts',(SELECT json_agg(to_jsonb(a) ORDER BY id) FROM edgeai.result_artifact a),"\
            "'checkpoints',(SELECT json_agg(to_jsonb(c) ORDER BY id) FROM edgeai.stream_checkpoint c))"
        before = pg.sql(fingerprint_sql,target)

        def gate(expected=0,bundle=storage_bundle,receipt=restore_path,database=target):
            output = work/('verification-'+uuid.uuid4().hex)
            run([sys.executable,'scripts/recovery_references.py','--transport',args.transport,'--database',database,
                '--restore-report',receipt,'--storage-input',bundle,'--output',output],expected=expected,environment=independent_env)
            if expected:
                assert not (output/'verification-report.json').exists()
                return json.loads((output/'failure.json').read_text())
            return json.loads((output/'verification-report.json').read_text())

        verified = gate()
        assert verified['referenceCounts'] == {'result_artifact':2,'stream_checkpoint':2}
        assert verified['verifiedVersionCount'] == 4 and len(manifest['versions']) == 5 and verified['activated'] is False
        assert pg.sql(fingerprint_sql,target) == before
        report.update(referenceCounts=verified['referenceCounts'],verifiedVersionCount=4,storageVersionCount=5,
            verifiedBytes=verified['verifiedBytes'],tls=True,sourceDatabaseRemoved=True,sourceStorageStopped=True,
            serverMajor=int(pg.sql('SHOW server_version_num',target))//10000,
            jarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest(),
            minioBinarySha256=hashlib.sha256(args.minio_binary.read_bytes()).hexdigest())
        passed('real pg_restore and TLS backup verify every artifact and historical checkpoint after both sources are unavailable')

        existing = work/'existing-output'; existing.mkdir(mode=0o700)
        private_json(existing/'verification-report.json',verified)
        before_output = (existing/'verification-report.json').read_bytes()
        run([sys.executable,'scripts/recovery_references.py','--transport',args.transport,'--database',target,
            '--restore-report',restore_path,'--storage-input',storage_bundle,'--output',existing],expected=1,environment=independent_env)
        assert sorted(path.name for path in existing.iterdir()) == ['verification-report.json']
        assert (existing/'verification-report.json').read_bytes() == before_output
        passed('an existing verification directory and its accepted report remain untouched')

        def altered_manifest(value):
            directory = work/('manifest-'+uuid.uuid4().hex); directory.mkdir(mode=0o700)
            private_json(directory/'manifest.json',value); return directory

        omitted = copy.deepcopy(manifest)
        omitted['versions'] = [item for item in omitted['versions'] if item['versionId'] != checkpoints[0]['versionId']]
        assert 'stream_checkpoint' in gate(1,bundle=altered_manifest(omitted))['message']
        passed('an omitted historical checkpoint is rejected even when the latest checkpoint is present')
        omitted = copy.deepcopy(manifest)
        omitted['versions'] = [item for item in omitted['versions'] if item['versionId'] != artifacts[0]['versionId']]
        assert 'result_artifact' in gate(1,bundle=altered_manifest(omitted))['message']
        passed('a newer version of the same key cannot replace the exact restored result version')
        for field,replacement in [('sha256','0'*64),('bytes',artifacts[0]['bytes']+1)]:
            changed = copy.deepcopy(manifest)
            next(item for item in changed['versions'] if item['versionId'] == artifacts[0]['versionId'])[field] = replacement
            assert 'content proof differs' in gate(1,bundle=altered_manifest(changed))['message']
        passed('database and manifest checksum or length disagreement is rejected before acceptance')
        wrong = copy.deepcopy(restore_report); wrong['databaseOid'] = '0'
        receipt = work/'wrong-restore.json'; private_json(receipt,wrong)
        assert 'identity' in gate(1,receipt=receipt)['message']
        wrong = copy.deepcopy(restore_report); wrong['restoreIdentity'] = uuid.uuid4().hex
        receipt = work/'wrong-marker.json'; private_json(receipt,wrong)
        assert 'identity' in gate(1,receipt=receipt)['message']
        passed('a restore receipt for a different database identity cannot authorize verification')
        wrong_manifest = copy.deepcopy(manifest); wrong_manifest['targetDeploymentId'] = str(uuid.uuid4())
        assert 'deployment identity' in gate(1,bundle=altered_manifest(wrong_manifest))['message']
        passed('a storage manifest for another MinIO installation is rejected')
        pg.sql("INSERT INTO edgeai.flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) "
            "VALUES (999,'999','future reference fixture','SQL','V999__fixture.sql',current_user,0,true)",target)
        assert gate(2)['failureType'] == 'Blocked'
        pg.sql('DELETE FROM edgeai.flyway_schema_history WHERE installed_rank=999',target)
        passed('unknown schema versions block acceptance instead of silently ignoring future reference tables')
        gate()
        mc(['rm','--force','--version-id',checkpoints[0]['versionId'],'replica/'+bucket+'/'+checkpoints[0]['key']])
        assert gate(1)['failureType'] == 'RuntimeError'
        assert pg.sql(fingerprint_sql,target) == before
        passed('a missing physical historical version fails even when manifest and database agree; restored references remain unchanged')
        report['status'],code = 'PASS',0
    except Exception as error:
        report.update(status='FAIL',failureType=type(error).__name__)
        with private_file(work/'failure.log','w') as log: traceback.print_exc(file=log)
        print('FAIL: recovery reference test; private diagnostics: '+str(work),flush=True)
    finally:
        for api in apis: api.close()
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(15)
                except subprocess.TimeoutExpired: process.kill(); process.wait(5)
        for database in list(owned):
            try: drop(database)
            except Exception:
                report.update(status='FAIL',cleanupFailed=True); code = 1
        report['ownedProcessesStopped'] = all(process.poll() is not None for process in processes) and all(api.process.poll() is not None for api in apis)
        report['ownedDatabasesRemoved'] = not owned
        args.report.parent.mkdir(parents=True,exist_ok=True); args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(report['status']+': '+str(len(report['cases']))+' restored reference cases; private data retained',flush=True)
    return code


if __name__ == '__main__': raise SystemExit(main())
