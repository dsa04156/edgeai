"""Durably retire an original local Device journal and prove exclusive source ownership was released."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import os
from pathlib import Path
import stat
import time
import uuid

from postgres_backup import Blocked
import device_journal_backup as journals
import recovery_device_journal as comparison
import private_material_backup as material

SCOPE='local-device-source-retirement'


def boot_identity():
    return hashlib.sha256(Path('/proc/sys/kernel/random/boot_id').read_bytes()).hexdigest()


def identity(path):
    value=path.lstat()
    if not stat.S_ISREG(value.st_mode) or value.st_nlink!=1 or value.st_uid!=os.getuid() or stat.S_IMODE(value.st_mode)!=0o600:
        raise ValueError('Private single-link original source file required')
    return {'device':value.st_dev,'inode':value.st_ino,'uid':value.st_uid,'mode':stat.S_IMODE(value.st_mode)}


@contextmanager
def owner_lock(directory,deadline):
    path=directory/'journal/owner.lock';before=identity(path)
    fd=os.open(path,os.O_RDWR|os.O_NOFOLLOW)
    try:
        actual=os.fstat(fd)
        if (actual.st_dev,actual.st_ino)!=(before['device'],before['inode']):raise Blocked('Source owner lock changed')
        while True:
            try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB);break
            except BlockingIOError:
                if time.monotonic()>=deadline:raise Blocked('Original source owner has not released its journal')
                time.sleep(min(.02,max(.001,deadline-time.monotonic())))
        if identity(path)!=before:raise Blocked('Source owner lock changed')
        yield before
        if identity(path)!=before:raise Blocked('Source owner lock changed during verification')
    finally:os.close(fd)


def read_source(directory,timeout):
    wire=journals.capture(directory,timeout);value,bindings,_=journals.validate(wire)
    return value,bindings,hashlib.sha256(wire).hexdigest()


def intent(directory,recovery_id,bindings):
    if str(uuid.UUID(recovery_id))!=recovery_id:raise ValueError('Canonical recovery UUID required')
    config=[{'routeId':b.route_id,'generation':b.generation,'producer':b.producer.document()} for b in sorted(bindings,key=lambda b:b.route_id)]
    return {'formatVersion':1,'scope':SCOPE,'status':'SOURCE_RETIREMENT_REQUESTED','recoveryId':recovery_id,
        'hostBootSha256':boot_identity(),'sourceDirectory':str(directory.absolute()),
        'journalIdentity':identity(directory/'journal/journal.sqlite'),
        'ownerLockIdentity':identity(directory/'journal/owner.lock'),
        'bindingsSha256':hashlib.sha256(journals.encode(config)).hexdigest(),'activated':False}


def retire(directory,recovery_id,expected_bindings,expected_snapshot,timeout):
    if not 1<=timeout<=120:raise ValueError('Bounded source retirement timeout required')
    directory=directory.absolute();deadline=time.monotonic()+timeout
    _,bindings,_=read_source(directory,timeout)
    if set(bindings)!=set(expected_bindings):raise ValueError('Original source bindings differ from the selected restore')
    expected=intent(directory,recovery_id,bindings);path=directory/'journal/retirement.json'
    if os.path.lexists(path):
        if comparison.read_json(path)[0]!=expected:raise Blocked('Another source retirement intent is preserved')
    else:
        material.write_json(path,expected)
        fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(fd)
        finally:os.close(fd)
    with owner_lock(directory,deadline) as lock:
        marker,marker_sha=comparison.read_json(path)
        if marker!=expected or lock!=expected['ownerLockIdentity']:raise Blocked('Source retirement intent or owner lock changed')
        value,bindings,snapshot=read_source(directory,max(.001,deadline-time.monotonic()))
        if intent(directory,recovery_id,bindings)!=expected:raise Blocked('Source identity changed during retirement')
        report={'formatVersion':1,'scope':SCOPE,'status':'DEVICE_SOURCE_OWNER_RETIRED','recoveryId':recovery_id,
            'sourceDirectory':str(directory),'hostBootSha256':expected['hostBootSha256'],'journalIdentity':expected['journalIdentity'],
            'ownerLockIdentity':lock,'retirementMarkerSha256':marker_sha,'bindingsSha256':expected['bindingsSha256'],
            'snapshotSha256':snapshot,'suppliedRestoreSnapshotSha256':expected_snapshot,
            'snapshotMatchesSuppliedRestore':snapshot==expected_snapshot,'serial':value['serial'],'revision':value['revision'],
            'sourceJournalOwnerQuiescenceProven':True,'producerProcessQuiescenceProven':False,
            'globalQuiescenceProven':False,'activated':False,'retiredAt':datetime.now(timezone.utc).isoformat()}
        if comparison.read_json(path)[1]!=marker_sha:raise Blocked('Source retirement marker changed')
    return report


def verify(directory,receipt_path,recovery_id,expected_bindings,expected_snapshot):
    directory=directory.absolute();report,receipt_sha=comparison.read_json(receipt_path)
    if (report.get('formatVersion')!=1 or report.get('scope')!=SCOPE or report.get('status')!='DEVICE_SOURCE_OWNER_RETIRED' or
            report.get('recoveryId')!=recovery_id or report.get('sourceDirectory')!=str(directory) or
            report.get('hostBootSha256')!=boot_identity() or report.get('sourceJournalOwnerQuiescenceProven') is not True or
            report.get('activated') is not False or report.get('snapshotSha256')!=expected_snapshot):
        raise Blocked('Exact retired source and restored snapshot must match')
    journals.private_directory(directory);journals.private_directory(directory/'journal')
    with owner_lock(directory,time.monotonic()) as lock:
        marker,marker_sha=comparison.read_json(directory/'journal/retirement.json')
        value,bindings,snapshot=read_source(directory,30)
        expected=intent(directory,recovery_id,bindings)
        if (set(bindings)!=set(expected_bindings) or marker!=expected or lock!=report['ownerLockIdentity'] or
                expected['journalIdentity']!=report['journalIdentity'] or expected['bindingsSha256']!=report['bindingsSha256'] or
                marker_sha!=report['retirementMarkerSha256'] or snapshot!=expected_snapshot or
                comparison.read_json(receipt_path)[1]!=receipt_sha or
                comparison.read_json(directory/'journal/retirement.json')[1]!=marker_sha):
            raise Blocked('Retired source identity, data or marker changed')
    return {'scope':SCOPE,'recoveryId':recovery_id,'retirementReportSha256':receipt_sha,
        'retirementMarkerSha256':marker_sha,'snapshotSha256':snapshot,'sourceJournalOwnerQuiescenceProven':True,
        'producerProcessQuiescenceProven':False,'activated':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True);parser.add_argument('--journal-restore',type=Path,required=True)
    parser.add_argument('--recovery-id',required=True);parser.add_argument('--timeout',type=int,default=30)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();created=False
    try:
        args.output.mkdir(mode=0o700);created=True
        _,bindings,evidence=comparison.restored_journal(args.journal_restore)
        report=retire(args.source,args.recovery_id,bindings,evidence['journalSnapshotSha256'],args.timeout)
        material.write_json(args.output/'retirement-report.json',report)
        print(report['status']+': original journal cannot restart; retained data and source scope recorded')
        return 0
    except Exception as error:
        blocked=isinstance(error,Blocked)
        if created:material.write_json(args.output/'failure.json',{'status':'BLOCKED' if blocked else 'FAIL',
            'failureType':type(error).__name__,'activated':False,'sourceOwnerQuiescenceUnconfirmed':True})
        print(('BLOCKED' if blocked else 'FAIL')+': Device source retirement; existing intent is retained')
        return 2 if blocked else 1


if __name__=='__main__':raise SystemExit(main())
