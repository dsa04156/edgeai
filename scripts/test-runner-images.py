"""Verify platform provenance, immutable image identity and release pin refusal boundaries."""
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import image_identity as images
from runner_platform import selected,test_results

ROOT=Path(__file__).resolve().parents[1]
SOURCE='a'*40
INDEX='sha256:'+'1'*64
AMD='sha256:'+'2'*64
ARM='sha256:'+'3'*64
OTHER='sha256:'+'4'*64
REF='ghcr.io/dsa04156/edgeai-runner@'+INDEX


def descriptor():
    return {'mediaType':'application/vnd.oci.image.index.v1+json','digest':INDEX,'manifests':[
        {'mediaType':'application/vnd.oci.image.manifest.v1+json','digest':d,'platform':{'os':'linux','architecture':a}}
        for a,d in [('amd64',AMD),('arm64',ARM)]]}


def records():
    return [{'formatVersion':1,'scope':'native-runner-platform','sourceRevision':SOURCE,'platform':'linux/'+a,
        'hostMachine':m,'containerMachine':m,'imageConfigDigest':OTHER,'publishedDigest':d,
        'tests':{'runner-container':111,'stream-mqtt':107}} for a,m,d in [('amd64','x86_64',AMD),('arm64','aarch64',ARM)]]


class Images(unittest.TestCase):
    def test_single_platform_keeps_exact_digest_proof_without_registry_dependency(self):
        with patch.object(images,'manifest',side_effect=AssertionError('Unexpected registry access')):
            self.assertEqual(INDEX,images.verify_image_id(REF,'docker-pullable://example@'+INDEX))

    def test_index_accepts_only_its_real_platform_child(self):
        with patch.object(images,'manifest',return_value=descriptor()):
            self.assertEqual(ARM,images.verify_image_id(REF,'containerd://'+ARM,'linux/arm64'))
            self.assertEqual(AMD,images.verify_image_id(REF,'repo@'+AMD,'linux/amd64'))
            for actual,platform in [(OTHER,None),(AMD,'linux/arm64'),(ARM,'linux/ppc64le')]:
                with self.assertRaises(AssertionError):images.verify_image_id(REF,'repo@'+actual,platform)

    def test_mutable_or_malformed_references_cannot_be_verified(self):
        for ref in ['repo:latest','--host=evil@'+INDEX,'repo@'+INDEX+' extra']:
            with self.assertRaises(ValueError):images.verify_image_id(ref,'repo@'+INDEX)
        with self.assertRaises(AssertionError):images.verify_image_id(REF,'latest')

    def test_duplicate_nested_empty_or_corrupt_index_is_refused(self):
        cases=[]
        duplicate=descriptor();duplicate['manifests'].append(deepcopy(duplicate['manifests'][0]));cases.append(duplicate)
        nested=descriptor();nested['manifests'][1]['mediaType']=nested['mediaType'];cases.append(nested)
        invalid=descriptor();invalid['manifests'][1]['digest']='tag';cases.append(invalid)
        empty=descriptor();empty['manifests']=[];cases.append(empty)
        for case in cases:
            with self.subTest(case=case),self.assertRaises(ValueError):images.platforms(case)

    def test_attestation_is_not_an_executable_platform(self):
        value=descriptor();value['manifests'].append({'platform':{'os':'unknown','architecture':'unknown'},
            'annotations':{'vnd.docker.reference.type':'attestation-manifest'},'digest':OTHER})
        self.assertEqual({'linux/amd64':AMD,'linux/arm64':ARM},images.platforms(value))

    def test_only_two_matching_native_sources_can_be_published(self):
        self.assertEqual({'linux/amd64':AMD,'linux/arm64':ARM},selected(records(),SOURCE))
        for name,value in [('sourceRevision','b'*40),('hostMachine','x86_64'),('containerMachine','x86_64'),
                ('publishedDigest',None),('tests',{'runner-container':111,'stream-mqtt':106}),
                ('tests',{'runner-container':111,'stream-mqtt':102}),
                ('tests',{'runner-container':111,'stream-mqtt':97})]:
            rows=records();rows[1][name]=value
            with self.subTest(name=name),self.assertRaises(ValueError):selected(rows,SOURCE)
        for rows in [records()[:1],records()+records()[:1],[records()[0],records()[0]]]:
            with self.assertRaises(ValueError):selected(rows,SOURCE)

    def test_native_suite_requires_real_non_skipped_successes(self):
        with tempfile.TemporaryDirectory() as work:
            root=Path(work)
            for name,count in [('runner-container',111),('stream-mqtt',107)]:
                p=root/name;p.mkdir();(p/'result.json').write_text(json.dumps({'testId':name,'status':'PASS','exitCode':0}))
                (p/'output.log').write_text('Ran '+str(count)+' tests in 1.5s\nOK\n')
            self.assertEqual({'runner-container':111,'stream-mqtt':107},test_results(root))
            p=root/'stream-mqtt'/'output.log';p.write_text(p.read_text()+'OK (skipped=1)\n')
            with self.assertRaises(ValueError):test_results(root)
            p.write_text('Ran 97 tests in 1.5s\nOK\n')
            with self.assertRaises(ValueError):test_results(root)
            p.write_text('Ran 102 tests in 1.5s\nOK\n')
            with self.assertRaises(ValueError):test_results(root)
            p.write_text('Ran 107 tests in 1.5s\nOK\n')
            (root/'stream-mqtt'/'result.json').write_text(json.dumps({'testId':'stream-mqtt','status':'FAIL','exitCode':1}))
            with self.assertRaises(ValueError):test_results(root)

    def test_release_requires_both_platforms_before_touching_pins(self):
        with tempfile.TemporaryDirectory() as work:
            root=Path(work);overlay=root/'deploy/kubernetes/overlays/dev';overlay.mkdir(parents=True)
            k=overlay/'kustomization.yaml';original='images:\n'+''.join('- name: ghcr.io/dsa04156/edgeai-'+n+'\n    digest: '+OTHER+'\n' for n in ['api','dashboard','minio'])
            k.write_text(original)
            command=[sys.executable,str(ROOT/'scripts/update-images.py'),'--revision',SOURCE,'--api',AMD,'--dashboard',AMD,'--runner',INDEX,'--minio',AMD,'--runner-platform-digests']
            bad=subprocess.run(command+[json.dumps({'linux/amd64':AMD})],cwd=root,capture_output=True)
            self.assertNotEqual(0,bad.returncode);self.assertEqual(original,k.read_text());self.assertFalse((overlay/'release.json').exists())
            good=subprocess.run(command+[json.dumps({'linux/amd64':AMD,'linux/arm64':ARM})],cwd=root,capture_output=True)
            self.assertEqual(0,good.returncode)
            value=json.loads((overlay/'release.json').read_text());self.assertEqual(INDEX,value['runnerDigest'])
            self.assertEqual(['linux/amd64','linux/arm64'],value['runtimeImagePlatforms'])
            self.assertEqual({'linux/amd64':AMD,'linux/arm64':ARM},value['runnerPlatformDigests'])


if __name__=='__main__':unittest.main(verbosity=2)
