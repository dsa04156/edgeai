"""Encrypt explicitly selected static recovery files and restore them only into a new private directory."""
import argparse
from contextlib import ExitStack
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path,PurePosixPath
import re
import stat
import subprocess
import tempfile
import uuid
import zipfile

from age_tools import tools,VERSION
from postgres_backup import ROOT,Blocked,private_file

MAX_FILES=1024
MAX_FILE_BYTES=16*1024*1024
MAX_TOTAL_BYTES=256*1024*1024
MAX_ARCHIVE_BYTES=MAX_TOTAL_BYTES+2*1024*1024
MAX_CIPHER_BYTES=MAX_ARCHIVE_BYTES+2*1024*1024
ALPHABET='023456789acdefghjklmnpqrstuvwxyz'
RECIPIENT=re.compile(r'(?:age1['+ALPHABET+r']{58}|age1pq1['+ALPHABET+r']{100,4096})')
# Native PQ identities encode a compact seed; age validates its structure/checksum.
IDENTITY=re.compile(r'(?:AGE-SECRET-KEY-1[0-9A-Z]{30,120}|AGE-SECRET-KEY-PQ-1[0-9A-Z]{30,8192})')


def timestamp(): return datetime.now(timezone.utc).isoformat()


def stamp(info):
    return info.st_dev,info.st_ino,info.st_size,info.st_mtime_ns,info.st_ctime_ns,info.st_mode,info.st_uid,info.st_nlink


def private_input(path,limit):
    descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    try:
        info=os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode & 0o077
                or info.st_nlink!=1 or info.st_size>limit):
            raise ValueError('Input must be an owned, private, bounded regular file without links')
        return os.fdopen(descriptor,'rb')
    except Exception:
        os.close(descriptor);raise


def json_value(raw):
    def pairs(values):
        result={}
        for key,value in values:
            if key in result: raise ValueError('Duplicate manifest property')
            result[key]=value
        return result
    return json.loads(raw,object_pairs_hook=pairs)


def names_valid(names):
    if not 1<=len(names)<=MAX_FILES or len(set(names))!=len(names): raise ValueError('Invalid file count or duplicate name')
    for name in names:
        if (not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9_./-]{1,180}',name)
                or name.startswith('/') or any(p in ('','.','..') for p in name.split('/'))):
            raise ValueError('Invalid relative recovery file name')
        if any(str(parent) in names for parent in PurePosixPath(name).parents if str(parent)!='.'):
            raise ValueError('Recovery file and directory names overlap')


class Age:
    def __init__(self): self.paths=tools()

    def encrypt(self,source,destination,recipient):
        if not isinstance(recipient,str) or RECIPIENT.fullmatch(recipient) is None:
            raise ValueError('A native age public recipient is required')
        result=subprocess.run([str(self.paths['age']),'--encrypt','--recipient',recipient],
            stdin=source,stdout=destination,stderr=subprocess.PIPE,timeout=120)
        if result.returncode: raise ValueError('age encryption failed; private diagnostics suppressed')

    def decrypt(self,source,destination,identity,recipient):
        with private_input(identity,16384) as key:
            original=stamp(os.fstat(key.fileno()))
            lines=[line.strip() for line in key.read().decode('ascii').splitlines() if line.strip() and not line.startswith('#')]
            if len(lines)!=1 or IDENTITY.fullmatch(lines[0]) is None:
                raise ValueError('Exactly one native age identity is required; plugins and passphrases are not used')
            key.seek(0)
            inherited='/proc/self/fd/'+str(key.fileno())
            public=subprocess.run([str(self.paths['age-keygen']),'-y',inherited],capture_output=True,
                pass_fds=(key.fileno(),),timeout=30)
            if public.returncode or public.stdout.decode().strip()!=recipient:
                raise ValueError('Recovery identity does not match the selected recipient')
            key.seek(0)
            result=subprocess.run([str(self.paths['age']),'--decrypt','--identity',inherited],stdin=source,
                stdout=destination,stderr=subprocess.PIPE,pass_fds=(key.fileno(),),timeout=120)
            if result.returncode or original!=stamp(os.fstat(key.fileno())):
                raise ValueError('age authenticated decryption failed; private diagnostics suppressed')


def write_json(path,value):
    with private_file(path,'w') as output:
        json.dump(value,output,indent=2);output.write('\n');output.flush();os.fsync(output.fileno())


def backup(age,selected,recipient,output):
    names_valid([name for name,path in selected])
    if RECIPIENT.fullmatch(recipient) is None: raise ValueError('A native age public recipient is required')
    bundle_id=uuid.uuid4().hex
    with ExitStack() as stack:
        inputs=[]; total=0; inodes=set()
        for name,path in selected:
            source=stack.enter_context(private_input(path,MAX_FILE_BYTES)); info=os.fstat(source.fileno())
            if (info.st_dev,info.st_ino) in inodes: raise ValueError('Duplicate source file identity')
            inodes.add((info.st_dev,info.st_ino)); total+=info.st_size
            if total>MAX_TOTAL_BYTES: raise ValueError('Selected private material exceeds the size limit')
            inputs.append((name,Path(path),source,stamp(info)))
        with tempfile.TemporaryDirectory(prefix='plaintext-',dir=output) as temporary:
            archive_path=Path(temporary)/'payload.zip'
            manifest={'formatVersion':1,'scope':'static-private-recovery-files','bundleId':bundle_id,'files':[]}
            with private_file(archive_path) as destination,zipfile.ZipFile(destination,'w',compression=zipfile.ZIP_STORED) as archive:
                for index,(name,path,source,original) in enumerate(inputs):
                    member='files/'+str(index).zfill(6); digest=hashlib.sha256(); size=0
                    with archive.open(member,'w') as target:
                        while chunk:=source.read(1024*1024):
                            size+=len(chunk)
                            if size>MAX_FILE_BYTES: raise ValueError('Source changed or exceeds the size limit')
                            digest.update(chunk);target.write(chunk)
                    if size!=original[2] or stamp(os.fstat(source.fileno()))!=original:
                        raise ValueError('Static source changed while reading')
                    manifest['files'].append({'name':name,'member':member,'bytes':size,'sha256':digest.hexdigest()})
                archive.writestr('manifest.json',json.dumps(manifest,sort_keys=True).encode())
            with archive_path.open('rb') as source,private_file(output/'payload.age') as destination:
                age.encrypt(source,destination,recipient)
                destination.flush();os.fsync(destination.fileno())
            for name,path,source,original in inputs:
                if stamp(os.fstat(source.fileno()))!=original or stamp(path.stat(follow_symlinks=False))!=original:
                    raise ValueError('Static source changed before backup completion')
        with private_input(output/'payload.age',MAX_CIPHER_BYTES) as ciphertext:
            digest=hashlib.file_digest(ciphertext,'sha256').hexdigest(); size=os.fstat(ciphertext.fileno()).st_size
        envelope={'formatVersion':1,'scope':'encrypted-static-recovery-files','status':'ENCRYPTED_PRIVATE_FILES',
            'bundleId':bundle_id,'ageVersion':VERSION,'recipient':recipient,'ciphertextSha256':digest,
            'ciphertextBytes':size,'fileCount':len(inputs),'plaintextBytes':total,'createdAt':timestamp(),
            'activated':False,'originAuthenticated':False}
        write_json(output/'manifest.json',envelope)
        return envelope


def validate_archive(archive,envelope):
    infos=archive.infolist()
    if len(infos)!=envelope['fileCount']+1 or len({i.filename for i in infos})!=len(infos):
        raise ValueError('Encrypted archive has duplicate or unexpected entries')
    for info in infos:
        if (info.compress_type!=zipfile.ZIP_STORED or info.flag_bits & 1 or info.is_dir()
                or stat.S_IFMT(info.external_attr>>16) not in (0,stat.S_IFREG)
                or not 0<=info.file_size<=MAX_FILE_BYTES):
            raise ValueError('Encrypted archive entry is not a bounded regular stored file')
    info=archive.getinfo('manifest.json')
    if info.file_size>1024*1024: raise ValueError('Private manifest exceeds limit')
    manifest=json_value(archive.read('manifest.json'))
    if (manifest.get('formatVersion')!=1 or manifest.get('scope')!='static-private-recovery-files'
            or manifest.get('bundleId')!=envelope['bundleId'] or not isinstance(manifest.get('files'),list)
            or len(manifest['files'])!=envelope['fileCount']):
        raise ValueError('Encrypted archive identity differs')
    files=manifest['files'];names_valid([entry['name'] for entry in files])
    expected={'manifest.json'};total=0
    for index,entry in enumerate(files):
        member='files/'+str(index).zfill(6);expected.add(member)
        if (entry.get('member')!=member or type(entry.get('bytes')) is not int
                or not 0<=entry['bytes']<=MAX_FILE_BYTES or not re.fullmatch('[a-f0-9]{64}',entry.get('sha256',''))
                or archive.getinfo(member).file_size!=entry['bytes']):
            raise ValueError('Private file metadata differs')
        total+=entry['bytes']
        if total>MAX_TOTAL_BYTES: raise ValueError('Restored files exceed size limit')
        with archive.open(member) as source:
            if hashlib.file_digest(source,'sha256').hexdigest()!=entry['sha256']:
                raise ValueError('Private file bytes differ from encrypted manifest')
    if expected!={i.filename for i in infos} or total!=envelope['plaintextBytes']:
        raise ValueError('Private archive membership or length differs')
    return manifest


def restore(age,bundle,identity,output):
    with private_input(bundle/'manifest.json',1024*1024) as source: envelope=json_value(source.read())
    if (envelope.get('formatVersion')!=1 or envelope.get('scope')!='encrypted-static-recovery-files'
            or envelope.get('status')!='ENCRYPTED_PRIVATE_FILES' or envelope.get('activated') is not False
            or not re.fullmatch('[a-f0-9]{32}',envelope.get('bundleId',''))
            or RECIPIENT.fullmatch(envelope.get('recipient','')) is None
            or not re.fullmatch('[a-f0-9]{64}',envelope.get('ciphertextSha256',''))
            or type(envelope.get('fileCount')) is not int or not 1<=envelope['fileCount']<=MAX_FILES
            or type(envelope.get('plaintextBytes')) is not int or not 0<=envelope['plaintextBytes']<=MAX_TOTAL_BYTES
            or type(envelope.get('ciphertextBytes')) is not int or not 0<envelope['ciphertextBytes']<=MAX_CIPHER_BYTES):
        raise ValueError('Unsupported encrypted recovery manifest')
    with private_input(bundle/'payload.age',MAX_CIPHER_BYTES) as source:
        original=stamp(os.fstat(source.fileno()))
        if original[2]!=envelope['ciphertextBytes'] or hashlib.file_digest(source,'sha256').hexdigest()!=envelope['ciphertextSha256']:
            raise ValueError('Encrypted recovery file checksum differs')
        source.seek(0)
        with tempfile.TemporaryDirectory(prefix='plaintext-',dir=output) as temporary:
            archive_path=Path(temporary)/'payload.zip'
            with private_file(archive_path) as destination:
                age.decrypt(source,destination,identity,envelope['recipient'])
            if stamp(os.fstat(source.fileno()))!=original or archive_path.stat().st_size>MAX_ARCHIVE_BYTES:
                raise ValueError('Encrypted input changed or plaintext exceeds the limit')
            with zipfile.ZipFile(archive_path) as archive:
                manifest=validate_archive(archive,envelope)  # Validate all contents before publishing any restored file.
                material=Path(temporary)/'files';material.mkdir(mode=0o700)
                for entry in manifest['files']:
                    target=material/entry['name']
                    # mkdir(parents=True) can use a permissive default mode for intermediate directories.
                    parent=material
                    for part in PurePosixPath(entry['name']).parts[:-1]:
                        parent=parent/part;parent.mkdir(mode=0o700,exist_ok=True)
                    with archive.open(entry['member']) as source_file,private_file(target) as destination:
                        while chunk:=source_file.read(1024*1024): destination.write(chunk)
                        destination.flush();os.fsync(destination.fileno())
            if (output/'files').exists() or (output/'files').is_symlink(): raise ValueError('Existing restore destination refused')
            os.rename(material,output/'files')
    report={'formatVersion':1,'scope':'restored-static-recovery-files','status':'RESTORED_PRIVATE_FILES_ONLY',
        'bundleId':envelope['bundleId'],'ciphertextSha256':envelope['ciphertextSha256'],
        'fileCount':envelope['fileCount'],'verifiedBytes':envelope['plaintextBytes'],
        'activated':False,'originAuthenticated':False,'restoredAt':timestamp()}
    write_json(output/'restore-report.json',report)
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest='action',required=True)
    save=commands.add_parser('backup');save.add_argument('--file',action='append',required=True,help='relative-name=private-source-path')
    save.add_argument('--recipient',required=True);save.add_argument('--output',type=Path,required=True)
    load=commands.add_parser('restore');load.add_argument('--input',type=Path,required=True)
    load.add_argument('--identity',type=Path,required=True);load.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();created=False;output=args.output.absolute()
    try:
        age=Age()
        output.parent.mkdir(mode=0o700,parents=True,exist_ok=True);output.mkdir(mode=0o700,exist_ok=False);created=True
        if args.action=='backup':
            selected=[]
            for item in args.file:
                name,separator,path=item.partition('=')
                if not separator or not path: raise ValueError('Each file needs a relative name and private source path')
                selected.append((name,Path(path)))
            result=backup(age,selected,args.recipient,output)
        else: result=restore(age,args.input,args.identity,output)
        print(result['status']+': '+str(result['fileCount'])+' files; no existing material replaced or service activated')
        return 0
    except Exception as error:
        status='BLOCKED' if isinstance(error,Blocked) else 'FAIL'
        if created: write_json(output/'failure.json',{'status':status,'failureType':type(error).__name__,'activated':False})
        print(status+': private material '+args.action+' ('+type(error).__name__+'); secret values suppressed')
        return 2 if isinstance(error,Blocked) else 1


if __name__=='__main__': raise SystemExit(main())
