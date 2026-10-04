"""Actual packaged HTTP API and owned PostgreSQL: audit failures, restart, auth and privacy boundaries."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import secrets
import time
import traceback
import urllib.error
import urllib.request
import uuid

from postgres_backup import Postgres,ROOT,identifier,literal,private_file
Api=runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport',choices=['native','compose'],default='native')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/management-audit-test.json')
    args=parser.parse_args()
    identity=uuid.uuid4().hex;work=ROOT/'.tools'/('management-audit-test-'+identity);work.mkdir(mode=0o700)
    database='edgeai_audit_'+identity;pg=Postgres(args.transport,diagnostics=work/'database')
    apis=[];oid=None
    report={'scope':'management-http-audit','sourceMode':'SYNTHETIC','status':'RUNNING','cases':[],
        'ownedApisStopped':False,'ownedDatabaseRemoved':False}
    canary='PRIVATE-AUDIT-CANARY-'+secrets.token_hex(16)
    def passed(name):report['cases'].append(name);print('PASS: '+name,flush=True)
    def sql(statement):return pg.sql(statement,database)
    def count(table):return int(sql('SELECT count(*) FROM edgeai.'+table))
    def request(method,path,value=None,expected=200,headers=None):
        data=None if value is None else json.dumps(value).encode()
        supplied={**api.headers,'Content-Type':'application/json'} if headers is None else headers
        req=urllib.request.Request(api.url+'/api/v1/'+path,data=data,headers=supplied,method=method)
        try:response=urllib.request.urlopen(req,timeout=10)
        except urllib.error.HTTPError as error:response=error
        with response:
            body=response.read()
            assert response.status==expected,'Unexpected API status; values suppressed'
            audit=response.headers.get('X-EdgeAI-Audit-Id')
            if audit:assert str(uuid.UUID(audit))==audit
            return json.loads(body) if body else None,audit
    def event(audit_id,recorded=True):
        deadline=time.monotonic()+5
        while True:
            value=request('GET','audit-requests/'+audit_id)[0]
            if not recorded or value['outcome'] is not None:return value
            assert time.monotonic()<deadline,'Expected audit outcome was not recorded'
            time.sleep(.05)
    def profile(key):return {'key':key,'version':'1.0.0','spec':{'canary':canary}}
    def rejection(statement):
        try:sql('BEGIN; '+statement+'; ROLLBACK;')
        except Exception:return
        raise AssertionError('Immutable audit operation was accepted')
    code=1
    try:
        pg.check_versions('postgres');pg.sql('CREATE DATABASE '+identifier(database),'postgres')
        oid=pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(database),'postgres');assert oid
        api=Api(database,work);apis.append(api)
        key='audit-'+identity
        value,first=request('POST','profiles/DEVICE?private='+canary,profile(key),201,
            {**api.headers,'Content-Type':'application/json','X-EdgeAI-Audit-Id':str(uuid.uuid4()),'X-Private-Test':canary})
        assert first
        stored=event(first)
        assert stored['state']=='OUTCOME_RECORDED' and stored['operation']=='publishProfile'
        assert stored['routeTemplate']=='/api/v1/profiles/{kind}' and stored['outcome']['httpStatus']==201
        assert stored['outcome']['actor']=={'type':'LOCAL_BASIC','subject':'backup-test','subjectFormat':'NAME'}
        passed('real-Basic-CSRF-mutation-has-durable-server-audit-id-and-authenticated-outcome')

        before=count('management_audit_request');request('GET','profiles/DEVICE');request('GET','audit-requests?limit=1')
        assert count('management_audit_request')==before
        _,denied=request('GET','devices?private='+canary,expected=401,headers={'Authorization':'Basic invalid-'+canary})
        if denied is None:
            deadline=time.monotonic()+5
            while count('management_audit_request')==before:
                assert time.monotonic()<deadline,'Read rejection was not recorded';time.sleep(.05)
            denied=sql('SELECT id FROM edgeai.management_audit_request ORDER BY started_at DESC,id DESC LIMIT 1')
        assert event(denied)['outcome']['actor']['type']=='UNAUTHENTICATED'
        _,csrf=request('POST','profiles/DEVICE',profile('csrf-'+identity),403,headers={'Authorization':api.headers['Authorization'],'Content-Type':'application/json'})
        assert event(csrf)['outcome']['actor']['type']=='UNAUTHENTICATED'
        _,unauthorized=request('POST','profiles/DEVICE',profile('auth-'+identity),401,
            headers={**api.headers,'Authorization':'Basic invalid-'+canary,'Content-Type':'application/json'})
        assert event(unauthorized)['outcome']['actor']['type']=='UNAUTHENTICATED'
        passed('successful-reads-are-not-recorded-and-real-401-CSRF403-never-trust-credential-text')

        _,conflict=request('POST','profiles/DEVICE',{'key':key,'version':'1.0.0','spec':{'changed':True}},409)
        _,invalid=request('POST','profiles/DEVICE',{},400)
        assert event(conflict)['outcome']['httpStatus']==409 and event(invalid)['outcome']['httpStatus']==400
        target=str(uuid.uuid4())
        _,missing=request('DELETE','devices/'+target,expected=404)
        assert event(missing)['targetId']==target and event(missing)['outcome']['httpStatus']==404
        passed('conflicts-validation-and-domain-not-found-retain-exact-HTTP-outcome-and-UUID-target')

        sql("CREATE FUNCTION edgeai.audit_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned test refusal' USING ERRCODE='23514'; END; $$")
        sql('CREATE TRIGGER audit_test_admission BEFORE INSERT ON edgeai.management_audit_request FOR EACH ROW EXECUTE FUNCTION edgeai.audit_test_reject()')
        before=count('management_audit_request');rejected_key='not-created-'+identity
        result,failed=request('POST','profiles/DEVICE',profile(rejected_key),503)
        assert result['code']=='AUDIT_STORE_UNAVAILABLE' and failed is None and count('management_audit_request')==before
        assert sql('SELECT count(*) FROM edgeai.profile_version WHERE profile_key='+literal(rejected_key))=='0'
        sql('DROP TRIGGER audit_test_admission ON edgeai.management_audit_request')
        passed('actual-PostgreSQL-admission-failure-returns503-before-any-domain-change')

        sql('CREATE TRIGGER audit_test_completion BEFORE INSERT ON edgeai.management_audit_outcome FOR EACH ROW EXECUTE FUNCTION edgeai.audit_test_reject()')
        unknown_key='unknown-'+identity
        created,unknown=request('POST','profiles/DEVICE',profile(unknown_key),201)
        assert event(unknown,False)['state']=='OUTCOME_UNKNOWN' and event(unknown,False)['outcome'] is None
        assert sql('SELECT count(*) FROM edgeai.profile_version WHERE profile_key='+literal(unknown_key))=='1'
        sql('DROP TRIGGER audit_test_completion ON edgeai.management_audit_outcome');sql('DROP FUNCTION edgeai.audit_test_reject()')
        api.close();api=Api(database,work);apis.append(api)
        assert event(unknown,False)['state']=='OUTCOME_UNKNOWN'
        replay,replayed=request('POST','profiles/DEVICE',profile(unknown_key),200)
        assert replay['id']==created['id'] and replayed!=unknown
        assert event(replayed)['outcome']['httpStatus']==200 and event(unknown,False)['outcome'] is None
        passed('actual-result-write-failure-preserves-201-and-unknown-intent-through-API-restart-idempotent-replay')

        for table,column in [('management_audit_request','id'),('management_audit_outcome','request_id')]:
            for statement in ['UPDATE edgeai.'+table+' SET '+column+'='+literal(first)+' WHERE '+column+'='+literal(first),
                'DELETE FROM edgeai.'+table+' WHERE '+column+'='+literal(first),'TRUNCATE edgeai.'+table+' CASCADE']:
                rejection(statement)
        rejection('INSERT INTO edgeai.management_audit_outcome SELECT * FROM edgeai.management_audit_outcome WHERE request_id='+literal(first))
        assert event(first)==stored
        passed('real-database-rejects-audit-update-delete-truncate-and-duplicate-outcomes')

        _,unmapped=request('POST',canary+'/unknown',{},404)
        assert event(unmapped)['operation']=='unmappedManagementRequest' and event(unmapped)['routeTemplate']=='/api/v1/**'
        _,malformed=request('DELETE','devices/'+canary,expected=400)
        assert event(malformed)['targetId'] is None
        dump=sql("SELECT to_jsonb(r)::text FROM edgeai.management_audit_request r")+sql("SELECT to_jsonb(o)::text FROM edgeai.management_audit_outcome o")
        assert all(secret not in dump for secret in (canary,api.headers['Authorization'],api.headers['X-CSRF-TOKEN'],api.headers['Cookie']))
        assert event(unmapped)['outcome']['actor']['type']=='LOCAL_BASIC'
        passed('body-query-header-credential-CSRF-cookie-and-invalid-path-canaries-are-absent-from-durable-audit')

        request('GET','audit-requests?limit=0',expected=400);request('GET','audit-requests?offset=-1',expected=400)
        request('GET','audit-requests/not-a-uuid',expected=400);request('GET','audit-requests/'+str(uuid.uuid4()),expected=404)
        page,_=request('GET','audit-requests?limit=2');assert len(page['items'])==2 and page['nextOffset']==2
        report.update(serverMajor=int(pg.sql('SHOW server_version_num',database))//10000,
            auditRequests=count('management_audit_request'),unknownOutcomes=int(sql('SELECT count(*) FROM edgeai.management_audit_request r LEFT JOIN edgeai.management_audit_outcome o ON o.request_id=r.id WHERE o.request_id IS NULL')),
            jarSha256=hashlib.sha256((ROOT/'backend/app/build/libs/edgeai-control-plane.jar').read_bytes()).hexdigest(),
            realAdmissionFailure=True,realCompletionFailure=True,restartedApi=True,secretCanariesExcluded=True)
        assert report['unknownOutcomes']==1
        passed('bounded-authenticated-audit-query-exposes-unconfirmed-outcome-without-inventing-domain-success')
        code=0
    except Exception as error:
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as log:traceback.print_exc(file=log)
        print('FAIL: management audit test; private diagnostics retained',flush=True)
    finally:
        for api in apis:api.close()
        report['ownedApisStopped']=all(api.process.poll() is not None for api in apis)
        try:
            if oid:
                assert pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(database),'postgres')==oid
                pg.sql('DROP DATABASE '+identifier(database),'postgres')
            report['ownedDatabaseRemoved']=True
        except Exception as error:report['cleanupFailureType']=type(error).__name__;code=1
        report['status']='PASS' if code==0 else 'FAIL'
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(report['status']+': '+str(len(report['cases']))+' real management audit cases',flush=True)
    return code


if __name__=='__main__':raise SystemExit(main())
