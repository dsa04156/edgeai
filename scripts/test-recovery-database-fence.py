"""Actual PostgreSQL/API connection fencing, interrupted transaction and unrelated database preservation."""
import argparse
import json
from pathlib import Path
import runpy
import subprocess
import sys
import time
import traceback
import urllib.error
import uuid

from postgres_backup import Postgres, ROOT, identifier, literal, private_file
from recovery_database_fence import fence, state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport',choices=['native','compose'],default='native')
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/recovery-database-fence-test.json')
    args = parser.parse_args()
    token = uuid.uuid4().hex
    work = ROOT/'.tools'/('recovery-database-fence-test-'+token)
    work.mkdir(mode=0o700)
    pg = Postgres(args.transport,diagnostics=work/'diagnostics')
    source,other,empty = ['edgeai_fence_'+kind+'_'+token for kind in ['source','other','empty']]
    operation = str(uuid.uuid4())
    owned,clients,apis = {},[],[]
    report = {'scope':'postgresql-source-connection-fence-tests','sourceMode':'SYNTHETIC','status':'RUNNING',
        'cases':[],'ownedApisStopped':False,'ownedDatabasesRemoved':False,'ownedClientsStopped':False}

    def passed(name):
        report['cases'].append(name); print('PASS: '+name,flush=True)

    def cli(name,expected=0,database=None,oid=None,recovery_id=None):
        target = database or source
        output = work/name
        result = subprocess.run([sys.executable,'scripts/recovery_database_fence.py','--transport',args.transport,
            '--database',target,'--database-oid',oid or owned[target],'--recovery-id',recovery_id or operation,
            '--timeout','15','--output',str(output)],capture_output=True,timeout=60)
        with private_file(work/('command-'+uuid.uuid4().hex+'.log')) as out:
            out.write(result.stdout); out.write(result.stderr)
        assert result.returncode == expected, name+': unexpected exit; private diagnostics retained'
        value = json.loads((output/'fence-report.json').read_text())
        assert value['activated'] is False and value['globalQuiescenceProven'] is False
        assert (output/'fence-report.json').stat().st_mode & 0o077 == 0
        return value

    def hold(database,write=False):
        application = 'edgeai-fence-probe-'+uuid.uuid4().hex
        text = ('BEGIN; INSERT INTO public.fence_probe VALUES(2); ' if write else '')+'SELECT pg_sleep(600);'+(' COMMIT;' if write else '')
        env = {**pg.env,'PGAPPNAME':application}
        with private_file(work/('client-'+uuid.uuid4().hex+'.log')) as out:
            process = subprocess.Popen(pg.prefix+[pg.binaries['psql']]+pg.connection+
                ['--dbname',database,'-X','-A','-t','-v','ON_ERROR_STOP=1','-c',text],env=env,stdout=out,stderr=out)
        clients.append(process)
        deadline = time.monotonic()+10
        while time.monotonic()<deadline:
            assert process.poll() is None
            pid = pg.sql("SELECT pid FROM pg_stat_activity WHERE application_name="+literal(application)+" AND wait_event='PgSleep'",'postgres')
            if pid:
                return process,pid
            time.sleep(.05)
        raise AssertionError('Owned PostgreSQL client did not enter its transaction/sleep')

    code = 1
    try:
        for database in [source,other,empty]:
            pg.sql('CREATE DATABASE '+identifier(database),'postgres')
            owned[database] = state(pg,database)['oid']
        Api = runpy.run_path(str(ROOT/'scripts/test-postgres-backup.py'))['Api']
        pool = {'SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE':'2','SPRING_DATASOURCE_HIKARI_MINIMUMIDLE':'2',
                'SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT':'1000','SPRING_DATASOURCE_HIKARI_INITIALIZATIONFAILTIMEOUT':'1000'}
        api = Api(source,work,extra_env=pool); apis.append(api)
        profile = api.request('POST','profiles/DEVICE',{'key':'fence-'+token,'version':'1.0.0','spec':{'protocol':'synthetic'}},201)
        assert profile['id']
        pg.sql('CREATE TABLE public.fence_probe(value integer); INSERT INTO public.fence_probe VALUES(1)',source)
        pg.sql('CREATE TABLE public.fence_probe(value integer); INSERT INTO public.fence_probe VALUES(99)',other)
        source_client,source_pid = hold(source,True)
        other_client,other_pid = hold(other)
        assert pg.sql('SELECT count(*) FROM public.fence_probe',source)=='1'
        passed('real-api-and-uncommitted-source-write-with-unrelated-live-client')

        wrong = cli('wrong-oid',expected=1,oid=str(int(owned[source])+1))
        assert not wrong['fenceRetained'] and state(pg,source)['connectionsAllowed']
        assert state(pg,source)['marker'] is None and source_client.poll() is None
        passed('wrong-database-identity-refused-before-fencing')
        cli('empty-database',expected=1,database=empty)
        assert state(pg,empty)['connectionsAllowed'] and state(pg,empty)['marker'] is None
        passed('database-without-edgeai-history-preserved')
        pg.sql('COMMENT ON DATABASE '+identifier(source)+" IS 'existing-comment-kept'",'postgres')
        cli('existing-comment',expected=1)
        assert state(pg,source)['marker']=='existing-comment-kept' and state(pg,source)['connectionsAllowed']
        pg.sql('COMMENT ON DATABASE '+identifier(source)+' IS NULL','postgres')
        passed('unrelated-database-comment-preserved')

        fenced = cli('fenced')
        assert fenced['status']=='SOURCE_DATABASE_CONNECTIONS_FENCED' and fenced['fenceRetained']
        assert fenced['newConnectionRejected'] and fenced['remainingBackends']==fenced['preparedTransactions']==0
        assert fenced['terminatedBackends']>=3
        assert source_client.wait(10)!=0 and other_client.poll() is None
        assert pg.sql('SELECT count(*) FROM pg_stat_activity WHERE datid='+owned[source]+'::oid','postgres')=='0'
        assert pg.sql('SELECT count(*) FROM pg_stat_activity WHERE datid='+owned[other]+'::oid AND pid='+other_pid,'postgres')=='1'
        assert pg.sql('SELECT value FROM public.fence_probe',other)=='99'
        report['terminatedBackends']=fenced['terminatedBackends']
        passed('new-source-connections-denied-and-old-api-and-writer-backends-exited')
        passed('unrelated-database-live-connection-and-data-preserved')

        try:
            api.request('POST','profiles/DEVICE',{'key':'blocked-'+token,'version':'1.0.0','spec':{}},201)
            raise AssertionError('Old API wrote after source fencing')
        except urllib.error.HTTPError as error:
            assert error.code==503
        api.close()
        try:
            restarted = Api(source,work,extra_env=pool)
        except AssertionError:
            pass
        else:
            restarted.close(); raise AssertionError('New API started against the fenced source')
        passed('old-api-write-503-and-fresh-api-startup-refused')

        resumed = cli('resumed')
        assert resumed['status']=='SOURCE_DATABASE_CONNECTIONS_FENCED' and resumed['fenceRetained']
        assert resumed['terminatedBackends']==0
        refused = cli('different-operation',expected=1,recovery_id=str(uuid.uuid4()))
        assert refused['status']=='FAIL' and not state(pg,source)['connectionsAllowed']
        assert state(pg,source)['marker']=='edgeai-recovery-fence:'+operation
        passed('same-operation-resumes-and-another-operation-cannot-adopt-fence')

        # This explicit reopening is confined to our disposable fixture after all owned API
        # processes exited. The product command never releases a fence or activates a restore.
        assert all(a.process.poll() is not None for a in apis)
        assert state(pg,source)['oid']==owned[source]
        pg.sql('ALTER DATABASE '+identifier(source)+' ALLOW_CONNECTIONS true','postgres')
        assert pg.sql('SELECT json_agg(value ORDER BY value) FROM public.fence_probe',source)=='[1]'
        assert pg.sql("SELECT count(*) FROM edgeai.profile_version WHERE profile_key="+literal('blocked-'+token),source)=='0'
        assert pg.sql("SELECT count(*) FROM edgeai.profile_version WHERE profile_key="+literal('fence-'+token),source)=='1'
        cli('externally-reopened',expected=1)
        assert state(pg,source)['connectionsAllowed']
        passed('uncommitted-write-rolled-back-committed-data-preserved-and-external-reopen-refused')

        race,retired = 'edgeai_fence_race_'+token,'edgeai_fence_retired_'+token
        pg.sql('CREATE DATABASE '+identifier(race)+' TEMPLATE '+identifier(source),'postgres')
        old_oid = owned[race] = state(pg,race)['oid']
        original_sql = pg.sql
        changed = False
        def replace_at_mutation(text,database):
            nonlocal changed
            if text.startswith('DO $fence$'):
                pg.sql = original_sql
                original_sql('ALTER DATABASE '+identifier(race)+' RENAME TO '+identifier(retired),'postgres')
                owned[retired] = owned.pop(race)
                original_sql('CREATE DATABASE '+identifier(race),'postgres')
                owned[race] = state(pg,race)['oid']
                changed = True
            return original_sql(text,database)
        offset = pg.log.stat().st_size
        pg.sql = replace_at_mutation
        try:
            fence(pg,race,old_oid,str(uuid.uuid4()),15,{})
            raise AssertionError('A replaced database was fenced')
        except RuntimeError:
            assert changed and owned[race]!=old_oid
            with pg.log.open('rb') as log:
                log.seek(offset); assert b'Recovery source identity changed' in log.read()
        finally:
            pg.sql = original_sql
        assert state(pg,race)['connectionsAllowed'] and state(pg,race)['marker'] is None
        assert state(pg,retired)['oid']==old_oid and state(pg,retired)['connectionsAllowed']
        report['replacedDatabasePreserved']=True
        passed('real-database-replacement-between-preflight-and-alter-rolls-back-the-fence')
        code = 0
    except Exception as error:
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as out: traceback.print_exc(file=out)
        print('FAIL: database fence acceptance; private evidence retained',flush=True)
    finally:
        try:
            for api in apis: api.close()
            report['ownedApisStopped']=all(api.process.poll() is not None for api in apis)
            for process in clients:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(5)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(5)
            report['ownedClientsStopped']=all(p.poll() is not None for p in clients)
            for database,oid in owned.items():
                assert state(pg,database)['oid']==oid
                pg.sql('DROP DATABASE '+identifier(database)+' WITH (FORCE)','postgres')
            report['ownedDatabasesRemoved']=True
        except Exception as error:
            code=1;report['cleanupFailureType']=type(error).__name__
        report['status']='PASS' if code==0 else 'FAIL'
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with private_file(args.report,'w') as out: json.dump(report,out,indent=2);out.write('\n')
    print(report['status']+': actual source database fence; '+str(len(report['cases']))+' cases')
    return code


if __name__=='__main__':
    sys.exit(main())
