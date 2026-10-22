#!/usr/bin/env python3
"""Build a deterministic scratch OCI image from a static Go binary and push it.

This needs no Docker daemon. Registry transport is restricted to the explicitly
provided development registry. No credentials or tokens are accepted or logged.
"""
import argparse, gzip, hashlib, io, json, tarfile, urllib.request
from pathlib import Path

def digest(data): return 'sha256:' + hashlib.sha256(data).hexdigest()

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--registry',required=True)
    p.add_argument('--repository',required=True)
    p.add_argument('--tag',required=True)
    p.add_argument('--arch',choices=['amd64','arm64'],required=True)
    p.add_argument('--binary',required=True)
    p.add_argument('--sensehat-runtime',action='store_true',help='Add the pinned ARM64 Python/RTIMULib SDK base and physical reader; no EdgeX service binary.')
    a=p.parse_args()
    if a.registry != '10.254.192.47:5000': p.error('only the configured development registry is supported')
    root='http://'+a.registry+'/v2/'+a.repository
    def request(method,url,data=None,content_type=None):
        headers={'Accept':'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'}
        if content_type is not None: headers['Content-Type']=content_type
        return urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers,method=method),timeout=30)
    def blob(data):
        sha=digest(data)
        try:
            with request('HEAD',root+'/blobs/'+sha): return sha
        except urllib.error.HTTPError as e:
            if e.code!=404: raise
        with request('POST',root+'/blobs/uploads/',b'') as r: location=r.headers['Location']
        if location.startswith('/'): location='http://'+a.registry+location
        location+=('&' if '?' in location else '?')+'digest='+sha
        with request('PUT',location,data,'application/octet-stream'): pass
        return sha
    base_layers=[]; base_diff_ids=[]; base_config={}
    if a.sensehat_runtime:
        if a.arch != 'arm64': p.error('Sense HAT RTIMULib runtime is ARM64 only')
        base_root='http://'+a.registry+'/v2/edgex-device-sensehat-base'
        base_digest='sha256:aaddfeadce5777fd57a0ef67c2adff10e72fd444d8c41dd9c344f706c5d7ceef'
        with request('GET',base_root+'/manifests/'+base_digest) as r: raw_base=r.read()
        if digest(raw_base)!=base_digest: raise ValueError('SDK manifest digest mismatch')
        base=json.loads(raw_base)
        with request('GET',base_root+'/blobs/'+base['config']['digest']) as r: original=json.load(r)
        if original['architecture']!='arm64': p.error('SDK base architecture mismatch')
        base_layers=base['layers']; base_diff_ids=original['rootfs']['diff_ids']; base_config=original.get('config',{})
        for item in base_layers:
            sha=item['digest']
            try:
                with request('HEAD',root+'/blobs/'+sha): continue
            except urllib.error.HTTPError as e:
                if e.code!=404: raise
            with request('POST',root+'/blobs/uploads/?mount='+sha+'&from=edgex-device-sensehat-base',b'') as r: mounted=r.status==201
            if not mounted:
                with request('GET',base_root+'/blobs/'+sha) as r: data=r.read()
                if digest(data)!=sha: raise ValueError('SDK base layer digest mismatch')
                blob(data)
    buffer=io.BytesIO()
    with tarfile.open(fileobj=buffer,mode='w') as tar:
        data=open(a.binary,'rb').read();info=tarfile.TarInfo('app');info.size=len(data);info.mode=0o755;info.mtime=0;tar.addfile(info,io.BytesIO(data))
        if a.sensehat_runtime:
            hardware=Path(__file__).resolve().parents[2]/'mappers'/'hardware'
            for filename,target in [('read_sensehat.py','opt/edgeai/read_sensehat.py'),('RTIMULib.ini','etc/RTIMULib.ini')]:
                data=(hardware/filename).read_bytes();info=tarfile.TarInfo(target);info.size=len(data);info.mode=0o444;info.mtime=0;tar.addfile(info,io.BytesIO(data))
    raw=buffer.getvalue();layer=gzip.compress(raw,mtime=0)
    base_config.update(Entrypoint=['/app'],Cmd=[])
    config=json.dumps({'architecture':a.arch,'os':'linux','config':base_config,'rootfs':{'type':'layers','diff_ids':base_diff_ids+[digest(raw)]}},sort_keys=True,separators=(',',':')).encode()
    manifest={'schemaVersion':2,'mediaType':'application/vnd.oci.image.manifest.v1+json','config':{'mediaType':'application/vnd.oci.image.config.v1+json','digest':blob(config),'size':len(config)},'layers':base_layers+[{'mediaType':'application/vnd.oci.image.layer.v1.tar+gzip','digest':blob(layer),'size':len(layer)}]}
    body=json.dumps(manifest,sort_keys=True,separators=(',',':')).encode()
    with request('PUT',root+'/manifests/'+a.tag,body,manifest['mediaType']): pass
    print(a.registry+'/'+a.repository+'@'+digest(body))

if __name__=='__main__': main()
