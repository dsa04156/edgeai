"""Actual SQLite writers, age encryption, source loss and quarantined Device journal restoration."""
import argparse
import base64
import copy
from dataclasses import replace
import hashlib
import json
import multiprocessing as mp
import os
from pathlib import Path
import secrets
import shutil
import signal
import sqlite3
import subprocess
import sys
import time
import traceback
from types import SimpleNamespace
from unittest.mock import patch
import uuid

import device_journal_backup as recovery
import private_material_backup as material
from postgres_backup import ROOT, private_file
from edgeai_runner.stream_journal import Journal, JournalError, Limits, Emission
from edgeai_runner.stream_protocol import Binding, Producer
from edgeai_runner import stream_source_completion as completion


def live_source(directory, bindings, ready, stop):
    with Journal(directory/'journal', [], bindings, create=True) as journal:
        n = 0
        while not stop.is_set():
            n += 1
            journal.commit(n-1, [], str(n).encode(), [Emission(b.route_id,str(n).encode(),'application/json') for b in bindings])
            for index,binding in enumerate(bindings):
                if n > index+1: journal.acknowledge(binding,n-index-1)
            ready.set(); time.sleep(.004)


def locked_source(directory, ready, stop):
    db = sqlite3.connect(directory/'journal/journal.sqlite', isolation_level=None)
    db.execute('BEGIN EXCLUSIVE'); ready.set(); stop.wait(30); db.rollback(); db.close()


def crash_restore(bundle, identity, output, ready, after):
    output.mkdir(mode=0o700)
    if after:
        original = os.rename
        def rename(source,destination):
            original(source,destination)
            if Path(destination) == output/'source': ready.set(); time.sleep(60)
        os.rename = rename
    else:
        original = material.write_json
        def write(path,value):
            if Path(path).name == 'recovery.json': ready.set(); time.sleep(60)
            return original(path,value)
        material.write_json = write
    recovery.restore(material.Age(),bundle,identity,output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/device-journal-test.json'); args=parser.parse_args()
    work=ROOT/'.tools'/('device-journal-test-'+uuid.uuid4().hex); work.mkdir(mode=0o700)
    report={'status':'RUNNING','scope':'device-source-journal-backup-tests','sourceMode':'SYNTHETIC','cases':[],'activated':False}
    age=material.Age(); processes=[]; canary='DEVICE-PRIVATE-'+secrets.token_hex(24)
    actor=Producer('DEVICE_SESSION',str(uuid.uuid4()),1,str(uuid.uuid4()))
    bindings=[Binding(str(uuid.uuid4()),i+1,actor) for i in range(2)]

    def passed(name):report['cases'].append(name);print('PASS: '+name,flush=True)
    def output(name):path=work/name;path.mkdir(mode=0o700);return path
    def key(name):
        path=work/name
        with private_file(path) as target:
            result=subprocess.run([str(age.paths['age-keygen'])],stdout=target,stderr=subprocess.PIPE,timeout=30)
        assert result.returncode==0
        recipient=subprocess.check_output([str(age.paths['age-keygen']),'-y',str(path)],stderr=subprocess.PIPE).decode().strip()
        return path,recipient
    def cli(action,values,expected=0):
        result=subprocess.run([sys.executable,'scripts/device_journal_backup.py',action,*map(str,values)],capture_output=True,timeout=120)
        with private_file(work/(uuid.uuid4().hex+'.log')) as log:log.write(result.stdout+result.stderr)
        assert result.returncode==expected,'Device recovery command failed; private diagnostics retained'
        assert canary.encode() not in result.stdout+result.stderr
    def archive(name,wire):
        directory=output(name); parts=output(name+'-parts'); selected=[]
        for i,offset in enumerate(range(0,len(wire),recovery.CHUNK_BYTES)):
            filename='journal-'+str(i).zfill(3)+'.json.part';path=parts/filename
            with private_file(path) as target:target.write(wire[offset:offset+recovery.CHUNK_BYTES])
            selected.append((filename,path))
        material.backup(age,selected,recipient,directory);return directory
    def inspect(source,value):
        with sqlite3.connect((source/'journal/journal.sqlite').as_uri()+'?mode=ro',uri=True) as db:
            assert db.execute('PRAGMA integrity_check').fetchall()==[('ok',)]
            assert db.execute('SELECT revision,state FROM checkpoint').fetchone()==(value['revision'],base64.b64decode(value['stateBase64']))
            assert db.execute('SELECT mode,serial,confirmed_serial,digest FROM durability').fetchone()==('LOCAL',value['serial'],-1,None)
            actual={r:(n,c,e) for r,n,c,e in db.execute('SELECT id,received,committed,ended FROM route')}
            assert actual=={r['routeId']:(r['received'],r['committed'],int(r['ended'])) for r in value['routes']}
            rows=list(db.execute('SELECT route_id,sequence,wire FROM frame ORDER BY route_id,sequence'))
            assert rows==sorted((r['routeId'],f['sequence'],recovery.encode(f)) for r in value['routes'] for f in r['frames'])
    def deny_open(source,selected=bindings):
        path=source/'journal/journal.sqlite';before=hashlib.sha256(path.read_bytes()).digest()
        try:Journal(source/'journal',[],selected)
        except JournalError as error:assert str(error)=='Stream journal recovery requires explicit activation'
        else:raise AssertionError('Restored producer opened without activation')
        assert hashlib.sha256(path.read_bytes()).digest()==before
    def stop(process,event=None):
        if event is not None:event.set()
        process.join(3)
        if process.is_alive():process.kill();process.join(3)
        assert not process.is_alive()
    code=1
    try:
        identity,recipient=key('recovery.key');wrong,_=key('wrong.key')
        source=output('source')
        with Journal(source/'journal',[],bindings,create=True) as journal:
            for n in range(3):journal.commit(n,[],(canary+':'+str(n)).encode(),[Emission(b.route_id,str(n).encode(),'application/json') for b in bindings])
            journal.acknowledge(bindings[0],2);journal.acknowledge(bindings[1],1)
            journal.commit(3,[],canary.encode(),[Emission(b.route_id,b'',None,'END') for b in bindings])
            original=recovery.capture(source,5); value,_,_=recovery.validate(original)
            before=journal.snapshot_serial,journal.checkpoint(),journal.outgoing()
            saved=work/'encrypted'
            cli('backup',['--source',source,'--recipient',recipient,'--output',saved])
            assert before==(journal.snapshot_serial,journal.checkpoint(),journal.outgoing())
        assert set(p.name for p in saved.iterdir())=={'payload.age','manifest.json','journal-backup.json'}
        assert canary.encode() not in (saved/'payload.age').read_bytes()
        assert all(canary not in p.read_text() and actor.id not in p.read_text() for p in saved.glob('*.json'))
        passed('actual-owned-journal-stays-open-and-unchanged-while-private-fanout-state-frames-and-end-are-encrypted')

        shutil.rmtree(source)
        restored=work/'restored';cli('restore',['--input',saved,'--identity',identity,'--output',restored])
        inspect(restored/'source',value);deny_open(restored/'source')
        assert all(p.stat().st_mode&0o077==0 for p in restored.rglob('*'))
        passed('source-volume-loss-restores-exact-cursors-state-pending-frames-and-end-into-a-private-quarantined-journal')

        receipt=(restored/'restore-report.json').read_bytes();cipher=(saved/'payload.age').read_bytes()
        cli('restore',['--input',saved,'--identity',identity,'--output',restored],1)
        cli('backup',['--source',restored/'source','--recipient',recipient,'--output',work/'quarantine-copy'],1)
        assert receipt==(restored/'restore-report.json').read_bytes() and cipher==(saved/'payload.age').read_bytes()
        marker=restored/'source/journal/recovery.json';marker.unlink();marker.symlink_to(work/'missing-marker')
        deny_open(restored/'source');marker.unlink()
        material.write_json(marker,{'activated':False})
        passed('existing-destinations-quarantined-backups-and-dangling-quarantine-markers-cannot-bypass-activation')

        denied=work/'wrong-key';cli('restore',['--input',saved,'--identity',wrong,'--output',denied],1)
        assert not (denied/'source').exists() and not list(denied.glob('recovery-*'))
        bad=output('bad-cipher');shutil.copyfile(saved/'manifest.json',bad/'manifest.json');(bad/'manifest.json').chmod(0o600)
        with private_file(bad/'payload.age') as target:target.write(cipher[:-1]+bytes([cipher[-1]^1]))
        cli('restore',['--input',bad,'--identity',identity,'--output',work/'bad-cipher-out'],1)
        assert not (work/'bad-cipher-out/source').exists()
        passed('wrong-identity-and-damaged-ciphertext-leave-no-published-journal-or-temporary-plaintext')

        live=output('live');ready=mp.Event();done=mp.Event()
        writer=mp.Process(target=live_source,args=(live,bindings,ready,done));writer.start();processes.append(writer)
        assert ready.wait(5)
        revisions=[]
        for _ in range(8):
            snapshot=recovery.validate(recovery.capture(live,5))[0]
            n=int(base64.b64decode(snapshot['stateBase64']));assert snapshot['revision']==n
            assert all(r['received']==n and r['received']-r['committed']<=3 for r in snapshot['routes'])
            revisions.append(n);time.sleep(.01)
        assert revisions[-1]>revisions[0]
        live_backup=output('live-backup');recovery.backup(age,live,recipient,live_backup,5)
        assert writer.is_alive();stop(writer,done)
        live_restore=output('live-restore');recovery.restore(age,live_backup,identity,live_restore);deny_open(live_restore/'source')
        passed('actual-separate-process-writer-yields-eight-consistent-snapshots-and-encrypted-backup-without-stealing-owner-lock')

        ready=mp.Event();done=mp.Event();locker=mp.Process(target=locked_source,args=(live,ready,done))
        locker.start();processes.append(locker);assert ready.wait(5);started=time.monotonic()
        try:recovery.capture(live,1)
        except sqlite3.OperationalError:pass
        else:raise AssertionError('Exclusive writer lock was ignored')
        assert time.monotonic()-started<3;stop(locker,done)
        passed('real-exclusive-sqlite-writer-causes-bounded-read-timeout-without-source-mutation')

        for name,inputs,outputs,durability in [('task',[],[replace(bindings[0],producer=Producer('TASK_ATTEMPT',str(uuid.uuid4()),1))],'LOCAL'),
                ('input',[bindings[0]],[bindings[1]],'LOCAL'),('external',[],bindings,'EXTERNAL'),
                ('mixed-actors',[],[bindings[0],replace(bindings[1],producer=replace(actor,id=str(uuid.uuid4())))],'LOCAL')]:
            candidate=output(name)
            with Journal(candidate/'journal',inputs,outputs,create=True,durability=durability):pass
            cli('backup',['--source',candidate,'--recipient',recipient,'--output',work/(name+'-rejected')],1)
            assert not (work/(name+'-rejected')/'manifest.json').exists()
        passed('task-input-and-external-checkpoint-journals-cannot-be-reinterpreted-as-local-device-sources')

        for name,mutate in [('sequence',lambda v:v['routes'][0]['frames'][0].update(sequence=100)),
                ('cursor',lambda v:v['routes'][0].update(committed=100)),
                ('actor',lambda v:v['manifest']['outputs'][0]['producer'].update(kind='TASK_ATTEMPT')),
                ('completion',lambda v:v.update(completion={'runId':str(uuid.uuid4())}))]:
            changed=copy.deepcopy(value);mutate(changed);bundle=archive('invalid-'+name,recovery.encode(changed));target=work/(name+'-restore')
            cli('restore',['--input',bundle,'--identity',identity,'--output',target],1)
            assert not (target/'source').exists() and not list(target.glob('recovery-*'))
        passed('authenticated-encrypted-but-invalid-sequences-cursors-actors-and-completion-contracts-are-rejected-before-publication')

        terminal=output('terminal');run_id=str(uuid.uuid4())
        with Journal(terminal/'journal',[],bindings,create=True) as journal:
            journal.commit(0,[],b'finished-adapter',[Emission(b.route_id,b'',None,'END') for b in bindings])
            for b in bindings:journal.acknowledge(b,1)
            assignments={b.route_id:SimpleNamespace(generation_id=str(uuid.uuid4()),binding=b) for b in bindings}
            completion.write(terminal,run_id,actor,assignments,journal)
            wire=recovery.capture(terminal,5)
        bundle=output('terminal-backup');recovery.backup(age,terminal,recipient,bundle)
        target=output('terminal-restored');result=recovery.restore(age,bundle,identity,target)
        assert result['completionIntent'] and result['pendingFrames']==0
        assert material.json_value((target/'source/completion.json').read_bytes())==material.json_value((terminal/'completion.json').read_bytes())
        inspect(target/'source',recovery.validate(wire)[0]);deny_open(target/'source')
        passed('settled-source-completion-intent-and-terminal-cursors-survive-without-issuing-a-new-grant')

        previous=(terminal/'completion.json').read_bytes();reader=recovery.completion_bytes;calls=[]
        def changing(directory):
            raw=reader(directory);calls.append(1)
            if len(calls)==1:
                changed=material.json_value(raw);changed['routes'][0]['generationId']=str(uuid.uuid4())
                replacement=directory/'changed-completion.json';material.write_json(replacement,changed)
                os.replace(replacement,directory/'completion.json')
            return raw
        with patch.object(recovery,'completion_bytes',changing):
            try:recovery.capture(terminal,5)
            except JournalError as error:assert str(error)=='Completion intent changed during snapshot; retry'
            else:raise AssertionError('Racing completion file was accepted')
        (terminal/'completion.json').write_bytes(previous)
        assert recovery.capture(terminal,5)==wire
        passed('actual-completion-file-replacement-across-the-read-snapshot-is-refused-without-changing-the-journal')

        link=output('linked-source');(link/'journal').symlink_to(live/'journal',target_is_directory=True)
        cli('backup',['--source',link,'--recipient',recipient,'--output',work/'linked-rejected'],1)
        path=live/'journal/journal.sqlite';before=path.read_bytes()
        with sqlite3.connect(path) as db:db.execute('UPDATE frame SET sequence=sequence+100')
        cli('backup',['--source',live,'--recipient',recipient,'--output',work/'index-rejected'],1)
        assert not (work/'index-rejected/manifest.json').exists()
        path.write_bytes(before)
        passed('linked-source-directories-and-frame-wire-index-corruption-cannot-produce-a-backup-manifest')

        large=output('large');binding=bindings[0]
        with Journal(large/'journal',[],[binding],create=True) as journal:
            for n in range(25):journal.commit(n,[],str(n+1).encode(),[Emission(binding.route_id,b'x'*262144,'application/octet-stream')])
            wire=recovery.capture(large,5)
        assert len(wire)>recovery.CHUNK_BYTES
        bundle=output('large-backup');recovery.backup(age,large,recipient,bundle)
        assert material.json_value((bundle/'manifest.json').read_bytes())['fileCount']==2
        target=output('large-restored');recovery.restore(age,bundle,identity,target)
        inspect(target/'source',recovery.validate(wire)[0]);deny_open(target/'source',[binding])
        passed('snapshot-larger-than-eight-mib-is-split-encrypted-and-restored-without-truncating-pending-payloads')

        for after in (False,True):
            target=work/('crash-after' if after else 'crash-before');ready=mp.Event()
            process=mp.Process(target=crash_restore,args=(saved,identity,target,ready,after));process.start();processes.append(process)
            assert ready.wait(10);os.kill(process.pid,signal.SIGKILL);process.join(5);assert process.exitcode==-signal.SIGKILL
            assert (target/'source').exists()==after and not (target/'restore-report.json').exists()
            if after:inspect(target/'source',value);deny_open(target/'source')
        passed('actual-sigkill-before-and-after-directory-publication-never-exposes-an-automatically-runnable-restored-source')

        code=0;report.update(status='PASS',concurrentSnapshots=8,writerAdvanced=True,largeSnapshotBytes=len(wire),sourceLossVerified=True,
            quarantineEnforced=True,crashBoundaries=2)
    except Exception as error:
        with private_file(work/'failure.log','w') as log:traceback.print_exc(file=log)
        report.update(status='FAIL',failureType=type(error).__name__)
        print('FAIL: Device journal backup; private diagnostics retained')
    finally:
        for process in processes:
            if process.is_alive():process.kill()
            process.join(5)
        report['ownedProcessesStopped']=all(not p.is_alive() for p in processes)
        args.report.parent.mkdir(mode=0o700,parents=True,exist_ok=True);material.write_json(args.report,report)
    print(report['status']+': '+str(len(report['cases']))+' Device journal cases; private diagnostics '+str(work))
    return code


if __name__=='__main__':raise SystemExit(main())
