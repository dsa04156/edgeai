"""Actual restored PostgreSQL/TLS broker STREAM retirement and transaction failure boundaries."""
import json
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace
from unittest.mock import patch
import uuid

from postgres_backup import Blocked, identifier, literal, private_file
import recovery_stream_retire as retirement
import recovery_mqtt_fence as mqtt


def exercise(fixtures,pg,restored,fingerprint,passed,transport):
    database,receipt=restored
    args=SimpleNamespace(database=database,restore_report=receipt,broker_digest=fixtures.digest,
        mqtt_state_directory=fixtures.cfg.state_directory,mqtt_ca_file=fixtures.cfg.ca_file,
        mqtt_original_password_file=fixtures.cfg.admin_password_file,recovery_id=fixtures.cfg.recovery_id,timeout=30)
    work=fixtures.directory/'stream-retirement';work.mkdir(mode=0o700)
    original=fingerprint(database)
    expected_other={k:v for k,v in original.items() if k!='route_generation'}
    def admin(command,**values):
        with mqtt.Connection(fixtures.cfg,fixtures.replacement) as connection:return connection.call(command,**values)
    def refused(operation,kind=Blocked):
        try:operation()
        except kind:return
        raise AssertionError('Unverified STREAM retirement was accepted')
    def output():
        value=SimpleNamespace(**vars(args),output=work/uuid.uuid4().hex);value.output.mkdir(mode=0o700);return value
    def apply(plan=None):
        return retirement.apply(pg,output(),plan or retirement.prepare(pg,args))
    def cli():
        out=work/uuid.uuid4().hex
        command=[sys.executable,'scripts/recovery_stream_retire.py','--transport',transport,'--output',str(out)]
        for key,value in vars(args).items():command+=['--'+key.replace('_','-'),str(value)]
        fixtures.run(command)
        return json.loads((out/'retirement.json').read_text())
    def unchanged():assert fingerprint(database)==original
    def rename_device(value):pg.sql('UPDATE edgeai.device SET display_name='+literal(value),database)
    def generation(number,digest=None):
        nonlocal expected_other
        identity=str(uuid.uuid4())
        columns=('id,route_id,run_id,generation,source_task_id,source_device_id,consumer_task_id,'
            'producer_attempt_id,producer_session_id,producer_epoch,consumer_attempt_id,consumer_epoch,'
            'broker_digest,policy_digest,request_digest,created_at,updated_at,lease_until')
        pg.sql('INSERT INTO edgeai.route_generation('+columns+') SELECT '+literal(identity)+'::uuid,route_id,run_id,'+
            str(number)+',source_task_id,source_device_id,consumer_task_id,producer_attempt_id,producer_session_id,producer_epoch,'
            'consumer_attempt_id,consumer_epoch,'+literal(digest or fixtures.digest)+',policy_digest,request_digest,now(),now(),'
            "now()+interval '120 seconds' FROM edgeai.route_generation WHERE id="+literal(first)+'::uuid',database)
        # Creating a test generation also creates its immutable heartbeat row.
        expected_other={k:v for k,v in fingerprint(database).items() if k!='route_generation'}
        return identity

    admin('enableClient',username=fixtures.device_name)
    refused(lambda:retirement.prepare(pg,args));unchanged()
    admin('disableClient',username=fixtures.device_name)
    passed('stream-retirement-refuses-a-live-original-principal-before-any-restored-database-write')
    variant=SimpleNamespace(**vars(args));variant.recovery_id=str(uuid.uuid4())
    refused(lambda:retirement.prepare(pg,variant),ValueError);unchanged()
    passed('stream-retirement-requires-the-same-broker-recovery-operation')
    marker=pg.sql("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname="+literal(database),'postgres')
    pg.sql('COMMENT ON DATABASE '+identifier(database)+" IS 'unrelated-restore'",'postgres')
    refused(lambda:retirement.prepare(pg,args),ValueError)
    pg.sql('COMMENT ON DATABASE '+identifier(database)+' IS '+literal(marker),'postgres');unchanged()
    passed('stream-retirement-refuses-an-actual-restored-database-marker-replacement')
    observe=retirement.observe_broker
    def changed_observation(*a,**kw):
        result=observe(*a,**kw);rename_device('Changed during broker observation');return result
    with patch.object(retirement,'observe_broker',side_effect=changed_observation):refused(lambda:retirement.prepare(pg,args))
    rename_device('Journal comparison');unchanged()
    passed('actual-database-write-during-broker-observation-invalidates-stream-retirement-plan')
    plan=retirement.prepare(pg,args);rename_device('Changed after plan')
    refused(lambda:apply(plan));rename_device('Journal comparison');unchanged()
    passed('actual-restored-database-change-after-plan-is-refused-before-stream-retirement')
    plan=retirement.prepare(pg,args);admin('enableClient',username=fixtures.device_name)
    refused(lambda:apply(plan));unchanged();admin('disableClient',username=fixtures.device_name)
    passed('actual-broker-reenable-after-plan-prevents-stream-database-retirement')
    call=pg.call
    def change_before_transaction(tool,arguments,*a,**kw):
        if '-f' in arguments:rename_device('Changed after final observation')
        return call(tool,arguments,*a,**kw)
    with patch.object(pg,'call',side_effect=change_before_transaction):refused(lambda:apply(),RuntimeError)
    rename_device('Journal comparison');unchanged()
    passed('actual-write-after-final-observation-is-rejected-by-the-locked-sql-guard')
    transaction=retirement.transaction_sql
    def rollback(plan):return transaction(plan).replace('SET CONSTRAINTS ALL IMMEDIATE;','SELECT 1/0; SET CONSTRAINTS ALL IMMEDIATE;')
    with patch.object(retirement,'transaction_sql',side_effect=rollback):refused(lambda:apply(),RuntimeError)
    unchanged()
    passed('actual-sql-failure-after-both-route-updates-rolls-back-fences-and-closures-together')
    locker=None;application='stream-retirement-lock-'+uuid.uuid4().hex
    try:
        with private_file(work/'locker.log') as log:
            locker=subprocess.Popen(pg.prefix+[pg.binaries['psql']]+pg.connection+['--dbname',database,'-X','-q','-c',
                'BEGIN; LOCK TABLE edgeai.route_generation IN SHARE ROW EXCLUSIVE MODE; SELECT pg_sleep(30); COMMIT;'],
                env={**pg.env,'PGAPPNAME':application},stdout=log,stderr=log)
        deadline=time.monotonic()+10
        while pg.sql("SELECT EXISTS(SELECT FROM pg_locks l JOIN pg_stat_activity a USING(pid) WHERE a.application_name="+
                literal(application)+" AND l.relation='edgeai.route_generation'::regclass AND l.granted)",database)!='t':
            assert time.monotonic()<deadline and locker.poll() is None;time.sleep(.05)
        started=time.monotonic();refused(lambda:apply(),RuntimeError);assert time.monotonic()-started<15;unchanged()
    finally:
        if locker is not None:
            pg.sql('SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE application_name='+literal(application),database)
            locker.wait(10)
    passed('actual-competing-route-lock-times-out-with-no-partial-retirement')
    first=pg.sql('SELECT id FROM edgeai.route_generation ORDER BY id LIMIT 1',database)
    pg.sql("UPDATE edgeai.route_generation SET fenced_at=now(),fence_reason='CANCELLED',updated_at=now() WHERE id="+literal(first)+'::uuid',database)
    fenced=json.loads(pg.sql('SELECT to_jsonb(g) FROM edgeai.route_generation g WHERE id='+literal(first)+'::uuid',database))
    result=cli();assert result['generationsFenced']==1 and result['generationsClosed']==2
    current=fingerprint(database);assert {k:v for k,v in current.items() if k!='route_generation'}=={k:v for k,v in original.items() if k!='route_generation'}
    closed=json.loads(pg.sql('SELECT to_jsonb(g) FROM edgeai.route_generation g WHERE id='+literal(first)+'::uuid',database))
    assert all(closed[k]==v for k,v in fenced.items() if k not in ('state','closed_at','updated_at'))
    assert all(result[k] is False for k in ('brokerModified','activated','globalQuiescenceProven','producerProcessQuiescenceProven'))
    passed('actual-stream-cli-closes-active-and-fenced-generations-preserving-cancellation-and-other-forty-two-tables')
    result=cli();assert result['generationsFenced']==result['generationsClosed']==0 and not result['databaseModified']
    assert fingerprint(database)==current
    passed('same-stream-retirement-is-a-zero-change-repeat-and-preserves-terminal-history')
    second=generation(2)
    def lost_response(tool,arguments,*a,**kw):
        result=call(tool,arguments,*a,**kw)
        if '-f' in arguments:raise subprocess.TimeoutExpired('psql',45)
        return result
    with patch.object(pg,'call',side_effect=lost_response):refused(lambda:apply(),subprocess.TimeoutExpired)
    assert pg.sql('SELECT state FROM edgeai.route_generation WHERE id='+literal(second)+'::uuid',database)=='CLOSED'
    result=cli();assert result['generationsFenced']==result['generationsClosed']==0
    passed('preparing-generation-is-retired-and-lost-real-commit-response-resumes-with-zero-duplicate-mutations')
    third=generation(3)
    def reenable_after_commit(tool,arguments,*a,**kw):
        result=call(tool,arguments,*a,**kw)
        if '-f' in arguments:admin('enableClient',username=fixtures.device_name)
        return result
    with patch.object(pg,'call',side_effect=reenable_after_commit):refused(lambda:apply())
    assert pg.sql('SELECT state FROM edgeai.route_generation WHERE id='+literal(third)+'::uuid',database)=='CLOSED'
    admin('disableClient',username=fixtures.device_name);result=cli();assert not result['databaseModified']
    passed('post-commit-broker-reenable-withholds-success-while-preserving-quarantine-and-allows-fenced-resume')
    other=generation(4,'sha256:'+'e'*64);other_before=fingerprint(database)
    result=cli();assert result['unresolvedGenerations']==[{'generationId':other,'brokerDigest':'sha256:'+'e'*64,'reason':'OTHER_BROKER_AUTHORITY_NOT_PROVEN'}]
    assert not result['databaseModified'] and fingerprint(database)==other_before
    passed('another-broker-open-generation-remains-unresolved-and-unchanged')
    final=fingerprint(database)
    assert len(final)==43 and {k:v for k,v in final.items() if k!='route_generation'}==expected_other
    return {'streamRetirementCases':14,'streamGenerationsClosed':4,'streamOtherBrokerGenerationsPreserved':1,
        'streamOtherTablesPreserved':42,'streamLockProcessStopped':locker.poll() is not None}
