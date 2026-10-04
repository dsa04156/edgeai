"""Actual local Device writer retirement, immutable identity and final encrypted snapshot verification."""
import argparse
from contextlib import closing
from dataclasses import replace
import hashlib
import json
import multiprocessing as mp
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import time
import traceback
from unittest.mock import patch
import uuid

import recovery_device_retire as retirement
import device_journal_backup as journals
import private_material_backup as material
from postgres_backup import ROOT, Blocked, private_file
from edgeai_runner.stream_journal import Journal, JournalError, Emission
from edgeai_runner.stream_protocol import Binding, Producer


def writer(directory,bindings,ready,advance,advanced):
    try:
        with Journal(directory/'journal',[],bindings,create=True) as journal:
            journal.commit(0,[],b'1',[Emission(b.route_id,b'1','application/json') for b in bindings]);ready.set()
            if not advance.wait(30):raise RuntimeError('Test writer advancement not requested')
            journal.commit(1,[],b'2',[Emission(b.route_id,b'2','application/json') for b in bindings]);advanced.set()
            while True:
                with journal._transaction(advance=False):pass
                time.sleep(.005)
    except JournalError as error:
        if str(error)!='Stream source journal is permanently retired':raise


def uncooperative(directory,bindings,ready,stop):
    with Journal(directory/'journal',[],bindings,create=True):
        ready.set();stop.wait(30)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/device-source-retirement-test.json');args=parser.parse_args()
    work=ROOT/'.tools'/('device-source-retirement-'+uuid.uuid4().hex);work.mkdir(mode=0o700)
    report={'status':'RUNNING','scope':'local-device-source-retirement-tests','sourceMode':'SYNTHETIC','cases':[],
        'activated':False,'ownedProcessesStopped':False}
    age=material.Age();processes=[];actor=Producer('DEVICE_SESSION',str(uuid.uuid4()),1,str(uuid.uuid4()))
    bindings=[Binding(str(uuid.uuid4()),1,actor) for _ in range(2)];recovery_id=str(uuid.uuid4())
    def directory(name):p=work/name;p.mkdir(mode=0o700);return p
    def passed(name):report['cases'].append(name);print('PASS: '+name,flush=True)
    def refused(operation,kind=Blocked):
        try:operation()
        except kind:return
        raise AssertionError('Invalid source retirement was accepted')
    def snapshot(source,name):
        bundle=directory(name+'-backup');journals.backup(age,source,recipient,bundle)
        restored=directory(name+'-restore');journals.restore(age,bundle,key,restored)
        return restored
    def cli(source,restored,label,expected=0,operation=recovery_id):
        output=work/label
        result=subprocess.run([sys.executable,'scripts/recovery_device_retire.py','--source',str(source),
            '--journal-restore',str(restored),'--recovery-id',operation,'--timeout','2','--output',str(output)],
            capture_output=True,timeout=30)
        with private_file(work/(label+'.log')) as log:log.write(result.stdout+result.stderr)
        assert result.returncode==expected,'Unexpected retirement result; private diagnostics retained'
        return output/('retirement-report.json' if expected==0 else 'failure.json')
    def original(name):
        source=directory(name)
        with Journal(source/'journal',[],bindings,create=True) as journal:
            journal.commit(0,[],b'1',[Emission(b.route_id,b'1','application/json') for b in bindings])
        return source
    code=1
    try:
        key=work/'identity'
        with private_file(key) as output:
            result=subprocess.run([str(age.paths['age-keygen'])],stdout=output,stderr=subprocess.PIPE,timeout=30)
        assert result.returncode==0
        recipient=subprocess.check_output([str(age.paths['age-keygen']),'-y',str(key)],stderr=subprocess.PIPE).decode().strip()
        source=directory('source');ready,advance,advanced=mp.Event(),mp.Event(),mp.Event()
        process=mp.Process(target=writer,args=(source,bindings,ready,advance,advanced));process.start();processes.append(process)
        assert ready.wait(5);old=snapshot(source,'old');advance.set();assert advanced.wait(5)
        receipt=cli(source,old,'retired');process.join(5);assert process.exitcode==0
        value=json.loads(receipt.read_text());assert value['sourceJournalOwnerQuiescenceProven'] and not value['snapshotMatchesSuppliedRestore']
        assert value['revision']==2 and value['producerProcessQuiescenceProven'] is False
        passed('real-writer-observes-durable-retirement-releases-owner-and-exits-with-final-data-retained')
        fresh=snapshot(source,'final');_,selected,evidence=retirement.comparison.restored_journal(fresh)
        proof=retirement.verify(source,receipt,recovery_id,selected,evidence['journalSnapshotSha256'])
        assert proof['sourceJournalOwnerQuiescenceProven'] and proof['activated'] is False
        passed('final-retired-journal-is-encrypted-restored-and-bound-to-fresh-owner-and-snapshot-evidence')
        _,old_bindings,old_evidence=retirement.comparison.restored_journal(old)
        refused(lambda:retirement.verify(source,receipt,recovery_id,old_bindings,old_evidence['journalSnapshotSha256']))
        passed('older-pre-retirement-snapshot-cannot-discard-the-final-generated-frames')
        marker=source/'journal/retirement.json';before=marker.read_bytes();data=(source/'journal/journal.sqlite').read_bytes()
        second=cli(source,fresh,'same-operation');assert json.loads(second.read_text())['snapshotMatchesSuppliedRestore']
        cli(source,fresh,'other-operation',2,str(uuid.uuid4()))
        assert marker.read_bytes()==before and (source/'journal/journal.sqlite').read_bytes()==data
        refused(lambda:Journal(source/'journal',[],bindings),JournalError)
        passed('same-operation-resumes-but-other-operations-and-original-journal-restarts-are-refused')
        ready,stop=mp.Event(),mp.Event();held=directory('held')
        process=mp.Process(target=uncooperative,args=(held,bindings,ready,stop));process.start();processes.append(process)
        assert ready.wait(5);held_restore=snapshot(held,'held');started=time.monotonic()
        failure=cli(held,held_restore,'held-timeout',2)
        assert time.monotonic()-started<6 and json.loads(failure.read_text())['sourceOwnerQuiescenceUnconfirmed']
        assert process.is_alive() and (held/'journal/retirement.json').exists()
        stop.set();process.join(5);assert process.exitcode==0
        cli(held,held_restore,'held-resumed')
        passed('uncooperative-live-owner-times-out-without-false-retirement-and-same-intent-resumes-after-release')
        foreign=original('foreign');_,_,digest=retirement.read_source(foreign,5)
        refused(lambda:retirement.retire(foreign,recovery_id,[replace(b,generation=2) for b in bindings],digest,2),ValueError)
        assert not (foreign/'journal/retirement.json').exists()
        passed('foreign-generation-bindings-are-refused-before-publishing-any-retirement-intent')
        swapped=source/'journal/old-owner.lock';os.rename(source/'journal/owner.lock',swapped)
        with private_file(source/'journal/owner.lock'):pass
        refused(lambda:retirement.verify(source,receipt,recovery_id,selected,evidence['journalSnapshotSha256']))
        (source/'journal/owner.lock').unlink();os.rename(swapped,source/'journal/owner.lock')
        passed('replaced-owner-lock-inode-invalidates-a-previous-retirement-receipt')
        with closing(sqlite3.connect(source/'journal/journal.sqlite')) as db:
            db.execute('UPDATE checkpoint SET state=?',(b'changed-after-retirement',));db.commit()
        refused(lambda:retirement.verify(source,receipt,recovery_id,selected,evidence['journalSnapshotSha256']))
        with closing(sqlite3.connect(source/'journal/journal.sqlite')) as db:
            db.execute('UPDATE checkpoint SET state=?',(b'2',));db.commit()
        retirement.verify(source,receipt,recovery_id,selected,evidence['journalSnapshotSha256'])
        passed('actual-post-retirement-sqlite-write-invalidates-the-old-data-proof')
        race=original('race');_,_,digest=retirement.read_source(race,5);write=material.write_json
        def change_lock(path,value):
            write(path,value)
            if Path(path)==race/'journal/retirement.json':
                os.rename(race/'journal/owner.lock',race/'journal/replaced.lock')
                with private_file(race/'journal/owner.lock'):pass
        with patch.object(material,'write_json',side_effect=change_lock):
            refused(lambda:retirement.retire(race,recovery_id,bindings,digest,2))
        passed('owner-lock-replacement-between-intent-and-exclusive-acquisition-cannot-prove-retirement')
        startup=original('startup');lock=retirement.fcntl.flock
        def fence_after_lock(fd,operation):
            lock(fd,operation)
            (startup/'journal/retirement.json').write_text('{}')
        with patch.object(retirement.fcntl,'flock',side_effect=fence_after_lock):
            refused(lambda:Journal(startup/'journal',[],bindings),JournalError)
        passed('new-owner-rechecks-retirement-after-acquiring-the-lock-before-opening-sqlite')
        marker.unlink();marker.symlink_to(work/'missing-marker')
        refused(lambda:Journal(source/'journal',[],bindings),JournalError)
        passed('dangling-retirement-marker-cannot-reenable-the-original-journal')
        report.update(status='PASS',finalRevision=2,finalSnapshotPreserved=True,liveOwnerExited=True,timeoutRetained=True,
            originalRestartDenied=True,identityRacesRejected=True)
        code=0
    except Exception as error:
        report.update(status='FAIL',failureType=type(error).__name__,failureFrames=[{'file':Path(f.filename).name,'line':f.lineno} for f in traceback.extract_tb(error.__traceback__)])
        print('FAIL: local source retirement; '+type(error).__name__+'; private diagnostics retained',file=sys.stderr)
    finally:
        for process in processes:
            if process.is_alive():process.kill()
            process.join(5)
        report['ownedProcessesStopped']=all(not p.is_alive() for p in processes)
        material.write_json(args.report,report)
    return code


if __name__=='__main__':raise SystemExit(main())
