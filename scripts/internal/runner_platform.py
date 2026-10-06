"""Record native Runner test provenance and publish only the two tested platform manifests."""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import subprocess

from image_identity import digest,manifest,platforms,SINGLE,INDEX

REPOSITORY='ghcr.io/dsa04156/edgeai-runner'
ARCHITECTURES={'amd64':'x86_64','arm64':'aarch64'}
RUNNER_INPUTS=['runner/', 'scripts/test/test-runner.sh', 'scripts/test/test-stream.sh',
    'scripts/collect-evidence.sh', 'scripts/lib.sh']


def needs_build(release, source, force=False):
    if force:return True
    try:
        previous=revision(release.get('runnerSourceRevision',release['sourceRevision']))
        digest(release['runnerDigest'])
        expected=release['runnerPlatformDigests']
        if set(expected)!={'linux/amd64','linux/arm64'} or len(set(expected.values()))!=2:return True
        for value in expected.values():digest(value)
    except (KeyError,ValueError,TypeError):return True
    ancestor=subprocess.run(['git','merge-base','--is-ancestor',previous,source],capture_output=True)
    if ancestor.returncode:return True
    changed=call(['git','diff','--name-only',previous,source,'--',*RUNNER_INPUTS])
    return bool(changed.strip())


def plan(args):
    release=json.loads(args.release.read_text()) if args.release.exists() else {}
    rebuild=needs_build(release,args.revision,args.force)
    if 'GITHUB_OUTPUT' in os.environ:
        with open(os.environ['GITHUB_OUTPUT'],'a') as out:out.write('rebuild='+str(rebuild).lower()+'\n')
    return {'scope':'runner-build-plan','sourceRevision':args.revision,'rebuild':rebuild}


def reuse(args):
    release=json.loads(args.release.read_text())
    if needs_build(release,args.revision):raise ValueError('Runner inputs changed; native builds are required')
    index=digest(release['runnerDigest']);expected=release['runnerPlatformDigests']
    if platforms(manifest(REPOSITORY+'@'+index))!=expected:
        raise ValueError('Retained Runner index differs from its verified platform digests')
    source=revision(release.get('runnerSourceRevision',release['sourceRevision']))
    if 'GITHUB_OUTPUT' in os.environ:
        with open(os.environ['GITHUB_OUTPUT'],'a') as out:
            out.write('runner_digest='+index+'\nrunner_platform_digests='+json.dumps(expected,separators=(',',':'))+'\nrunner_source='+source+'\n')
    return {'scope':'reused-runner-platform-index','status':'PASS','sourceRevision':source,
        'runnerDigest':index,'platformDigests':expected}


def call(command):
    result=subprocess.run(command,capture_output=True,text=True,timeout=180)
    if result.returncode:raise RuntimeError('Runner image operation failed; upstream diagnostics suppressed')
    return result.stdout


def revision(value):
    if not re.fullmatch(r'[0-9a-f]{40}',value):raise ValueError('Full source revision required')
    return value


def test_results(directory):
    found={}
    for path in directory.rglob('result.json'):
        row=json.loads(path.read_text());name=row['testId']
        if name not in ('runner-container','stream-mqtt'):continue
        if name in found or row['status']!='PASS' or row['exitCode']!=0:raise ValueError('Native Runner test evidence is incomplete')
        log=path.with_name('output.log').read_text()
        counts=re.findall(r'Ran (\d+) tests in [\d.]+s',log)
        if len(counts)!=1 or re.search(r'\bskipped\b',log,re.I):raise ValueError('Native Runner tests must execute without skips')
        found[name]=int(counts[0])
    if found!={'runner-container':111,'stream-mqtt':112}:raise ValueError('Required native Runner test cases were not all verified')
    return found


def native(args):
    if platform.system()!='Linux' or platform.machine()!=ARCHITECTURES[args.architecture]:
        raise ValueError('Native Linux host architecture differs from the test target')
    info,=json.loads(call(['docker','image','inspect',args.image]))
    if (info['Os']!='linux' or info['Architecture']!=args.architecture or
            info['Config']['Labels'].get('org.opencontainers.image.revision')!=args.revision):
        raise ValueError('Tested image platform/source label differs')
    machine=call(['docker','run','--rm','--network','none','--entrypoint','python3',args.image,
        '-c','import platform;print(platform.machine())']).strip()
    if machine!=ARCHITECTURES[args.architecture]:raise ValueError('Actual container architecture differs')
    record={'formatVersion':1,'scope':'native-runner-platform','sourceRevision':args.revision,
        'platform':'linux/'+args.architecture,'hostMachine':platform.machine(),'containerMachine':machine,
        'imageConfigDigest':digest(info['Id']),'tests':test_results(args.evidence),'publishedDigest':None}
    if args.published:
        refs=[ref for ref in info.get('RepoDigests',[]) if ref.startswith(REPOSITORY+'@')]
        if len(refs)!=1:raise ValueError('Exact tested image was not published once to the Runner repository')
        value=manifest(refs[0])
        if value['mediaType'] not in SINGLE:raise ValueError('Native publication must be a single platform manifest')
        record['publishedDigest']=digest(value['digest'])
    return record


def selected(records,source):
    if len(records)!=2:raise ValueError('Exactly two independently verified native platforms required')
    result={}
    for row in records:
        key=row.get('platform');architecture=(key or '').removeprefix('linux/')
        if (row.get('formatVersion')!=1 or row.get('scope')!='native-runner-platform' or
                row.get('sourceRevision')!=source or architecture not in ARCHITECTURES or key!='linux/'+architecture or
                row.get('hostMachine')!=ARCHITECTURES[architecture] or row.get('containerMachine')!=ARCHITECTURES[architecture] or
                row.get('tests')!={'runner-container':111,'stream-mqtt':112} or key in result):
            raise ValueError('Native Runner provenance differs from the selected source or platforms')
        digest(row.get('imageConfigDigest'));result[key]=digest(row.get('publishedDigest'))
    if set(result)!={'linux/amd64','linux/arm64'} or len(set(result.values()))!=2:
        raise ValueError('Distinct tested amd64 and arm64 manifests required')
    return dict(sorted(result.items()))


def publish(args):
    records=[json.loads(p.read_text()) for p in args.directory.rglob('runner-platform.json')]
    expected=selected(records,args.revision)
    for key,value in expected.items():
        row=manifest(REPOSITORY+'@'+value)
        if row['mediaType'] not in SINGLE:raise ValueError('Native source unexpectedly became an index')
    tag=REPOSITORY+':sha-'+args.revision
    call(['docker','buildx','imagetools','create','--tag',tag,*[REPOSITORY+'@'+v for v in expected.values()]])
    value=json.loads(call(['docker','buildx','imagetools','inspect',tag,'--format','{{json .Manifest}}']))
    index=digest(value.get('digest'))
    if value.get('mediaType') not in INDEX or platforms(manifest(REPOSITORY+'@'+index))!=expected:
        raise ValueError('Published index does not contain exactly the tested platform digests')
    if 'GITHUB_OUTPUT' in os.environ:
        with open(os.environ['GITHUB_OUTPUT'],'a') as out:
            out.write('runner_digest='+index+'\nrunner_platform_digests='+json.dumps(expected,separators=(',',':'))+'\nrunner_source='+args.revision+'\n')
    return {'formatVersion':1,'scope':'verified-runner-platform-index','status':'PASS','sourceRevision':args.revision,
        'runnerDigest':index,'platformDigests':expected,'nativeTests':records}


def main():
    parser=argparse.ArgumentParser(description=__doc__);commands=parser.add_subparsers(dest='action',required=True)
    record=commands.add_parser('native');record.add_argument('--architecture',choices=ARCHITECTURES,required=True)
    record.add_argument('--image',default='edgeai-runner:verify');record.add_argument('--evidence',type=Path,default=Path('docs/evidence/runs'))
    record.add_argument('--published',action='store_true')
    index=commands.add_parser('publish');index.add_argument('--directory',type=Path,required=True)
    selection=commands.add_parser('plan');selection.add_argument('--force',action='store_true')
    retained=commands.add_parser('reuse')
    for command in (selection,retained):
        command.add_argument('--release',type=Path,default=Path('deploy/kubernetes/overlays/dev/release.json'))
    for command in (record,index,selection,retained):
        command.add_argument('--revision',type=revision,required=True);command.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();result={'native':native,'publish':publish,'plan':plan,'reuse':reuse}[args.action](args)
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n')
    print('PASS: '+result['scope']+'; exact source '+result['sourceRevision'])


if __name__=='__main__':main()
