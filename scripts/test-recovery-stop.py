"""Conservative termination evidence and recovery ownership regression cases."""
import copy
import unittest
import uuid

from postgres_backup import Blocked
from recovery_kubernetes import PART,MANAGER,RUNTIME,VD
from recovery_stop_kubernetes import FINALIZER,OPERATION,FENCE,HARD,Stop,termination_proof


def fresh(): return str(uuid.uuid4())


class RecoveryStopTest(unittest.TestCase):
    def setUp(self):
        self.pod={'metadata':{'deletionTimestamp':'2026-10-04T00:00:00Z'},
            'spec':{'nodeName':'node','containers':[{'name':'runner'}]},
            'status':{'phase':'Succeeded','containerStatuses':[{'name':'runner','state':{'terminated':{
                'reason':'Completed','containerID':'containerd://abc','exitCode':0,
                'startedAt':'2026-10-04T00:00:00Z','finishedAt':'2026-10-04T00:00:01Z'}}}]}}

    def test_actual_termination_and_never_scheduled_are_distinct(self):
        self.assertEqual(termination_proof(self.pod)['kind'],'ALL_CONTAINERS_TERMINATED')
        self.pod['spec'].pop('nodeName'); self.pod['status']={'phase':'Pending'}
        self.assertEqual(termination_proof(self.pod),{'kind':'NEVER_BOUND_TO_NODE','containers':[]})

    def test_phase_or_node_loss_or_missing_container_is_insufficient(self):
        for change in ['running','unknown','missing','timestamp','deletion']:
            pod=copy.deepcopy(self.pod)
            if change=='running': pod['status']['phase']='Running'
            if change=='unknown': pod['status']['containerStatuses'][0]['state']['terminated']['reason']='ContainerStatusUnknown'
            if change=='missing': pod['spec']['containers'].append({'name':'other'})
            if change=='timestamp': pod['status']['containerStatuses'][0]['state']['terminated'].pop('finishedAt')
            if change=='deletion': pod['metadata'].clear()
            self.assertIsNone(termination_proof(pod),change)

    def test_init_sidecar_and_ephemeral_container_cannot_be_ignored(self):
        self.pod['spec']['initContainers']=[{'name':'sidecar','restartPolicy':'Always'}]
        self.assertIsNone(termination_proof(self.pod))
        self.pod['status']['initContainerStatuses']=[{'name':'sidecar','state':{'running':{}}}]
        self.assertIsNone(termination_proof(self.pod))
        self.pod['spec']['ephemeralContainers']=[{'name':'debug'}]
        with self.assertRaises(Blocked): termination_proof(self.pod)

    def test_fence_identity_policy_and_operation_are_immutable(self):
        operation=Stop(None,'owned',fresh(),fresh(),60); operation.quota_uid=fresh()
        quota={'metadata':{'uid':operation.quota_uid,'name':FENCE,'namespace':'owned',
            'labels':{PART:'edgeai',MANAGER:'edgeai-recovery'},'annotations':{OPERATION:operation.operation}},'spec':{'hard':HARD}}
        operation.check_fence(quota)
        for field in ['uid','operation','policy','deleting']:
            altered=copy.deepcopy(quota)
            if field=='uid': altered['metadata']['uid']=fresh()
            if field=='operation': altered['metadata']['annotations'][OPERATION]=fresh()
            if field=='policy': altered['spec']['hard']['count/pods']='1'
            if field=='deleting': altered['metadata']['deletionTimestamp']='now'
            with self.assertRaises(ValueError): operation.check_fence(altered)

    def test_foreign_or_previously_terminating_producer_refused(self):
        operation=Stop(None,'owned',fresh(),fresh(),60)
        runtime,vd=fresh(),fresh()
        pod={'metadata':{'namespace':'owned','name':'edgeai-vd-'+runtime,'uid':fresh(),'resourceVersion':'1',
            'labels':{PART:'edgeai',MANAGER:VD,'edgeai.io/vd-id':vd,'edgeai.io/vd-runtime-id':runtime,
                'edgeai.io/generation':'1'}},'spec':{}}
        operation.owned('Pod',pod)
        pod['metadata']['deletionTimestamp']='now'
        with self.assertRaises(Blocked): operation.owned('Pod',pod)
        pod['metadata']['finalizers']=[FINALIZER]
        pod['metadata']['annotations']={OPERATION:fresh()}
        with self.assertRaises(ValueError): operation.owned('Pod',pod)
        pod['metadata']['annotations'][OPERATION]=operation.operation
        operation.owned('Pod',pod)
        pod['metadata']['labels'][MANAGER]='someone-else'
        with self.assertRaises(ValueError): operation.owned('Pod',pod)


if __name__=='__main__': unittest.main()
