"""Mixed NODE/VD STREAM fixture, with actual journals and authenticated completion.

Routing, Device END and VD readiness are explicit database fixtures.
Claims, checkpoint receipts, group grants, Result commits and broker revocation are real.
"""
import hashlib
import json
import secrets
import urllib.request
import uuid

from postgres_backup import ROOT, literal
from test_recovery_runtime_starts import q
from test_stream_result_fixture import StreamFixture
from recovery_remote_storage import header


class GroupFixture(StreamFixture):
    def define(self,api):
        base=json.loads((ROOT/'contracts/profiles/service-execution.example.json').read_text())
        live=json.loads((ROOT/'contracts/profiles/service-stream.example.json').read_text())
        port={'mediaType':'application/json','maxPayloadBytes':4096}
        self.profiles={}
        for key,outputs in [('root',{'next':port}),('peer',{})]:
            spec={**base,'recovery':live['recovery'],'stream':{**live['stream'],'inputs':{'sample':port},'outputs':outputs}}
            self.profiles[key]=api.request('POST','profiles/SERVICE',{'key':'group-'+key,'version':'1.0.0','spec':spec},201)
        child={**base,'inputs':{k:{'mediaType':'application/json','maxBytes':1048576,'required':True} for k in ('left','right')}}
        self.profiles['child']=api.request('POST','profiles/SERVICE',{'key':'group-child','version':'1.0.0','spec':child},201)
        flow=api.request('POST','workflows',{'key':'mixed-result-recovery','displayName':'Mixed result recovery'},201)
        version=api.request('POST','workflows/'+flow['id']+'/versions',{'version':'1.0.0',
            'tasks':[{'key':k,'serviceProfileVersionId':v['id'],'parameters':{}} for k,v in self.profiles.items()],
            'dependencies':[{'fromTask':'root','toTask':'peer','fromPort':'next','toPort':'sample','mode':'STREAM'},
                *[{'fromTask':k,'toTask':'child','fromPort':'output','toPort':p,'mode':'BATCH'} for k,p in [('root','left'),('peer','right')]]]},201)
        return version,self.public_input(api)

    def checkpoints(self,pg,db,members,requests,tls):
        from edgeai_runner.stream_protocol import Binding,Producer,Frame
        from edgeai_runner.stream_journal import Journal,Limits,Emission
        from edgeai_runner.stream_checkpoint import capture,confirm
        from edgeai_runner.stream_processor import execution_digest
        import recovery_stream_results as recovery
        state=json.loads(pg.sql(recovery.catalog_query(),db))
        tasks={m['attempt']['task_id']:key for key,m in members.items()}
        self.device_actor=Producer('DEVICE_SESSION',self.session['id'],self.session['epoch'],self.device['id'])
        self.routes=state['routes'];self.bindings={};self.generations={}
        for route in self.routes:
            consumer=members[tasks[route['consumer_task_id']]]['attempt']
            if route['source_device_id'] is not None:actor=self.device_actor
            else:
                producer=members[tasks[route['source_task_id']]]['attempt']
                actor=Producer('TASK_ATTEMPT',producer['id'],producer['epoch'])
            g={'id':str(uuid.uuid4()),'route_id':route['id'],'run_id':route['run_id'],'generation':1,
                'source_device_id':route['source_device_id'],'source_task_id':route['source_task_id'],
                'consumer_task_id':route['consumer_task_id'],'producer_session_id':actor.id if actor.kind=='DEVICE_SESSION' else None,
                'producer_attempt_id':actor.id if actor.kind=='TASK_ATTEMPT' else None,'producer_epoch':actor.epoch,
                'consumer_attempt_id':consumer['id'],'consumer_epoch':consumer['epoch'],
                'broker_digest':self.broker.digest,'policy_digest':'sha256:'+'c'*64,'request_digest':'sha256:'+'d'*64}
            columns=','.join([*g,'created_at','updated_at','lease_until'])
            pg.sql('INSERT INTO edgeai.route_generation('+columns+') SELECT '+columns+' FROM jsonb_populate_record(NULL::edgeai.route_generation,'+
                literal(json.dumps(g))+"::jsonb || jsonb_build_object('created_at',now(),'updated_at',now(),'lease_until',now()+interval '120 seconds')); "+
                'UPDATE edgeai.route_generation SET activated_at=now() WHERE id='+q(g['id']),db)
            self.generations[route['id']]=g;self.bindings[route['id']]=Binding(route['id'],1,actor)
        incoming=next(b for b in self.bindings.values() if b.producer.kind=='DEVICE_SESSION')
        outgoing=next(b for b in self.bindings.values() if b.producer.kind=='TASK_ATTEMPT')
        journals={};executions={};self.checkpoints_by_task={}
        previous={key:None for key in members}
        def publish(key):
            journal=journals[key];snapshot=capture(journal,executions[key])
            if snapshot is None:return
            a=members[key]['attempt'];ids=sorted(g['id'] for g in self.generations.values() if a['task_id'] in (g['source_task_id'],g['consumer_task_id']))
            candidate={'previousCheckpointId':previous[key],'serial':snapshot.serial,'sha256':snapshot.sha256,
                'bytes':len(snapshot.wire),'executionSha256':executions[key],'generationIds':ids}
            grant=requests[key]('streams/checkpoints/uploads',{'checkpoint':candidate},200)['upload']
            with urllib.request.urlopen(urllib.request.Request(grant['url'],data=snapshot.wire,headers=grant['headers'],method='PUT'),context=tls,timeout=15) as response:
                assert response.status==200;version=response.headers['x-amz-version-id']
            body={'checkpoint':candidate,'versionId':version}
            receipt=requests[key]('streams/checkpoints/commit',body,201)['checkpoint']
            assert requests[key]('streams/checkpoints/commit',body,200)['checkpoint']==receipt
            assert (receipt['sha256'],receipt['serial'],receipt['versionId'])==(snapshot.sha256,snapshot.serial,version)
            confirm(journal,snapshot.serial,snapshot.sha256);previous[key]=receipt['id']
            self.checkpoints_by_task[key]=json.loads(pg.sql('SELECT to_jsonb(c) FROM edgeai.stream_checkpoint c WHERE id='+q(receipt['id']),db))
        try:
            for key,m in members.items():
                contract=next(c for c in state['contracts'] if c['taskId']==m['attempt']['task_id'])
                work=json.loads(contract['workJson']);spec=work['spec']['stream']
                ins={'sample':incoming.route_id if key=='root' else outgoing.route_id};outs={'next':[outgoing.route_id]} if key=='root' else {}
                executions[key]=execution_digest(spec['command']+spec['args'],work['parameters'],ins,outs,spec['stepTimeoutSeconds'])
                limits=Limits(max_frames=spec['limits']['maxFrames'],max_buffer_bytes=spec['limits']['maxBufferBytes'],max_state_bytes=spec['limits']['maxStateBytes'])
                journals[key]=Journal(self.work/('sealed-'+key),[incoming if key=='root' else outgoing],
                    [outgoing] if key=='root' else [],limits,create=True,durability='EXTERNAL')
            root,peer=journals['root'],journals['peer']
            for revision,kind,payload,media in [(0,'DATA',b'6','application/json'),(1,'END',b'',None)]:
                root.receive(Frame(incoming,revision+1,kind,payload,media))
                emitted=root.commit(revision,root.pending(),b'6',[Emission(outgoing.route_id,payload,media,kind)])
                publish('root')
                for frame in emitted:peer.receive(frame)
                peer.commit(revision,peer.pending(),b'6')
                publish('peer')
                root.acknowledge(outgoing,revision+1)
            for key in members:publish(key)
            pg.sql('INSERT INTO edgeai.stream_device_completion(generation_id,sequence,created_at) VALUES ('+
                q(self.generations[incoming.route_id]['id'])+',2,now())',db)
        finally:
            for journal in journals.values():journal.close()

    def fence_group(self,members):
        from recovery_device_test_support import Peer
        from edgeai_runner.stream_protocol import Frame
        import recovery_mqtt_fence as mqtt
        broker=self.broker
        names=['edgeai-device-'+self.device_actor.id+'-'+str(self.device_actor.epoch)]+[
            'edgeai-task-'+m['attempt']['id']+'-'+str(m['attempt']['epoch']) for m in members.values()]
        credentials={name:secrets.token_hex(32) for name in names}
        topics=['edgeai/streams/'+route+'/1/frames' for route in self.bindings]
        role='edgeai-generation-'+uuid.uuid4().hex
        with mqtt.Connection(broker.cfg,broker.password) as admin:
            admin.call('createRole',rolename=role,textname='edgeai-stream-v1',textdescription='sha256:'+'c'*64,
                acls=[{'acltype':kind,'topic':topic,'allow':True,'priority':0} for topic in topics
                    for kind in ('publishClientSend','publishClientReceive','subscribeLiteral','unsubscribeLiteral')])
            for name,password in credentials.items():
                admin.call('createClient',username=name,clientid=name,password=password,textname='edgeai-stream-v1',
                    textdescription=broker.digest+':'+'a'*64,roles=[{'rolename':role,'priority':0}])
        peers={name:Peer(broker.cfg,name,password) for name,password in credentials.items()};broker.peers.extend(peers.values())
        assert all(p.reason==0 for p in peers.values())
        for route,binding in self.bindings.items():
            sender=peers['edgeai-'+('device-' if binding.producer.kind=='DEVICE_SESSION' else 'task-')+binding.producer.id+'-'+str(binding.producer.epoch)]
            consumer=self.generations[route];receiver=peers['edgeai-task-'+consumer['consumer_attempt_id']+'-'+str(consumer['consumer_epoch'])]
            topic='edgeai/streams/'+route+'/1/frames';receiver.client.subscribe(topic,qos=1);assert receiver.subscribed.wait(5)
            wire=Frame(binding,1,'DATA',b'6','application/json').encode()
            sender.client.publish(topic,wire,qos=1).wait_for_publish(5);assert receiver.messages.get(timeout=5)==wire
        result={};mqtt.execute(broker.cfg,result)
        assert result['status']=='SOURCE_BROKER_CLIENTS_DISABLED' and result['disabledPrincipals']==3
        assert all(peer.disconnected.wait(5) for peer in peers.values())
        for name,password in credentials.items():
            peer=Peer(broker.cfg,name,password);broker.peers.append(peer);assert peer.reason in (134,135)
        broker.replacement=json.loads((broker.cfg.state_directory/'recovery.json').read_text())['password']


def commit_result(request,store,fixture,member,vd):
    raw=b'{"value":6,"sourceMode":"SYNTHETIC"}'
    content={'port':'output','bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'mediaType':'application/json'}
    grant=request('uploads',{'outputs':[content]},200)['outputs'][0]
    with urllib.request.urlopen(urllib.request.Request(grant['url'],data=raw,headers=grant['headers'],method='PUT'),context=fixture.tls,timeout=15) as response:
        assert response.status==200;version=response.headers['x-amz-version-id']
    body={'outputs':[{**content,'versionId':version}]};first=request('commit',body,201)
    assert request('commit',body,200)==first
    path='/'+fixture.bucket+('/authority/vd-task-result/' if vd else '/authority/runtime-result/')+member['runtime']+'.json'
    code,headers,wire=store.request('GET',path);assert code==200
    result=json.loads(wire);assert result['resultId']==first['resultId']
    member.update(result=result,resultVersion=header(headers,'x-amz-version-id'))
