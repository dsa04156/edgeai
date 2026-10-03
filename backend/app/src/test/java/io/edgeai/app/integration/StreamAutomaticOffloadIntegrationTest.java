package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.config.StreamConnectionSettings;
import io.edgeai.app.service.*;
import org.springframework.context.annotation.Import;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.stream.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public MVC and real PostgreSQL transactions. Pod identities, broker receipts and S3 receipts are explicit fixtures. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.runtime.namespace=automatic-stream-test",
    "edgeai.stream.enabled=true","edgeai.stream.bindings-enabled=true","edgeai.stream.runs-enabled=true",
    "edgeai.stream.broker-digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","edgeai.stream.lease-seconds=120"})
@AutoConfigureMockMvc
@Import(RetryIntegrationTest.TimeConfiguration.class)
class StreamAutomaticOffloadIntegrationTest {
    @Autowired RetryIntegrationTest.TestClock clock;
    @Autowired RuntimeTelemetryService telemetry;
    @Autowired TelemetryRepository measurements;
    private static final Path KEY=key();
    private static Path key(){try{
        var p=Files.createTempFile("edgeai-public-stream-",".key",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.writeString(p,HexFormat.of().formatHex(bytes));return p;
    }catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("edgeai.runtime.key-file",KEY::toString);p.add("edgeai.stream.device-key-file",KEY::toString);}
    @AfterAll static void removeKey()throws Exception{Files.delete(KEY);}
    @MockitoBean RuntimeGateway gateway;@MockitoBean S3ArtifactStore storage;@MockitoBean MosquittoStreamBroker broker;
    @MockitoBean StreamAuthorityWorker authorityWorker;@MockitoBean StreamConnectionSettings connection;@MockitoBean StreamRunWorker scheduledWorker;
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired DeviceService devices;
    @Autowired ExecutionService runs;@Autowired ExecutionRepository executions;@Autowired RuntimeLifecycleService lifecycle;@Autowired RuntimeRepository runtimes;
    @Autowired StreamRunService streams;@Autowired StreamRunRepository store;@Autowired DataRouteRepository routes;@Autowired DataRouteService routeLifecycle;
    @Autowired StreamExecutionRepository completions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired PlatformTransactionManager transactions;
    @Autowired WorkflowRepository workflowStore;
    @Autowired StreamExecutionService streamExecution;@Autowired StreamCheckpointRepository checkpoints;
    @Autowired OffloadService offloads;@Autowired OffloadRepository offloadStore;@Autowired NodeService nodes;
    private final JsonDocuments json=new JsonDocuments();
    private record Definition(UUID version,List<Map<String,Object>> inputs,List<Device> devices){}
    private String encode(Object value){return json.canonical(value);}
    private UUID profile(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,encode(Map.of("key","public-stream-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    @SuppressWarnings("unchecked") private UUID service(List<String> inputs,boolean output,boolean fileInput)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/"+(inputs==null?"service-execution":"service-stream")+".example.json")));
        if(inputs==null)spec.put("recovery",Map.of("mode","RESTART"));
        if(inputs!=null){var stream=(Map<String,Object>)spec.get("stream");var ports=new TreeMap<String,Object>();
            inputs.forEach(p->ports.put(p,Map.of("mediaType","application/json","maxPayloadBytes",4096)));
            stream.put("inputs",ports);stream.put("outputs",output?Map.of("output",Map.of("mediaType","application/json","maxPayloadBytes",4096)):Map.of());}
        if(fileInput)spec.put("inputs",Map.of("file-input",Map.of("mediaType","application/json","maxBytes",1048576,"required",true)));
        return profile(ProfileIdentity.Kind.SERVICE,spec);
    }
    private Device device(){var p=profile(ProfileIdentity.Kind.DEVICE,Map.of("protocol","mqtt"));
        var d=devices.create(encode(Map.of("key","public-stream-"+UUID.randomUUID(),"displayName","Stream test","profileVersionId",p.toString(),"sourceMode","SYNTHETIC"))).value();
        devices.openSession(d.id(),encode(Map.of("bootId",UUID.randomUUID().toString())));return d;}
    private Map<String,Object> input(Device d,String task,String port){return Map.of("deviceId",d.id().toString(),"sourcePort","samples","toTask",task,"toPort",port,"maxPayloadBytes",4096);}
    private Map<String,Object> edge(String from,String to,String output,String input,String mode){return Map.of("fromTask",from,"toTask",to,"fromPort",output,"toPort",input,"mode",mode);}
    private UUID version(Map<String,UUID> specs,List<Map<String,Object>> edges){
        var w=workflows.create(encode(Map.of("key","public-stream-"+UUID.randomUUID(),"displayName","Public stream fixture"))).value();
        var tasks=specs.entrySet().stream().map(e->Map.of("key",e.getKey(),"serviceProfileVersionId",e.getValue().toString(),"parameters",Map.of())).toList();
        return workflows.publish(w.id(),encode(Map.of("version","1.0.0","tasks",tasks,"dependencies",edges))).value().id();
    }
    private Definition definition(boolean upstream,boolean unrelated)throws Exception{
        var specs=new TreeMap<String,UUID>();specs.put("source",service(List.of("a","b"),true,false));specs.put("sink",service(List.of("input"),false,upstream));
        var edges=new ArrayList<Map<String,Object>>();edges.add(edge("source","sink","output","input","STREAM"));
        if(upstream){specs.put("prep",service(null,false,false));edges.add(edge("prep","sink","output","file-input","BATCH"));}
        if(unrelated){specs.put("independent",service(null,false,false));specs.put("child",service(null,false,true));edges.add(edge("sink","child","result","file-input","BATCH"));}
        var a=device();var b=device();return new Definition(version(specs,edges),List.of(input(a,"source","a"),input(b,"source","b")),List.of(a,b));
    }
    private Map<String,Object> request(Definition d){return new TreeMap<>(Map.of("workflowVersionId",d.version().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of(),"streamInputs",d.inputs()));}
    private String perform(MockHttpServletRequestBuilder r,int code)throws Exception{return mvc.perform(r.with(user("test")).with(csrf())).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();}
    private String create(String key,Object value,int code)throws Exception{return perform(post("/api/v1/workflow-runs").header("Idempotency-Key",key).contentType("application/json").content(encode(value)),code);}
    private Task task(UUID run,String name){return executions.tasks(run).stream().filter(t->t.key().equals(name)).findFirst().orElseThrow();}
    private TaskAttempt attempt(UUID run,String name){return executions.attempts(task(run,name).id()).getFirst();}
    private void finishPhysical(UUID run,String name){
        var a=attempt(run,name);var r=runtimes.byAttempt(a.id()).orElseThrow();
        // Explicit provisioning fixture: the CREATE operation has finished and the old Pod has disappeared.
        jdbc.update("UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=? AND kind='CREATE'",r.id());
        lifecycle.confirmStopped(a.id());
    }
    private void revokeGroup(UUID run){
        for(var route:routes.forRun(run,20,0))routes.open(route.id()).ifPresent(g->{
            assertThat(g.fencedAt()).isNotNull();routeLifecycle.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));
        });
    }
    private io.edgeai.app.config.RunnerPrincipal principal(UUID run,String name){
        var r=runtimes.byAttempt(attempt(run,name).id()).orElseThrow();
        return new io.edgeai.app.config.RunnerPrincipal(r.attemptId(),r.epoch(),new RuntimePod(r.jobUid(),r.producerPodUid(),r.nodeUid(),r.nodeName()));
    }
    private String identity(io.edgeai.app.config.RunnerPrincipal p,Object... extra){
        var value=new TreeMap<String,Object>(Map.of("epoch",p.epoch(),"podUid",p.podUid().toString()));
        for(int i=0;i<extra.length;i+=2)value.put((String)extra[i],extra[i+1]);return encode(value);
    }
    /** Checkpoint bytes/S3 receipt are fixtures here; actual signed-object finalization is tested separately. */
    private Map<String,StreamCheckpoint> seal(UUID run){return checkpoint(run,true);}
    private Map<String,StreamCheckpoint> checkpoint(UUID run,boolean seal){
        for(var route:routes.forRun(run,20,0)){var g=routes.open(route.id()).orElseThrow();
            routeLifecycle.activate(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));}
        var source=principal(run,"source");streamExecution.execution(source,identity(source));
        var saved=new HashMap<String,StreamCheckpoint>();
        for(String name:List.of("source","sink")){
            var t=task(run,name);var p=principal(run,name);var r=runtimes.byAttempt(p.attemptId()).orElseThrow();
            var relevant=routes.forRun(run,20,0).stream().filter(v->t.id().equals(v.sourceTaskId()) || t.id().equals(v.consumerTaskId())).toList();
            var inputs=new ArrayList<Object>();var outputs=new ArrayList<Object>();var cursors=new ArrayList<Object>();var ids=new ArrayList<UUID>();
            for(var route:relevant){var g=routes.open(route.id()).orElseThrow();ids.add(g.id());
                Object producer=route.deviceSource()?Map.of("kind","DEVICE_SESSION","deviceId",route.sourceDeviceId().toString(),"sessionId",g.producer().id().toString(),"epoch",g.producer().epoch())
                    :Map.of("kind","TASK_ATTEMPT","attemptId",g.producer().id().toString(),"epoch",g.producer().epoch());
                (t.id().equals(route.consumerTaskId())?inputs:outputs).add(Map.of("routeId",route.id().toString(),"generation",g.generation(),"producer",producer));
                cursors.add(Map.of("routeId",route.id().toString(),"received",3,"committed",3,"ended",seal));
            }
            var previous=checkpoints.latest(t.id()).orElse(null);
            var request=new StreamCheckpoint.Request(previous==null?null:previous.id(),previous==null?10:previous.request().serial()+1,"d".repeat(64),100,"e".repeat(64),ids);var content=request.content(t.id(),p.attemptId());
            var profile=workflowStore.definitions(executions.run(run,false).orElseThrow().workflowVersionId()).stream().filter(d->d.id().equals(t.definitionId())).findFirst().orElseThrow().serviceProfileVersionId();
            var summary=encode(Map.of("manifest",Map.of("version",1,"inputs",inputs,"outputs",outputs,"limits",Map.of("max_frames",128,"max_buffer_bytes",16777216,"max_state_bytes",262144)),
                "revision",3,"routes",cursors,"stateSha256","f".repeat(64),"stateBytes",2));
            var cp=new StreamCheckpoint(UUID.randomUUID(),run,t.id(),p.attemptId(),r.id(),p.epoch(),p.podUid(),profile,request,3,summary,
                new VerifiedArtifact("fixture-only",content.objectKey(),UUID.randomUUID().toString(),content.sha256(),content.bytes(),content.mediaType()),clock.instant(),previous!=null && !previous.attemptId().equals(p.attemptId())?previous.id():null);
            new TransactionTemplate(transactions).execute(tx->{executions.run(run,true);checkpoints.insert(cp);return null;});
            if(seal)streamExecution.complete(p,identity(p,"checkpointId",cp.id().toString()));saved.put(name,cp);
        }
        if(!seal)return saved;
        for(var pin:store.bindings(run)){var g=routes.open(pin.routeId()).orElseThrow();
            streamExecution.deviceComplete(new io.edgeai.app.config.DeviceStreamPrincipal(pin.deviceId(),pin.sessionId(),pin.epoch()),
                encode(Map.of("epoch",pin.epoch(),"generationId",g.id().toString(),"sequence",3)));}
        assertThat(saved.values()).allMatch(c->completions.granted(c.attemptId()).isPresent());return saved;
    }
    private void rejected(Definition d,Map<String,Object> request,int code)throws Exception{
        String key=UUID.randomUUID().toString();create(key,request,code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_run WHERE idempotency_key=?",Integer.class,UUID.fromString(key))).isZero();
    }
    private <T>List<T> parallel(IntFunction<T> action)throws Exception{
        try(var pool=Executors.newFixedThreadPool(4)){var start=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<4;i++){int index=i;futures.add(pool.submit(()->{start.await();return action.apply(index);}));}
            start.countDown();var result=new ArrayList<T>();for(var f:futures)result.add(f.get(15,TimeUnit.SECONDS));return result;
        }
    }

    private void drainTransfer(UUID run,UUID operation){finishPhysical(run,"source");finishPhysical(run,"sink");revokeGroup(run);offloads.advance(operation);}
    private void claimOn(UUID run,String name,UUID node){var a=attempt(run,name);var p=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),node,"transfer-"+node);
        lifecycle.submitted(a.id(),p.jobUid());lifecycle.claim(a.id(),a.epoch(),p);}

    private record Fixture(UUID run,UUID source,UUID target,UUID third,Map<String,Object> request){}
    private Map<String,Object> policy(int limit){
        var p=new TreeMap<String,Object>(Map.of("memoryPercent",90,"consecutiveSamples",2,"maxSampleAgeSeconds",30,"maxGapSeconds",10,
            "minRunningSeconds",10,"cooldownSeconds",20,"maxTransfers",limit,"drainTimeoutSeconds",60,"startTimeoutSeconds",60));
        p.put("cpuPercent",null);p.put("latencyMicros",null);return p;
    }
    private void inventory(UUID... ids){nodes.recordSnapshot(Arrays.stream(ids).map(id->new io.edgeai.domain.node.ExecutionNode(id,
        "transfer-"+id,"amd64","linux","READY","4","4Gi","{}",clock.instant())).toList(),clock.instant());}
    private Fixture fixture(int limit,boolean checkpoints,long peerDelay)throws Exception{
        UUID source=UUID.randomUUID(),target=UUID.randomUUID(),third=UUID.randomUUID();inventory(source,target,third);
        var d=definition(false,true);var body=request(d);if(limit>0)body.put("offload",policy(limit));
        body.put("execution",Map.of("mode","NODE","nodeId",source.toString()));
        UUID run=UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),body,201))).get("id"));
        claimOn(run,"source",source);clock.advance(peerDelay);claimOn(run,"sink",source);streams.prepare(run);
        if(checkpoints)checkpoint(run,false);
        return new Fixture(run,source,target,third,body);
    }
    private Fixture fixture(int limit)throws Exception{return fixture(limit,true,0);}
    private void sample(Fixture f,String name,Long limit){
        var p=principal(f.run(),name);var recent=measurements.recent(p.attemptId(),1);long sequence=recent.isEmpty()?1:recent.getFirst().sequence()+1;
        var body=new TreeMap<String,Object>(Map.of("epoch",p.epoch(),"podUid",p.podUid().toString(),"sequence",sequence,
            "observedAt",clock.instant().toString(),"intervalMillis",5000,"memoryBytes",950));
        for(String key:List.of("cpuUsageMicros","cpuLimitMillicores","latencyMicros","latencyObservedAt"))body.put(key,null);
        body.put("memoryLimitBytes",limit);telemetry.record(p,encode(body));
    }
    private void hot(Fixture f,String name){clock.advance(10);sample(f,name,1000L);clock.advance(5);sample(f,name,1000L);}
    private Optional<OffloadOperation> evaluate(Fixture f,String name){return offloads.evaluate(task(f.run(),name).id(),"automatic-stream-test");}
    private void unchanged(Fixture f){
        for(String name:List.of("source","sink")){
            assertThat(attempt(f.run(),name).state()).isEqualTo("RUNNING");
            assertThat(runtimes.byAttempt(attempt(f.run(),name).id()).orElseThrow().desiredState()).isEqualTo("RUNNING");
            assertThat(offloadStore.forTask(task(f.run(),name).id())).isEmpty();
        }
    }
    private void resumed(Fixture f,OffloadOperation o){
        drainTransfer(f.run(),o.id());
        for(String name:List.of("source","sink"))claimOn(f.run(),name,task(f.run(),name).id().equals(o.taskId())?f.target():f.source());
        assertThat(offloads.find(o.id()).state()).isEqualTo("SUCCEEDED");streams.prepare(f.run());checkpoint(f.run(),false);
    }
    @Test void publicAutomaticPolicyReplaysAndRequiresCurrentCheckpointAndFreshMeasurements()throws Exception{
        var f=fixture(1,false,0);var original=executions.run(f.run(),false).orElseThrow();
        assertThat(((Map<?,?>)json.decode(create(original.idempotencyKey().toString(),f.request(),200))).get("id")).isEqualTo(f.run().toString());
        var conflict=new TreeMap<>(f.request());conflict.put("offload",policy(2));create(original.idempotencyKey().toString(),conflict,409);
        hot(f,"source");assertThat(evaluate(f,"source")).isEmpty();unchanged(f);
        checkpoint(f.run(),false);clock.advance(31);assertThat(evaluate(f,"source")).isEmpty();unchanged(f);
        inventory(f.source(),f.target(),f.third());hot(f,"source");assertThat(evaluate(f,"source")).isPresent();
    }
    @Test void concurrentPressureInBothMembersMakesOneAuditedGroupTransferAndUsesEveryMembersBudget()throws Exception{
        var f=fixture(1);hot(f,"source");hot(f,"sink");
        assertThat(offloads.automaticCandidates("automatic-stream-test")).contains(task(f.run(),"source").id(),task(f.run(),"sink").id());
        var choices=parallel(i->evaluate(f,i%2==0?"source":"sink"));assertThat(choices.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        var o=choices.stream().flatMap(Optional::stream).findFirst().orElseThrow();assertThat(o.trigger()).isEqualTo("MEMORY");
        assertThat(o.targetNodeId()).isNull();assertThat(o.excludedNodeNames()).containsExactly("transfer-"+f.source());
        assertThat(o.members()).hasSize(2);assertThat(o.decisionJson()).contains("950","eligibleSince","policy");
        for(var m:o.members()){
            assertThat(offloadStore.forTask(m.taskId())).extracting(OffloadOperation::id).containsExactly(o.id());
            assertThat(m.targetNodeId()).isEqualTo(m.taskId().equals(o.taskId())?null:f.source());
            assertThat(m.excludedNodeNames()).isEqualTo(m.taskId().equals(o.taskId())?o.excludedNodeNames():List.of());
        }
        offloads.advance(o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("DRAINING");
        resumed(f,o);inventory(f.source(),f.target(),f.third());clock.advance(20);hot(f,"source");hot(f,"sink");
        assertThat(evaluate(f,"source")).isEmpty();assertThat(evaluate(f,"sink")).isEmpty();
        assertThat(task(f.run(),"child").state()).isEqualTo("WAITING");assertThat(task(f.run(),"independent").state()).isEqualTo("RUNNING");
    }
    @Test void allMembersWarmupMustPrecedeTheSelectedMeasurements()throws Exception{
        var f=fixture(2,true,9);clock.advance(1);sample(f,"source",1000L);clock.advance(5);sample(f,"source",1000L);
        assertThat(evaluate(f,"source")).isEmpty();unchanged(f);
        clock.advance(5);sample(f,"source",1000L);clock.advance(5);sample(f,"source",1000L);
        assertThat(evaluate(f,"source")).isPresent();
    }
    @Test void cooldownUsesFreshMeasurementsAndSelectedHistoryExcludesEveryVisitedNode()throws Exception{
        var f=fixture(2);hot(f,"source");var first=evaluate(f,"source").orElseThrow();resumed(f,first);
        hot(f,"source");assertThat(evaluate(f,"source")).isEmpty();
        clock.advance(5);sample(f,"source",1000L);assertThat(evaluate(f,"source")).isEmpty();
        clock.advance(5);sample(f,"source",1000L);inventory(f.source(),f.target());
        assertThat(evaluate(f,"source")).isEmpty();
        inventory(f.source(),f.target(),f.third());var second=evaluate(f,"source").orElseThrow();
        assertThat(second.excludedNodeNames()).containsExactlyInAnyOrder("transfer-"+f.source(),"transfer-"+f.target());
        assertThat(second.members().stream().filter(m->!m.taskId().equals(second.taskId())).findFirst().orElseThrow().targetNodeId()).isEqualTo(f.source());
    }
    @Test void missingLimitsAndCompletedGroupsNeverFenceLiveProducers()throws Exception{
        var f=fixture(1);clock.advance(10);sample(f,"source",null);clock.advance(5);sample(f,"source",null);
        assertThat(evaluate(f,"source")).isEmpty();unchanged(f);
        seal(f.run());hot(f,"source");assertThat(evaluate(f,"source")).isEmpty();unchanged(f);
    }
    @Test void databaseRejectsChangedDecisionAndPeerPlacementWithoutPersistingPartialOperations()throws Exception{
        var f=fixture(1);var now=clock.instant();var selected=task(f.run(),"source");var excluded=List.of("transfer-"+f.source());
        var members=List.of("source","sink").stream().map(name->{var t=task(f.run(),name);
            return new OffloadMember(t.id(),attempt(f.run(),name).id(),null,checkpoints.latest(t.id()).orElseThrow().id(),
                name.equals("source")?null:f.source(),null,name.equals("source")?excluded:List.<String>of());}).toList();
        for(String change:List.of("valid","policy","selected-node","selected-exclusions","peer-node","peer-exclusions")){
            var altered=members.stream().map(m->{boolean source=m.taskId().equals(selected.id());
                UUID node=(source && change.equals("selected-node") || !source && change.equals("peer-node"))?f.target():m.targetNodeId();
                var names=source && change.equals("selected-exclusions")?List.<String>of():!source && change.equals("peer-exclusions")?excluded:m.excludedNodeNames();
                return new OffloadMember(m.taskId(),m.sourceAttemptId(),null,m.checkpointId(),node,null,names);}).toList();
            var id=UUID.randomUUID();var o=new OffloadOperation(id,selected.id(),f.run(),attempt(f.run(),"source").id(),null,null,id,"sha256:"+"a".repeat(64),
                "automatic-stream-test","DRAINING",null,now.plusSeconds(60),60,null,now,now,"MEMORY",excluded,
                encode(Map.of("policy",policy(change.equals("policy")?2:1))),null,altered);
            org.assertj.core.api.ThrowableAssert.ThrowingCallable insert=()->new TransactionTemplate(transactions).execute(tx->{
                executions.run(f.run(),true);assertThat(offloadStore.create(o)).isTrue();tx.setRollbackOnly();return null;});
            if(change.equals("valid"))assertThatCode(insert).doesNotThrowAnyException();
            else assertThatThrownBy(insert).as(change).isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("Stream transfer must preserve the selected decision and peer placement");
            assertThat(offloadStore.find(id)).isEmpty();unchanged(f);
        }
    }
    @Test void mixedDagRejectsAutomaticPolicyWhenAnOrdinaryServiceCannotRestart()throws Exception{
        var nonRestart=profile(ProfileIdentity.Kind.SERVICE,json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        var a=device();var b=device();
        var d=new Definition(version(Map.of("source",service(List.of("a","b"),true,false),"sink",service(List.of("input"),false,false),
            "independent",nonRestart),List.of(edge("source","sink","output","input","STREAM"))),List.of(input(a,"source","a"),input(b,"source","b")),List.of(a,b));
        var body=request(d);body.put("offload",policy(1));rejected(d,body,409);
    }
    @Test void databaseRequiresExplicitRunOptInForAutomaticStreamOperation()throws Exception{
        var f=fixture(0);var source=task(f.run(),"source");var now=clock.instant();var excluded=List.of("transfer-"+f.source());var id=UUID.randomUUID();
        var member=new OffloadMember(source.id(),attempt(f.run(),"source").id(),null,checkpoints.latest(source.id()).orElseThrow().id(),null,null,excluded);
        var o=new OffloadOperation(id,source.id(),f.run(),member.sourceAttemptId(),null,null,id,"sha256:"+"a".repeat(64),
            "automatic-stream-test","DRAINING",null,now.plusSeconds(60),60,null,now,now,"MEMORY",excluded,"{}",null,List.of(member));
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->{executions.run(f.run(),true);return offloadStore.create(o);}))
            .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("Stream transfer must preserve the selected decision and peer placement");
        assertThat(offloadStore.find(id)).isEmpty();unchanged(f);
    }
    @Test void peerCancellationRacesAutomaticDecisionWithoutCreatingTargets()throws Exception{
        var f=fixture(1);hot(f,"source");
        parallel(i->{if(i%2==0)evaluate(f,"source");else runs.cancelTask(task(f.run(),"sink").id(),"{}");return true;});
        finishPhysical(f.run(),"source");finishPhysical(f.run(),"sink");
        // Cancelled routes are fenced by the authority worker in production; that worker is a fixture here.
        for(var r:routes.forRun(f.run(),20,0))routes.open(r.id()).ifPresent(g->{if(g.fencedAt()==null)routeLifecycle.fence(g.id(),"CANCELLED");});
        revokeGroup(f.run());for(var o:offloadStore.forTask(task(f.run(),"source").id()))offloads.advance(o.id());
        for(String name:List.of("source","sink"))assertThat(executions.attempts(task(f.run(),name).id())).hasSize(1);
        assertThat(task(f.run(),"sink").state()).isEqualTo("CANCELLED");
        assertThat(offloadStore.forTask(task(f.run(),"source").id())).allMatch(o->o.state().equals("CANCELLED") && o.members().stream().allMatch(m->m.targetAttemptId()==null));
    }
}
