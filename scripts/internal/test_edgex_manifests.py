"""Validate the rendered deployment against its physical sensor and ownership contract."""
from pathlib import Path
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class EdgeXDeploymentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rendered = subprocess.check_output(['kubectl', 'kustomize', 'deploy/edgex'], cwd=ROOT, text=True)
        cls.docs = list(yaml.safe_load_all(rendered))

    def test_minimal_runtime_and_no_legacy_controllers(self):
        workloads = [d for d in self.docs if d['kind'] in ('Deployment', 'StatefulSet', 'DaemonSet')]
        self.assertEqual(len(workloads), 8)
        self.assertFalse(any(d['kind'] == 'DaemonSet' for d in workloads))
        for d in workloads:
            self.assertEqual(d['metadata']['labels']['app.kubernetes.io/part-of'], 'edgeai')
            spec = d['spec']['template']['spec']
            self.assertFalse(spec['automountServiceAccountToken'])
            for container in spec['containers'] + spec.get('initContainers', []):
                self.assertIn('@sha256:', container['image'])

    def test_sensor_channels_have_separate_registrations_and_matching_profiles(self):
        maps = [d for d in self.docs if d['kind'] == 'ConfigMap' and d['metadata']['namespace'] == 'edgex-edge']
        devices = []
        for config in maps:
            data = config['data']
            profiles = {yaml.safe_load(v)['name']: yaml.safe_load(v) for k, v in data.items()
                        if k not in ('configuration.yaml', 'devices.yaml')}
            for device in yaml.safe_load(data['devices.yaml'])['deviceList']:
                self.assertIn(device['profileName'], profiles)
                self.assertFalse(device['name'].startswith('virtual-'))
                self.assertIn('physicalDeviceId', device['tags'])
                resources = profiles[device['profileName']]['deviceResources']
                self.assertTrue(resources)
                self.assertTrue(all(r['properties']['readWrite'] == 'R' for r in resources))
                if 'serial' in device['protocols']:
                    self.assertEqual([r['name'] for r in resources],
                                     [device['protocols']['serial']['ResourceName']])
                devices.append(device)
        self.assertEqual(len(devices), 12)
        self.assertEqual(len({d['name'] for d in devices}), 12)
        arduino = [d for d in devices if 'serial' in d['protocols']]
        expected = {'temperature_raw', 'light_raw', 'magnetic_raw',
                    'acceleration_x_raw', 'acceleration_y_raw', 'acceleration_z_raw'}
        self.assertEqual(len(arduino), 6)
        self.assertEqual({d['protocols']['serial']['ResourceName'] for d in arduino}, expected)
        self.assertNotIn('arduino-001', {d['name'] for d in devices})
        for device in arduino:
            self.assertEqual(device['tags']['physicalDeviceId'], 'arduino-001')
            self.assertEqual(device['protocols']['serial']['DeviceID'], 'arduino-001')
            self.assertEqual(device['protocols']['serial']['Port'], '/dev/edgeai/arduino-001')
            self.assertEqual(device['protocols']['serial']['RecoveryStrategy'], 'on-demand-read')

    def test_mounted_configuration_and_secret_references(self):
        configs = {(d['metadata'].get('namespace'), d['metadata']['name']): d
                   for d in self.docs if d['kind'] == 'ConfigMap'}
        self.assertFalse(any(d['kind'] == 'Secret' for d in self.docs))
        for d in self.docs:
            if d['kind'] not in ('Deployment', 'StatefulSet', 'Job'):
                continue
            spec = d['spec']['template']['spec']
            for volume in spec.get('volumes', []):
                if 'configMap' in volume:
                    reference = volume['configMap']
                    config = configs[(d['metadata']['namespace'], reference['name'])]
                    for item in reference.get('items', []):
                        self.assertIn(item['key'], config['data'])
            for container in spec['containers'] + spec.get('initContainers', []):
                for env in container.get('env', []):
                    if env['name'] in ('POSTGRES_PASSWORD', 'PGPASSWORD', 'DB_PASSWORD'):
                        self.assertIn('secretKeyRef', env['valueFrom'])

    def test_spring_ingress_and_internal_services(self):
        policy = next(d for d in self.docs if d['metadata']['name'] == 'edgeai-spring-metadata')
        rule = policy['spec']['ingress'][0]
        self.assertEqual(rule['ports'][0]['port'], 59881)
        self.assertEqual(rule['from'][0]['podSelector']['matchLabels'], {'app': 'edgeai-api'})
        for d in self.docs:
            if d['kind'] == 'Service':
                self.assertEqual(d['spec'].get('type', 'ClusterIP'), 'ClusterIP')

    def test_core_data_waits_for_metadata_before_initializing_device_cache(self):
        deploy = next(d for d in self.docs if d['kind'] == 'Deployment'
                      and d['metadata']['name'] == 'edgex-core-data')
        commands = [c.get('command', []) for c in deploy['spec']['template']['spec']['initContainers']]
        self.assertTrue(any('http://edgex-core-metadata:59881/api/v3/ping' in ' '.join(c) for c in commands))

    def test_sensor_data_and_command_ingress_is_limited_to_spring(self):
        policy = next(d for d in self.docs if d['metadata']['name'] == 'edgeai-spring-sensor-access')
        self.assertEqual(set(policy['spec']['podSelector']['matchExpressions'][0]['values']),
                         {'edgex-core-data', 'edgex-core-command'})
        rule = policy['spec']['ingress'][0]
        self.assertEqual({p['port'] for p in rule['ports']}, {59880, 59882})
        self.assertEqual(rule['from'], [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'edgeai'}},
                                        'podSelector': {'matchLabels': {'app': 'edgeai-api'}}}])


if __name__ == '__main__':
    unittest.main()
