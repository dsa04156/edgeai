"""Actual PostgreSQL checks for component-wide readiness inside Result recovery SQL.

These explicit frozen workflow fixtures test the shared transaction, separately
from the real journal/CLI acceptance that invokes it.
"""
import json
from pathlib import Path
import uuid

from postgres_backup import literal,private_file
from test_recovery_runtime_starts import q
from recovery_remote_results import transaction_sql


def check(pg,args,results,work,passed,report):
    db=args.database
    profile=pg.sql("SELECT id::text FROM edgeai.profile_version WHERE kind='SERVICE' ORDER BY id LIMIT 1",db)
    def fixture(mode):
        workflow,version,run=[str(uuid.uuid4()) for _ in range(3)]
        definitions={k:str(uuid.uuid4()) for k in ('parent','a','b')};tasks={k:str(uuid.uuid4()) for k in definitions}
        pg.sql('BEGIN; INSERT INTO edgeai.workflow(id,workflow_key,display_name,creation_digest) VALUES ('+
            q(workflow)+','+literal('readiness-'+workflow)+",'Readiness fixture','sha256:"+'e'*64+"'); "+
            'INSERT INTO edgeai.workflow_version(id,workflow_id,version,dag,digest) VALUES ('+q(version)+','+q(workflow)+
            ",'1.0.0','{}','sha256:"+'e'*64+"'); "+
            '; '.join('INSERT INTO edgeai.task_definition(id,workflow_version_id,task_key,service_profile_version_id,parameters) VALUES ('+
                q(definition)+','+q(version)+','+literal(key)+','+q(profile)+",'{}')" for key,definition in definitions.items())+'; '+
            ('INSERT INTO edgeai.task_dependency(workflow_version_id,from_task_id,to_task_id,from_port,to_port,mode) VALUES ('+
                q(version)+','+q(definitions['parent'])+','+q(definitions['b'])+",'output','input','BATCH'); " if mode in ('failed-parent','missing-result') else '')+
            'UPDATE edgeai.workflow_version SET published=true WHERE id='+q(version)+'; '+
            'INSERT INTO edgeai.workflow_run(id,workflow_version_id,idempotency_key,request_digest,mode,parameters,state,created_at,updated_at) VALUES ('+
            q(run)+','+q(version)+','+q(str(uuid.uuid4()))+",'sha256:"+'e'*64+"','AUTO','{}','RUNNING',now(),now()); "+
            '; '.join('INSERT INTO edgeai.task(id,run_id,workflow_version_id,definition_id,state,cancellation_reason,created_at,updated_at) VALUES ('+
                q(tid)+','+q(run)+','+q(version)+','+q(definitions[key])+','+
                literal(('FAILED' if mode=='failed-parent' else 'SUCCEEDED') if key=='parent' else 'READY' if key=='b' and mode=='partial' else 'WAITING')+','+
                ("'RUN_CANCELLED'" if key=='b' and mode=='cancelled' else 'NULL')+',now(),now())' for key,tid in tasks.items())+'; COMMIT',db)
        return run,tasks
    def execute(run,tasks):
        identity=json.loads(pg.sql("SELECT json_build_object('targetDatabase',current_database(),'databaseOid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()))",db))
        plan={**identity,'beforeGuard':json.loads(pg.sql(results.GUARD_QUERY,db)),
            'entries':[{'runId':run,'existing':True,'restoreBinding':False,'publicationPending':False}],
            'streamEvidence':{'readyGroups':[{'runId':run,'taskIds':[tasks['a'],tasks['b']]}]}}
        source=work/('component-sql-'+uuid.uuid4().hex+'.sql')
        with private_file(source,'w') as out:out.write(transaction_sql(plan,tables=results.TABLES,guard_query=results.GUARD_QUERY,kubernetes=True))
        with source.open('rb') as inp:
            return json.loads(pg.call('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-f','-'],db,source=inp,timeout=45,reject_stderr=True))
    run,tasks=fixture('ready');value=execute(run,tasks)
    assert value['childrenReadied']==2
    assert pg.sql('SELECT count(*) FROM edgeai.task_attempt WHERE task_id IN ('+q(tasks['a'])+','+q(tasks['b'])+") AND state='QUEUED' AND mode='AUTO'",db)=='2'
    assert pg.sql('SELECT count(*) FROM edgeai.runtime_instance WHERE run_id='+q(run),db)=='0'
    assert execute(run,tasks)['childrenReadied']==0
    passed('actual SQL releases both waiting component members together with queued Attempts and no runtimes')
    for mode in ('partial','failed-parent','missing-result'):
        run,tasks=fixture(mode);assert execute(run,tasks)['childrenReadied']==0
        assert pg.sql('SELECT count(*) FROM edgeai.task_attempt WHERE task_id IN ('+q(tasks['a'])+','+q(tasks['b'])+')',db)=='0'
    passed('a nonwaiting peer or any unsealed BATCH predecessor holds the entire downstream component')
    run,tasks=fixture('cancelled')
    try:execute(run,tasks)
    except RuntimeError:pass
    else:raise AssertionError('Contradictory waiting group member was released')
    assert pg.sql('SELECT count(*) FROM edgeai.task WHERE id IN ('+q(tasks['a'])+','+q(tasks['b'])+") AND state='WAITING'",db)=='2'
    assert pg.sql('SELECT count(*) FROM edgeai.task_attempt WHERE task_id IN ('+q(tasks['a'])+','+q(tasks['b'])+')',db)=='0'
    passed('a late contradictory group member rolls back every readiness change and queued Attempt')
    run,tasks=fixture('second-member-fault')
    pg.sql("CREATE FUNCTION edgeai.owned_component_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
        "IF NEW.run_id="+q(run)+" AND NEW.state='READY' AND EXISTS (SELECT FROM edgeai.task WHERE run_id=NEW.run_id AND id<>NEW.id AND state='READY') "
        "THEN RAISE EXCEPTION 'owned second member fault'; END IF; RETURN NEW; END $$; "
        "CREATE TRIGGER owned_component_fault BEFORE UPDATE ON edgeai.task FOR EACH ROW EXECUTE FUNCTION edgeai.owned_component_fault()",db)
    diagnostic_offset=pg.log.stat().st_size
    try:
        try:execute(run,tasks)
        except RuntimeError:
            with pg.log.open('rb') as log:
                log.seek(diagnostic_offset);assert b'owned second member fault' in log.read()
        else:raise AssertionError('Second member fault was not exercised')
    finally:pg.sql('DROP TRIGGER owned_component_fault ON edgeai.task; DROP FUNCTION edgeai.owned_component_fault()',db)
    assert pg.sql('SELECT count(*) FROM edgeai.task WHERE id IN ('+q(tasks['a'])+','+q(tasks['b'])+") AND state='WAITING'",db)=='2'
    assert pg.sql('SELECT count(*) FROM edgeai.task_attempt WHERE task_id IN ('+q(tasks['a'])+','+q(tasks['b'])+')',db)=='0'
    passed('an observed second-member SQL fault rolls back the already-readied first member and its queued Attempt')
    report.update(streamReadinessSqlCases=4,streamReadinessReleasedMembers=2,streamReadinessNewRuntimes=0,
        streamReadinessObservedSecondMemberRollback=True)
