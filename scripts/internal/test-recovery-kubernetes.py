"""Recovery observation classification and paginated read boundaries; explicit metadata fixtures."""
import copy
import unittest
import uuid

from recovery_kubernetes import Kubernetes, PART, MANAGER, RUNTIME, VD, classify, inventory


def fresh(): return str(uuid.uuid4())


class RecoveryKubernetesTest(unittest.TestCase):
    def setUp(self):
        self.attempt,self.task,self.run,self.job_uid,self.pod_uid = [fresh() for _ in range(5)]
        self.row = {'id':fresh(),'namespace':'test','job_name':'edgeai-'+self.attempt,'job_uid':self.job_uid,
            'producer_pod_uid':self.pod_uid,'attempt_id':self.attempt,'task_id':self.task,'run_id':self.run,
            'epoch':2,'observed_state':'RUNNING'}
        self.catalog = {'runtimes':[self.row],'vds':[],'snapshot':'1:2:','restoreReportSha256':'a'*64}
        self.labels = {PART:'edgeai',MANAGER:RUNTIME,'edgeai.io/run-id':self.run,
            'edgeai.io/task-id':self.task,'edgeai.io/attempt-id':self.attempt,'edgeai.io/epoch':'2'}
        self.job = {'metadata':{'namespace':'test','name':'edgeai-'+self.attempt,
            'uid':self.job_uid,'resourceVersion':'1','labels':self.labels}}
        self.pod = {'metadata':{'namespace':'test','name':'runner-pod','uid':self.pod_uid,'resourceVersion':'2',
            'labels':self.labels,'ownerReferences':[{'kind':'Job','controller':True,
                'name':'edgeai-'+self.attempt,'uid':self.job_uid}]}}

    def status(self,kind,item,catalog=None):
        return classify(kind,item,catalog or self.catalog,{self.job_uid})['classification']

    def test_database_match_and_after_snapshot_job_and_child(self):
        self.assertEqual(self.status('Job',self.job),'DATABASE_UID_MATCH')
        self.assertEqual(self.status('Pod',self.pod),'DATABASE_UID_MATCH')
        empty = {**self.catalog,'runtimes':[]}
        self.assertEqual(self.status('Job',self.job,empty),'ABSENT_FROM_RESTORED_DATABASE')
        self.assertEqual(self.status('Pod',self.pod,empty),'ABSENT_FROM_RESTORED_DATABASE')

    def test_recreated_same_name_and_unrecorded_uid_are_not_matches(self):
        job = copy.deepcopy(self.job); job['metadata']['uid']=fresh()
        self.assertEqual(self.status('Job',job),'DATABASE_UID_MISMATCH')
        self.row['job_uid']=None
        self.assertEqual(self.status('Job',self.job),'DATABASE_UID_NOT_RECORDED')

    def test_label_drift_foreign_reserved_name_and_orphan_are_visible(self):
        for key,value in [(MANAGER,'someone-else'),('edgeai.io/task-id',fresh()),('edgeai.io/epoch','-1')]:
            item=copy.deepcopy(self.job); item['metadata']['labels'][key]=value
            self.assertEqual(self.status('Job',item),'OWNERSHIP_CONFLICT')
        pod=copy.deepcopy(self.pod); pod['metadata']['ownerReferences']=[]
        self.assertEqual(self.status('Pod',pod),'ORPHAN_OR_CONFLICTING_RUNTIME_POD')
        pod=copy.deepcopy(self.pod); pod['metadata']['labels']={}
        self.assertEqual(self.status('Pod',pod),'OWNERSHIP_CONFLICT')
        foreign=copy.deepcopy(self.job); foreign['metadata'].update(name='unrelated',labels={})
        self.assertIsNone(classify('Job',foreign,self.catalog,set()))

    def test_vd_generation_and_identity(self):
        runtime,vd,pod = fresh(),fresh(),fresh()
        item={'metadata':{'namespace':'test','name':'edgeai-vd-'+runtime,'uid':pod,'resourceVersion':'8',
            'labels':{PART:'edgeai',MANAGER:VD,'edgeai.io/vd-id':vd,'edgeai.io/vd-runtime-id':runtime,'edgeai.io/generation':'3'}}}
        self.assertEqual(self.status('Pod',item),'ABSENT_FROM_RESTORED_DATABASE')
        self.catalog['vds']=[{'id':runtime,'vd_id':vd,'generation':3,'namespace':'test',
            'pod_name':'edgeai-vd-'+runtime,'pod_uid':pod,'observed_state':'READY'}]
        self.assertEqual(self.status('Pod',item),'DATABASE_UID_MATCH')
        item['metadata']['labels']['edgeai.io/generation']='4'
        self.assertEqual(self.status('Pod',item),'OWNERSHIP_CONFLICT')

    def test_pagination_is_complete_and_rejects_changed_snapshot(self):
        kube=Kubernetes('fixture'); paths=[]
        pages=[{'metadata':{'resourceVersion':'12','continue':'opaque/+ token'},'items':[self.job]},
            {'metadata':{'resourceVersion':'12'},'items':[]}]
        def read(path): paths.append(path); return pages.pop(0)
        kube.read=read
        rows,version=kube.items('test','Job')
        self.assertEqual(rows,[self.job]); self.assertEqual(version,'12')
        self.assertIn('continue=opaque%2F%2B+token',paths[1])
        pages.extend([{'metadata':{'resourceVersion':'12','continue':'next'},'items':[]},
            {'metadata':{'resourceVersion':'13'},'items':[]}])
        with self.assertRaises(ValueError): kube.items('test','Job')

    def test_duplicate_objects_and_continuation_are_rejected(self):
        kube=Kubernetes('fixture')
        kube.read=lambda path:{'metadata':{'resourceVersion':'1','continue':'next'},'items':[self.job]}
        with self.assertRaises(ValueError): kube.items('test','Job')
        kube.read=lambda path:{'metadata':{'resourceVersion':'1','continue':'next'},'items':[]}
        with self.assertRaises(ValueError): kube.items('test','Job')

    def test_missing_object_never_means_stopped_and_namespace_replacement_rejected(self):
        kube=Kubernetes('fixture'); kube.namespace=lambda name:self.job_uid
        kube.items=lambda name,kind:([],'1')
        report=inventory(kube,['test'],self.catalog)
        self.assertFalse(report['quiesced']); self.assertFalse(report['activated'])
        self.assertEqual(report['databaseObjectsNotObserved'][0]['classification'],'NOT_OBSERVED')
        ids=iter([fresh(),fresh()]); kube.namespace=lambda name:next(ids)
        with self.assertRaises(ValueError): inventory(kube,['test'],self.catalog)


if __name__=='__main__': unittest.main()
