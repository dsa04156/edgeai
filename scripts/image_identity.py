"""Verify a pinned image or one of its immutable platform manifests against a Pod imageID."""
from functools import lru_cache
import json
import re
import subprocess

DIGEST=re.compile(r'sha256:[0-9a-f]{64}')
SINGLE={'application/vnd.docker.distribution.manifest.v2+json','application/vnd.oci.image.manifest.v1+json'}
INDEX={'application/vnd.docker.distribution.manifest.list.v2+json','application/vnd.oci.image.index.v1+json'}


def digest(value):
    if not isinstance(value,str) or not DIGEST.fullmatch(value):raise ValueError('Immutable SHA-256 image digest required')
    return value


def pinned(reference):
    if not isinstance(reference,str) or not re.fullmatch(r'[a-z0-9][a-z0-9./:_-]+@sha256:[0-9a-f]{64}',reference):
        raise ValueError('Immutable image reference required')
    return reference.rsplit('@',1)


@lru_cache(maxsize=128)
def manifest(reference):
    repository,expected=pinned(reference)
    result=subprocess.run(['docker','buildx','imagetools','inspect',reference,'--format','{{json .Manifest}}'],
        capture_output=True,text=True,timeout=60)
    if result.returncode:raise RuntimeError('Pinned image registry inspection failed; upstream output suppressed')
    value=json.loads(result.stdout)
    if value.get('digest')!=expected:raise ValueError('Registry descriptor differs from requested image digest')
    if value.get('mediaType') not in SINGLE|INDEX:raise ValueError('Unsupported image manifest type')
    return value


def platforms(value):
    if value.get('mediaType') not in INDEX:raise ValueError('Platform index required')
    result={}
    for entry in value.get('manifests',[]):
        platform=entry.get('platform',{})
        if platform=={'architecture':'unknown','os':'unknown'} and entry.get('annotations',{}).get('vnd.docker.reference.type')=='attestation-manifest':
            continue
        if entry.get('mediaType') not in SINGLE:raise ValueError('Nested or unsupported platform manifest')
        os_name,arch=platform.get('os'),platform.get('architecture')
        if not all(isinstance(v,str) and re.fullmatch(r'[a-z0-9_]+',v) for v in (os_name,arch)):
            raise ValueError('Invalid image platform')
        key=os_name+'/'+arch
        variant=platform.get('variant')
        if variant and not (arch=='arm64' and variant=='v8'):
            if not isinstance(variant,str) or not re.fullmatch(r'[a-zA-Z0-9._-]+',variant):raise ValueError('Invalid platform variant')
            key+='/'+variant
        if key in result:raise ValueError('Ambiguous duplicate image platform')
        result[key]=digest(entry.get('digest'))
    if not result:raise ValueError('Empty executable image index')
    return result


def verify_image_id(reference,actual,platform=None):
    repository,expected=pinned(reference)
    match=re.search(r'(?:^|@|://)(sha256:[0-9a-f]{64})$',actual)
    if not match:raise AssertionError('Runtime imageID has no immutable digest')
    observed=match.group(1)
    if observed==expected:return observed
    mapping=platforms(manifest(reference))
    allowed={mapping[platform]} if platform in mapping else set(mapping.values()) if platform is None else set()
    if observed not in allowed:raise AssertionError('Runtime image differs from the pinned platform manifests')
    return observed
