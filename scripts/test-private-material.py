"""Real age encryption and OpenSSL key material; refusal, integrity and safe restore boundaries."""
import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid
import zipfile
from unittest.mock import patch

import age_tools
from age_tools import VERSION,tools
from postgres_backup import ROOT,private_file
from private_material_backup import Age,backup,write_json


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,default=ROOT/'.tools/private-material-test.json')
    args=parser.parse_args()
    work=ROOT/'.tools'/('private-material-test-'+uuid.uuid4().hex);work.mkdir(mode=0o700)
    report={'status':'RUNNING','scope':'encrypted-static-recovery-files','sourceMode':'SYNTHETIC',
        'ageVersion':VERSION,'cases':[],'activated':False}
    age=Age();paths=tools()

    def run(command,expected=0):
        result=subprocess.run(list(map(str,command)),capture_output=True,timeout=150)
        assert result.returncode==expected,'Unexpected private-material command exit; values suppressed'
        return result

    def key(name,pq=False):
        identity=work/(name+'.key')
        with private_file(identity) as target:
            result=subprocess.run([str(paths['age-keygen']),*(['-pq'] if pq else [])],stdout=target,stderr=subprocess.PIPE,timeout=30)
        assert result.returncode==0
        return identity,run([paths['age-keygen'],'-y',identity]).stdout.decode().strip()

    def cli(action,values,expected=0):
        result=run([sys.executable,'scripts/private_material_backup.py',action,*values],expected)
        assert canary.encode() not in result.stdout+result.stderr
        return result

    def passed(name): report['cases'].append(name);print('PASS: '+name,flush=True)

    def static_files(directory,values):
        directory.mkdir(mode=0o700)
        selected=[]
        for name,data in values.items():
            path=directory/str(len(selected))
            with private_file(path) as out:out.write(data)
            selected.append((name,path))
        return selected

    def options(selected):
        return [value for name,path in selected for value in ['--file',name+'='+str(path)]]

    code=1
    try:
        identity,recipient=key('recipient');wrong_identity,_=key('wrong')
        canary='PRIVATE-MATERIAL-CANARY-'+secrets.token_hex(24)
        run(['openssl','req','-x509','-nodes','-newkey','rsa:2048','-subj','/CN=recovery-private-fixture',
            '-days','1','-keyout',work/'tls.key','-out',work/'tls.crt'])
        (work/'tls.key').chmod(0o600);(work/'tls.crt').chmod(0o600)
        values={'stream/tls.key':(work/'tls.key').read_bytes(),'stream/tls.crt':(work/'tls.crt').read_bytes(),
            'runner.key':secrets.token_hex(32).encode(),'storage.json':json.dumps({'secret':canary}).encode()}
        selected=static_files(work/'source',values)
        saved=work/'encrypted'
        cli('backup',[*options(selected),'--recipient',recipient,'--output',str(saved)])
        manifest=json.loads((saved/'manifest.json').read_text())
        assert manifest['fileCount']==4 and manifest['plaintextBytes']==sum(map(len,values.values()))
        assert set(p.name for p in saved.iterdir())=={'payload.age','manifest.json'}
        assert canary.encode() not in (saved/'payload.age').read_bytes()
        assert all(name not in (saved/'manifest.json').read_text() for name in values)
        assert not manifest['activated'] and not manifest['originAuthenticated']
        for path in saved.iterdir(): assert stat.S_IMODE(path.stat().st_mode)==0o600
        passed('public-recipient-encryption-keeps-values-and-file-names-out-of-envelope')

        shutil.rmtree(work/'source')
        restored=work/'restored'
        cli('restore',['--input',str(saved),'--identity',str(identity),'--output',str(restored)])
        for name,expected in values.items():
            actual=restored/'files'/name;assert actual.read_bytes()==expected
            assert stat.S_IMODE(actual.stat().st_mode)==0o600
        assert all(stat.S_IMODE(p.stat().st_mode)==0o700 for p in restored.rglob('*') if p.is_dir())
        assert run(['openssl','pkey','-in',restored/'files/stream/tls.key','-pubout']).stdout==run([
            'openssl','x509','-in',restored/'files/stream/tls.crt','-pubkey','-noout']).stdout
        assert not list(restored.glob('plaintext-*'))
        passed('source-loss-restore-preserves-all-bytes-private-modes-and-real-TLS-key-pair')
        selected=[(name,restored/'files'/name) for name in values]

        original=(saved/'payload.age').read_bytes();receipt=(restored/'restore-report.json').read_bytes()
        cli('backup',[*options(selected),'--recipient',recipient,'--output',str(saved)],1)
        cli('restore',['--input',str(saved),'--identity',str(identity),'--output',str(restored)],1)
        assert (saved/'payload.age').read_bytes()==original and (restored/'restore-report.json').read_bytes()==receipt
        passed('existing-encrypted-backup-and-restored-material-are-never-overwritten')

        denied=work/'wrong-key'
        cli('restore',['--input',str(saved),'--identity',str(wrong_identity),'--output',str(denied)],1)
        assert not (denied/'files').exists() and not list(denied.glob('plaintext-*'))
        passed('wrong-private-identity-refused-with-no-restored-plaintext')

        changed=work/'changed-cipher';changed.mkdir(mode=0o700)
        tampered=bytearray(original);tampered[-17]^=1
        with private_file(changed/'payload.age') as out:out.write(tampered)
        outer=copy.deepcopy(manifest);outer['ciphertextSha256']=hashlib.sha256(tampered).hexdigest()
        write_json(changed/'manifest.json',outer)
        denied=work/'tamper-denied'
        cli('restore',['--input',str(changed),'--identity',str(identity),'--output',str(denied)],1)
        assert not (denied/'files').exists() and not list(denied.glob('plaintext-*'))
        passed('age-authentication-rejects-corrupted-payload-even-with-recomputed-outer-checksum')

        link=work/'link';link.symlink_to(selected[0][1]);hard=work/'hard';os.link(selected[1][1],hard)
        exposed=work/'exposed';exposed.write_bytes(b'fixture');exposed.chmod(0o644)
        fifo=work/'fifo';os.mkfifo(fifo,0o600)
        for index,path in enumerate([link,hard,exposed,fifo]):
            out=work/('unsafe-'+str(index))
            cli('backup',['--file','fixture='+str(path),'--recipient',recipient,'--output',str(out)],1)
            assert not (out/'manifest.json').exists()
        hard.unlink()
        key_link=work/'identity-link';key_link.symlink_to(identity)
        cli('restore',['--input',str(saved),'--identity',str(key_link),'--output',str(work/'key-link-denied')],1)
        install_root=work/'install-root';install_root.mkdir(mode=0o700)
        (install_root/'.tools').mkdir(mode=0o700)
        install_target=work/'install-target';install_target.mkdir(mode=0o700)
        (install_root/'.tools/age-cache').symlink_to(install_target,target_is_directory=True)
        with patch.object(age_tools,'ROOT',install_root):
            try: tools(install=True)
            except ValueError: pass
            else: raise AssertionError('Installer followed cache directory link')
        assert not list(install_target.iterdir())
        passed('links-public-files-FIFO-and-linked-installer-directory-refused')

        for index,names in enumerate([['../escape'],['/absolute'],['a','a'],['a','a/b']]):
            chosen=[(name,selected[i%len(selected)][1]) for i,name in enumerate(names)]
            out=work/('bad-name-'+str(index))
            cli('backup',[*options(chosen),'--recipient',recipient,'--output',str(out)],1)
            assert not (out/'manifest.json').exists()
        passed('source-name-traversal-duplicates-and-file-directory-collisions-refused')

        # Construct authenticated age files with hostile inner archives to test restore validation itself.
        sibling=work/'escape';sibling.write_bytes(b'keep-existing')
        for index,attack in enumerate(['traversal','hash','extra','duplicate','symlink','compressed']):
            raw=io.BytesIO();entry={'name':'../escape' if attack=='traversal' else 'safe',
                'member':'files/000000','bytes':3,'sha256':hashlib.sha256(b'bad' if attack=='hash' else b'abc').hexdigest()}
            inner={'formatVersion':1,'scope':'static-private-recovery-files','bundleId':manifest['bundleId'],'files':[entry]}
            with zipfile.ZipFile(raw,'w',compression=zipfile.ZIP_DEFLATED if attack=='compressed' else zipfile.ZIP_STORED) as archive:
                member=zipfile.ZipInfo('files/000000');member.external_attr=(stat.S_IFLNK|0o600)<<16 if attack=='symlink' else 0o600<<16
                archive.writestr(member,b'abc');archive.writestr('manifest.json',json.dumps(inner))
                if attack=='extra':archive.writestr('unexpected',b'abc')
                if attack=='duplicate':
                    import warnings
                    with warnings.catch_warnings():
                        warnings.simplefilter('ignore',UserWarning);archive.writestr('files/000000',b'abc')
            raw.seek(0);bundle=work/('hostile-'+str(index));bundle.mkdir(mode=0o700)
            # Real subprocess encryption needs a file descriptor, not an in-memory buffer.
            with tempfile.TemporaryFile() as source,private_file(bundle/'payload.age') as destination:
                source.write(raw.getvalue());source.seek(0);age.encrypt(source,destination,recipient)
            payload=(bundle/'payload.age').read_bytes()
            envelope={**manifest,'fileCount':1,'plaintextBytes':3,'ciphertextSha256':hashlib.sha256(payload).hexdigest(),'ciphertextBytes':len(payload)}
            write_json(bundle/'manifest.json',envelope)
            out=work/('hostile-denied-'+str(index))
            cli('restore',['--input',str(bundle),'--identity',str(identity),'--output',str(out)],1)
            assert not (out/'files').exists() and not list(out.glob('plaintext-*'))
        assert sibling.read_bytes()==b'keep-existing'
        passed('authenticated-hostile-archives-cannot-escape-overwrite-or-publish-invalid-material')

        mutable=work/'mutable'
        with private_file(mutable) as out:out.write(b'before')
        class ChangedSource(Age):
            def encrypt(self,source,destination,recipient):
                super().encrypt(source,destination,recipient);mutable.write_bytes(b'after')
        output=work/'changed-source';output.mkdir(mode=0o700)
        try: backup(ChangedSource(),[('key',mutable)],recipient,output)
        except ValueError: pass
        else: raise AssertionError('Changed source accepted')
        assert not (output/'manifest.json').exists() and not list(output.glob('plaintext-*'))
        passed('source-change-during-real-encryption-prevents-success-manifest')

        pq_identity,pq_recipient=key('pq',True)
        pq=work/'pq-encrypted';pq_restore=work/'pq-restored'
        cli('backup',[*options(selected),'--recipient',pq_recipient,'--output',str(pq)])
        cli('restore',['--input',str(pq),'--identity',str(pq_identity),'--output',str(pq_restore)])
        assert all((pq_restore/'files'/name).read_bytes()==value for name,value in values.items())
        passed('native-post-quantum-recipient-and-identity-roundtrip')
        report.update(fileCount=4,verifiedBytes=sum(map(len,values.values())),sourceRemoved=True,
            nativeRecipients=['X25519','ML-KEM-768-X25519'],tlsKeyPairVerified=True,
            corruptionWithUpdatedChecksumRejected=True,hostileArchiveVariants=6,temporaryPlaintextRemoved=True)
        code=0
    except Exception as error:
        import traceback
        report['failureType']=type(error).__name__
        with private_file(work/'failure.log','w') as output:traceback.print_exc(file=output)
        print('FAIL: private recovery material test; values suppressed',flush=True)
    finally:
        report['status']='PASS' if code==0 else 'FAIL'
        args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2)+'\n')
        print(report['status']+': '+str(len(report['cases']))+' private material cases; synthetic private fixtures '+str(work))
    return code


if __name__=='__main__': raise SystemExit(main())
