"""Pinned official age tools; extract only the two requested regular files, never archive paths."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import stat
import tarfile
import tempfile
import urllib.request

from postgres_backup import ROOT,Blocked,private_file

VERSION='v1.3.2'
ARCHIVES={
    'x86_64':('amd64','cbe24006683f8eb669266162894b9a522a1af52f2665fbc63a4bb032ed26ac10'),
    'aarch64':('arm64','6b8dc4333c53a5a57c9e5834e3a48f92605d7154014cd07269ff3327db5d37f4'),
}


def tools(install=False):
    if platform.system()!='Linux' or platform.machine() not in ARCHIVES:
        raise Blocked('Pinned age tools support Linux amd64 and arm64')
    architecture,checksum=ARCHIVES[platform.machine()]
    name='age-'+VERSION+'-linux-'+architecture+'.tar.gz'
    cache=ROOT/'.tools/age-cache'; archive=cache/name
    destination=ROOT/'.tools'/('age-'+VERSION)/architecture
    for directory in (ROOT/'.tools',cache,destination.parent,destination):
        if install:
            try: directory.mkdir(mode=0o700)
            except FileExistsError: pass
        try: info=directory.lstat()
        except FileNotFoundError: raise Blocked('Run scripts/dev/install-age.sh first')
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.getuid():
            raise ValueError('Age tool directories must be owned directories without links')
    if not archive.exists():
        if not install: raise Blocked('Run scripts/dev/install-age.sh first')
        with tempfile.TemporaryDirectory(prefix='download-',dir=cache) as directory:
            temporary=Path(directory)/name
            with private_file(temporary) as output,urllib.request.urlopen(
                    'https://github.com/FiloSottile/age/releases/download/'+VERSION+'/'+name,timeout=30) as response:
                while data:=response.read(1024*1024): output.write(data)
            if hashlib.sha256(temporary.read_bytes()).hexdigest()!=checksum:
                raise ValueError('Official age archive checksum differs')
            os.link(temporary,archive)
    with os.fdopen(os.open(archive,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode) or hashlib.file_digest(source,'sha256').hexdigest()!=checksum:
            raise ValueError('Cached age archive checksum differs; nothing was replaced')
        source.seek(0)
        with tarfile.open(fileobj=source,mode='r:gz') as package:
            paths={}
            for name in ('age','age-keygen'):
                member=package.getmember('age/'+name)
                if not member.isfile() or not 0<member.size<64*1024*1024: raise ValueError('Invalid age tool member')
                expected=package.extractfile(member).read()
                target=destination/name
                if not target.exists():
                    if not install: raise Blocked('Run scripts/dev/install-age.sh first')
                    with private_file(target) as output: output.write(expected)
                    target.chmod(0o700)
                with os.fdopen(os.open(target,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as binary:
                    info=os.fstat(binary.fileno())
                    if not stat.S_ISREG(info.st_mode) or not info.st_mode & stat.S_IXUSR or hashlib.file_digest(binary,'sha256').digest()!=hashlib.sha256(expected).digest():
                        raise ValueError('Installed age tool checksum differs; nothing was replaced')
                paths[name]=target
    return paths


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--install',action='store_true');args=parser.parse_args()
    try:
        tools(args.install)
        print('PASS: official age '+VERSION+' archive and both tool checksums verified')
    except Exception as error:
        print(('BLOCKED' if isinstance(error,Blocked) else 'FAIL')+': age tool verification ('+type(error).__name__+')')
        raise SystemExit(2 if isinstance(error,Blocked) else 1)
