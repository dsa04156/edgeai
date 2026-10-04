"""Retire restored STREAM generations against fresh proof of original broker authority revocation."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re

from postgres_backup import Blocked, Postgres, literal, private_file
from recovery_kubernetes import database_inventory
from recovery_remote_inventory import canonical
from recovery_remote_retire import durable_json
from recovery_device_readiness import observe_broker
from recovery_device_journal import TABLES as DEVICE_TABLES

TABLES=(*DEVICE_TABLES,'route_heartbeat','runtime_instance','runtime_command','task_retry')
GUARD='SELECT jsonb_build_object('+','.join(literal(name)+
    ",(SELECT encode(sha256(convert_to(coalesce(string_agg(encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'),'' "
    "ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex') FROM edgeai."+name+' t)' for name in TABLES)+')'
QUERY=("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT jsonb_build_object("
    "'database',current_database(),'oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),"
    "'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),"
    "'readOnly',current_setting('transaction_read_only'),'migrations',(SELECT jsonb_agg(jsonb_build_object("
    "'version',version,'success',success)) FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),"
    "'guard',("+GUARD+"),'generations',(SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY g.id),'[]'::jsonb)"
    " FROM edgeai.route_generation g)); COMMIT;")


def prepare(pg,args):
    if not re.fullmatch('sha256:[0-9a-f]{64}',args.broker_digest):raise ValueError('Exact original broker digest required')
    before=database_inventory(pg,args.database,args.restore_report,QUERY)
    broker=observe_broker(args,args.broker_digest)
    after=database_inventory(pg,args.database,args.restore_report,QUERY)
    if before!=after:raise Blocked('Restored STREAM inventory changed during broker observation')
    selected=[g['id'] for g in after['generations'] if g['broker_digest']==args.broker_digest]
    unresolved=[{'generationId':g['id'],'brokerDigest':g['broker_digest'],'reason':'OTHER_BROKER_AUTHORITY_NOT_PROVEN'}
        for g in after['generations'] if g['broker_digest']!=args.broker_digest and g['closed_at'] is None]
    return {'formatVersion':1,'scope':'restored-stream-broker-retirement','targetDatabase':args.database,
        'databaseOid':after['oid'],'marker':after['marker'],'restoreReportSha256':after['restoreReportSha256'],
        'beforeGuard':after['guard'],'broker':broker,'brokerDigest':args.broker_digest,
        'selected':selected,'unresolvedGenerations':unresolved,'recoveryId':args.recovery_id,
        'preparedAt':datetime.now(timezone.utc).isoformat()}


def transaction_sql(plan):
    identity=("IF current_database()<>"+literal(plan['targetDatabase'])+
        " OR NOT EXISTS(SELECT FROM pg_database WHERE datname=current_database() AND oid::text="+
        literal(plan['databaseOid'])+" AND shobj_description(oid,'pg_database')="+literal(plan['marker'])+
        ") THEN RAISE EXCEPTION 'Restored database identity changed'; END IF;\n")
    selected='(SELECT value::uuid FROM jsonb_array_elements_text('+literal(json.dumps(plan['selected']))+'::jsonb))'
    body=("DECLARE fenced integer; closed integer; BEGIN\n"+identity+
        'IF ('+GUARD+') IS DISTINCT FROM '+literal(canonical(plan['beforeGuard']).decode())+"::jsonb THEN "
        "RAISE EXCEPTION 'Restored STREAM state changed before retirement'; END IF;\n"
        "UPDATE edgeai.route_generation SET fenced_at=transaction_timestamp(),fence_reason='REPLACED',updated_at=transaction_timestamp() "
        "WHERE id IN "+selected+" AND fenced_at IS NULL; GET DIAGNOSTICS fenced=ROW_COUNT;\n"
        "UPDATE edgeai.route_generation SET closed_at=transaction_timestamp(),updated_at=transaction_timestamp() "
        "WHERE id IN "+selected+" AND closed_at IS NULL; GET DIAGNOSTICS closed=ROW_COUNT;\n"
        "IF EXISTS(SELECT FROM edgeai.route_generation WHERE id IN "+selected+
        " AND (fenced_at IS NULL OR closed_at IS NULL OR broker_digest<>"+literal(plan['brokerDigest'])+")) THEN "
        "RAISE EXCEPTION 'Restored STREAM retirement postcondition differs'; END IF;\n"+identity+
        "INSERT INTO stream_retirement_result VALUES(jsonb_build_object('generationsFenced',fenced,"
        "'generationsClosed',closed,'afterGuard',("+GUARD+"))); END")
    return ("BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s';\nLOCK TABLE "+
        ','.join('edgeai.'+name for name in TABLES)+' IN SHARE ROW EXCLUSIVE MODE;\n'
        'CREATE TEMP TABLE stream_retirement_result(value jsonb) ON COMMIT DROP;\nDO '+literal(body)+';\n'
        'SET CONSTRAINTS ALL IMMEDIATE; SELECT value FROM stream_retirement_result; COMMIT;\n')


def apply(pg,args,plan):
    current=prepare(pg,args)
    if any(current[k]!=plan[k] for k in plan if k!='preparedAt'):
        raise Blocked('STREAM recovery inputs changed before retirement')
    durable_json(args.output/'intent.json',plan)
    sql=args.output/'transaction.sql'
    with private_file(sql,'w') as out:
        out.write(transaction_sql(plan));out.flush();os.fsync(out.fileno())
    with sql.open('rb') as source:
        result=json.loads(pg.call('psql',['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-f','-'],args.database,
            source=source,timeout=45,reject_stderr=True))
    after=prepare(pg,args)
    if (after['beforeGuard']!=result['afterGuard'] or any(after[k]!=plan[k] for k in
            ('targetDatabase','databaseOid','marker','restoreReportSha256','broker','brokerDigest','selected','unresolvedGenerations','recoveryId'))):
        raise Blocked('Post-commit STREAM authority changed; retain quarantine')
    report={k:plan[k] for k in ('formatVersion','scope','targetDatabase','databaseOid','brokerDigest','selected','unresolvedGenerations','recoveryId')}
    report.update(status='ORIGINAL_BROKER_GENERATIONS_RETIRED',**result,
        databaseModified=bool(result['generationsFenced'] or result['generationsClosed']),brokerModified=False,
        activated=False,globalQuiescenceProven=False,producerProcessQuiescenceProven=False,
        intentSha256=hashlib.sha256((args.output/'intent.json').read_bytes()).hexdigest(),verifiedAt=datetime.now(timezone.utc).isoformat(),
        preserved=['existing-fence-reasons','checkpoint-and-completion-history','run-task-attempt-outcomes','database-quarantine'],
        excluded=['other-brokers','producer-process-retirement','new-credentials-and-grants','group-retry-and-offload','service-activation'])
    durable_json(args.output/'retirement.json',report)
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('database','broker-digest','recovery-id'):parser.add_argument('--'+name,required=True)
    for name in ('restore-report','mqtt-state-directory','mqtt-ca-file','mqtt-original-password-file','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--timeout',type=int,default=60)
    parser.add_argument('--transport',choices=['native','compose'],default='native');parser.add_argument('--pg-bin',type=Path)
    args=parser.parse_args();args.output.mkdir(mode=0o700);submitted=False
    try:
        pg=Postgres(args.transport,args.pg_bin,diagnostics=args.output/'postgres')
        plan=prepare(pg,args);submitted=True;report=apply(pg,args,plan)
        print(report['status']+': original broker generations closed; restored database remains quarantined')
        return 0
    except Exception as error:
        blocked=isinstance(error,(Blocked,OSError))
        durable_json(args.output/'failure.json',{'status':'BLOCKED' if blocked else 'FAIL','failureType':type(error).__name__,
            'activated':False,'databaseModified':None if submitted else False,'brokerModified':False,
            'instruction':'Retain database quarantine and broker fence; repeat the same recovery in a new output directory'})
        print(('BLOCKED' if blocked else 'FAIL')+': STREAM retirement; private evidence retained')
        return 2 if blocked else 1


if __name__=='__main__':raise SystemExit(main())
