package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.config.StreamConnectionSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.stream.*;
import java.nio.file.*;
import java.time.*;
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
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.runtime.namespace=public-stream-test",
    "edgeai.stream.enabled=true","edgeai.stream.bindings-enabled=true","edgeai.stream.runs-enabled=true",
    "edgeai.stream.broker-digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","edgeai.stream.lease-seconds=120"})
@AutoConfigureMockMvc
class StreamRunIntegrationTest {
    private static final Path KEY=key();
    private static Path key(){try{
        var p=Files.createTempFile("edgeai-public-stream-",".key",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.writeString(p,HexFormat.of().formatHex(bytes));return p;
    }catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("edgeai.runtime.key-file",KEY::toString);p.add("edgeai.stream.device-key-file",KEY::toString);}
    @AfterAll static void removeKey()throws Exception{Files.delete(KEY);}
    @MockitoBean RuntimeGateway gateway;@MockitoBean S3ArtifactStore storage;@MockitoBean MosquittoStreamBroker broker;
    @MockitoBean StreamAuthorityWorker authorityWorker;@MockitoBean StreamConnectionSettings connection;@MockitoBean StreamRunWorker scheduledWorker;
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired DeviceService devices;@Autowired DeviceRepository deviceStore;
    @Autowired ExecutionService runs;@Autowired ExecutionRepository executions;@Autowired RuntimeLifecycleService lifecycle;@Autowired RuntimeRepository runtimes;
    @Autowired StreamRunService streams;@Autowired StreamRunRepository store;@Autowired DataRouteRepository routes;@Autowired DataRouteService routeLifecycle;
    @Autowired StreamExecutionRepository completions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired PlatformTransactionManager transactions;
    @Autowired DeviceStreamTokenService deviceTokens;
    @Autowired WorkflowRepository workflowStore;
    @Autowired StreamExecutionService streamExecution;@Autowired StreamCheckpointRepository checkpoints;
    private final JsonDocuments json=new JsonDocuments();
    private record Definition(UUID version,List<Map<String,Object>> inputs,List<Device> devices){}
    private String encode(Object value){return json.canonical(value);}
    private UUID profile(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,encode(Map.of("key","public-stream-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    @SuppressWarnings("unchecked") private UUID service(List<String> inputs,boolean output,boolean fileInput)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/"+(inputs==null?"service-execution":"service-stream")+".example.json")));
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
    private UUID create(Definition d)throws Exception{return UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),request(d),201))).get("id"));}
    private MockHttpServletRequestBuilder deviceRoutes(DeviceSession session,UUID run,int limit,int offset){
        return post("/internal/v1/devices/"+session.deviceId()+"/sessions/"+session.id()+"/streams/routes")
            .header("Authorization","Bearer "+deviceTokens.issue(session)).contentType("application/json")
            .content(encode(Map.of("epoch",session.epoch(),"runId",run.toString(),"limit",limit,"offset",offset)));
    }
    private Task task(UUID run,String name){return executions.tasks(run).stream().filter(t->t.key().equals(name)).findFirst().orElseThrow();}
    private TaskAttempt attempt(UUID run,String name){return executions.attempts(task(run,name).id()).getFirst();}
    private RuntimeLifecycleService.Assignment claim(UUID run,String name){var a=attempt(run,name);var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(a.id(),pod.jobUid());return lifecycle.claim(a.id(),a.epoch(),pod);}
    /** Internal recovery fixture; public retry remains rejected until finalization and source reconnect are integrated. */
    private UUID recoveryRun(Definition d,int attempts)throws Exception{
        return new TransactionTemplate(transactions).execute(tx->{
            var inputs=io.edgeai.app.support.StreamRunInput.parse(json.decode(encode(d.inputs())));
            var sessions=streams.pin(inputs,"AUTO",RetryPolicy.disabled(),false);var now=Instant.now();
            var run=new WorkflowRun(UUID.randomUUID(),d.version(),UUID.randomUUID(),"sha256:"+"a".repeat(64),"AUTO",null,"{}",
                new RetryPolicy(attempts,1,300,Set.of("RUNTIME_LOST")),null,"PENDING",now,now);
            assertThat(executions.create(run)).isTrue();executions.initialize(run,workflowStore.definitions(d.version()),Set.of());
            streams.configure(run,"public-stream-test",inputs,sessions);lifecycle.startRun(run.id(),"public-stream-test");return run.id();
        });
    }
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
    private void due(UUID run)throws Exception{
        var latest=executions.tasks(run).stream().map(t->executions.retry(t.id())).flatMap(Optional::stream)
            .map(TaskRetry::availableAt).max(Comparator.naturalOrder()).orElseThrow();
        long millis=Duration.between(Instant.now(),latest).toMillis();if(millis>=0)Thread.sleep(millis+20);
    }
    private void worker(){new StreamRunWorker(store,streams,lifecycle,"public-stream-test").tick();}
    private io.edgeai.app.config.RunnerPrincipal principal(UUID run,String name){
        var r=runtimes.byAttempt(attempt(run,name).id()).orElseThrow();
        return new io.edgeai.app.config.RunnerPrincipal(r.attemptId(),r.epoch(),new RuntimePod(r.jobUid(),r.producerPodUid(),r.nodeUid(),r.nodeName()));
    }
    private String identity(io.edgeai.app.config.RunnerPrincipal p,Object... extra){
        var value=new TreeMap<String,Object>(Map.of("epoch",p.epoch(),"podUid",p.podUid().toString()));
        for(int i=0;i<extra.length;i+=2)value.put((String)extra[i],extra[i+1]);return encode(value);
    }
    /** Checkpoint bytes/S3 receipt are fixtures here; actual signed-object finalization is tested separately. */
    private Map<String,StreamCheckpoint> seal(UUID run){
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
                cursors.add(Map.of("routeId",route.id().toString(),"received",3,"committed",3,"ended",true));
            }
            var request=new StreamCheckpoint.Request(null,10,"d".repeat(64),100,"e".repeat(64),ids);var content=request.content(t.id(),p.attemptId());
            var profile=workflowStore.definitions(executions.run(run,false).orElseThrow().workflowVersionId()).stream().filter(d->d.id().equals(t.definitionId())).findFirst().orElseThrow().serviceProfileVersionId();
            var summary=encode(Map.of("manifest",Map.of("version",1,"inputs",inputs,"outputs",outputs,"limits",Map.of("max_frames",128,"max_buffer_bytes",16777216,"max_state_bytes",262144)),
                "revision",3,"routes",cursors,"stateSha256","f".repeat(64),"stateBytes",2));
            var cp=new StreamCheckpoint(UUID.randomUUID(),run,t.id(),p.attemptId(),r.id(),p.epoch(),p.podUid(),profile,request,3,summary,
                new VerifiedArtifact("fixture-only",content.objectKey(),UUID.randomUUID().toString(),content.sha256(),content.bytes(),content.mediaType()),Instant.now(),null);
            new TransactionTemplate(transactions).execute(tx->{executions.run(run,true);checkpoints.insert(cp);return null;});
            streamExecution.complete(p,identity(p,"checkpointId",cp.id().toString()));saved.put(name,cp);
        }
        for(var pin:store.bindings(run)){var g=routes.open(pin.routeId()).orElseThrow();
            streamExecution.deviceComplete(new io.edgeai.app.config.DeviceStreamPrincipal(pin.deviceId(),pin.sessionId(),pin.epoch()),
                encode(Map.of("epoch",pin.epoch(),"generationId",g.id().toString(),"sequence",3)));}
        assertThat(saved.values()).allMatch(c->completions.granted(c.attemptId()).isPresent());return saved;
    }
    private void commitFinalizer(UUID run,String name){
        var p=principal(run,name);var output=new ResultManifest.Output("result",2,"a".repeat(64),"application/json","fixture-version");
        var permit=lifecycle.prepareCommit(p.attemptId(),p.epoch(),p.podUid(),new ResultManifest(List.of(output)));
        var content=output.content(task(run,name).id(),p.attemptId());
        lifecycle.commitVerified(permit,List.of(new TaskResult.Output("result",new VerifiedArtifact("fixture-only",content.objectKey(),output.versionId(),content.sha256(),content.bytes(),content.mediaType()))));
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

    @Test void publicCreationPinsSessionsDispatchesWholeGroupAndReplaysReorderedInputs()throws Exception{
        var d=definition(false,false);String key=UUID.randomUUID().toString();var body=request(d);
        var response=(Map<?,?>)json.decode(create(key,body,201));UUID id=UUID.fromString((String)response.get("id"));
        assertThat(response.get("state")).isEqualTo("RUNNING");
        assertThat(executions.tasks(id)).hasSize(2).allMatch(t->t.state().equals("RUNNING"));
        for(String name:List.of("source","sink")){assertThat(attempt(id,name).state()).isEqualTo("DISPATCHING");assertThat(runtimes.byAttempt(attempt(id,name).id())).isPresent();}
        assertThat(routes.forRun(id,20,0)).hasSize(3).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        assertThat(store.bindings(id)).hasSize(2).allMatch(b->deviceStore.activeSession(b.deviceId()).orElseThrow().id().equals(b.sessionId()));
        assertThat(completions.bindingDigest(id)).isPresent();
        var reverse=new ArrayList<>(d.inputs());Collections.reverse(reverse);body.put("streamInputs",reverse);
        assertThat(((Map<?,?>)json.decode(create(key,body,200))).get("id")).isEqualTo(id.toString());
        body.put("streamInputs",List.of(d.inputs().getFirst()));assertThat(create(key,body,409)).contains("IDEMPOTENCY_CONFLICT");
    }
    @Test void grantedFinalizerRetriesAloneAfterPhysicalAndBrokerBarriersAndRetainsOriginalGrant()throws Exception{
        UUID run=recoveryRun(definition(false,true),3);claim(run,"source");claim(run,"sink");streams.prepare(run);var sealed=seal(run);
        var original=principal(run,"sink");var grant=completions.granted(original.attemptId()).orElseThrow();commitFinalizer(run,"source");
        // The broker worker is mocked in this PG test: model its closure of the completed source's Device routes.
        for(var route:routes.forRun(run,20,0))if(route.deviceSource()){
            var g=routes.open(route.id()).orElseThrow();routeLifecycle.fence(g.id(),"COMPLETED");
            routeLifecycle.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));
        }
        for(int round=2;round<=3;round++){
            var before=principal(run,"sink");lifecycle.observeFailure(before.attemptId(),"RUNTIME_LOST");due(run);
            assertThat(task(run,"source").state()).isEqualTo("SUCCEEDED");assertThat(executions.attempts(task(run,"source").id())).hasSize(1);
            assertThat(task(run,"child").state()).isEqualTo("WAITING");assertThat(task(run,"independent").state()).isEqualTo("RUNNING");
            assertThat(lifecycle.retryTask(task(run,"sink").id())).isFalse();finishPhysical(run,"sink");
            if(round==2){assertThat(lifecycle.retryTask(task(run,"sink").id())).isFalse();revokeGroup(run);}
            assertThat(parallel(i->lifecycle.retryTask(task(run,"sink").id()))).containsOnlyOnce(true);
            claim(run,"sink");assertThat(streams.prepare(run)).isEmpty();
            var p=principal(run,"sink");assertThat(p.epoch()).isEqualTo(round);
            var reply=(Map<?,?>)streamExecution.execution(p,identity(p));assertThat(reply.get("state")).isEqualTo("FINALIZE");
            assertThat(reply.get("checkpointActor")).isEqualTo(Map.of("attemptId",original.attemptId().toString(),"epoch",original.epoch()));
            assertThat(streamExecution.finalized(p,identity(p,"checkpointId",sealed.get("sink").id().toString())).id()).isEqualTo(sealed.get("sink").id());
            assertThat(completions.granted(p.attemptId()).orElseThrow()).isEqualTo(grant);assertThat(completions.task(p.attemptId())).isEmpty();
            assertThatThrownBy(()->streamExecution.finalized(p,identity(p,"checkpointId",sealed.get("source").id().toString()))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
            assertThatThrownBy(()->streamExecution.execution(before,identity(before))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
            assertThat(routes.forRun(run,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1 && routes.open(r.id()).isEmpty());
            assertThatThrownBy(()->jdbc.update("UPDATE edgeai.stream_finalization_recovery SET granted_attempt_id=? WHERE attempt_id=?",sealed.get("source").attemptId(),p.attemptId())).isInstanceOf(DataIntegrityViolationException.class);
        }
        var last=principal(run,"sink");commitFinalizer(run,"sink");
        assertThat(runtimes.result(task(run,"sink").id()).orElseThrow().attemptId()).isEqualTo(last.attemptId());
        assertThat(task(run,"child").state()).isEqualTo("RUNNING");assertThat(checkpoints.latest(task(run,"sink").id()).orElseThrow().id()).isEqualTo(sealed.get("sink").id());
    }
    @Test void finalizerRetryCancellationAndBudgetDoNotTurnSealedStateIntoLateSuccess()throws Exception{
        for(boolean cancel:List.of(false,true)){
            UUID run=recoveryRun(definition(false,false),2);claim(run,"source");claim(run,"sink");streams.prepare(run);var sealed=seal(run);
            var old=principal(run,"source");lifecycle.observeFailure(old.attemptId(),"RUNTIME_LOST");finishPhysical(run,"source");
            // Only the failed finalizer's routes are fenced; here source touches the entire component.
            revokeGroup(run);due(run);assertThat(lifecycle.retryTask(task(run,"source").id())).isTrue();claim(run,"source");
            var current=principal(run,"source");
            if(cancel)runs.cancelRun(run,"{}");else lifecycle.observeFailure(current.attemptId(),"RUNTIME_LOST");
            assertThat(executions.retry(task(run,"source").id())).isEmpty();assertThat(lifecycle.retryTask(task(run,"source").id())).isFalse();
            assertThatThrownBy(()->streamExecution.finalized(current,identity(current,"checkpointId",sealed.get("source").id().toString()))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
            assertThat(runtimes.result(task(run,"source").id())).isEmpty();
        }
    }
    @Test void databaseRejectsForgedFinalizerLineageAndUnfinishedPhysicalOrBrokerRevocation()throws Exception{
        UUID run=recoveryRun(definition(false,false),2);claim(run,"source");claim(run,"sink");streams.prepare(run);seal(run);
        var old=principal(run,"source");var task=task(run,"source");lifecycle.observeFailure(old.attemptId(),"RUNTIME_LOST");
        org.assertj.core.api.ThrowableAssert.ThrowingCallable inherit=()->new TransactionTemplate(transactions).execute(tx->{
            executions.run(run,true);var next=executions.startRetry(task.id(),Instant.now());
            completions.inheritFinalization(next.id(),old.attemptId(),Instant.now());return null;
        });
        assertThatThrownBy(inherit).isInstanceOf(DataIntegrityViolationException.class); // old producer still running
        lifecycle.confirmStopped(old.attemptId());
        assertThatThrownBy(inherit).isInstanceOf(DataIntegrityViolationException.class); // CREATE can still be in flight
        jdbc.update("UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=? AND kind='CREATE'",runtimes.byAttempt(old.attemptId()).orElseThrow().id());
        assertThatThrownBy(inherit).isInstanceOf(DataIntegrityViolationException.class); // old ACLs not yet revoked
        revokeGroup(run);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->{
            executions.run(run,true);var next=executions.startRetry(task.id(),Instant.now());
            jdbc.update("INSERT INTO edgeai.stream_finalization_recovery(attempt_id,predecessor_attempt_id,granted_attempt_id,created_at) VALUES (?,?,?,now())",
                next.id(),old.attemptId(),attempt(run,"sink").id());return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(executions.attempts(task.id())).hasSize(1);due(run);assertThat(lifecycle.retryTask(task.id())).isTrue();
    }
    @Test void concurrentCreationAndRestartedPreparationCreateOneRunAndOneGenerationPerRoute()throws Exception{
        var d=definition(false,false);String key=UUID.randomUUID().toString();String body=encode(request(d));
        var created=parallel(i->runs.create(key,body));assertThat(created.stream().filter(Creation::created)).hasSize(1);
        UUID id=created.getFirst().value().id();assertThat(created).allMatch(c->c.value().id().equals(id));
        claim(id,"source");worker();assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        claim(id,"sink");parallel(i->streams.prepare(id));worker();
        for(var route:routes.forRun(id,20,0)){
            var history=routes.history(route.id(),20,0);assertThat(history).hasSize(1);var g=history.getFirst();
            assertThat(g.state()).isEqualTo("PREPARING");assertThat(g.consumer().id()).isEqualTo(attempt(id,route.deviceSource()?"source":"sink").id());
            if(route.deviceSource())assertThat(store.bindings(id).stream().filter(b->b.routeId().equals(route.id())).findFirst().orElseThrow().sessionId()).isEqualTo(g.producer().id());
            else assertThat(g.producer().id()).isEqualTo(attempt(id,"source").id());
        }
    }
    @Test void everyBatchPredecessorMustHaveSealedResultBeforeAnyStreamMemberStarts()throws Exception{
        var d=definition(true,false);UUID id=create(d);var prep=claim(id,"prep");
        assertThat(task(id,"source").state()).isEqualTo("WAITING");assertThat(task(id,"sink").state()).isEqualTo("WAITING");
        // Deliberately incomplete state fixture proves state alone cannot release a group.
        jdbc.update("UPDATE edgeai.task SET state='SUCCEEDED' WHERE id=?",task(id,"prep").id());streams.releaseReady(id);
        assertThat(executions.attempts(task(id,"source").id())).isEmpty();assertThat(executions.attempts(task(id,"sink").id())).isEmpty();
        jdbc.update("UPDATE edgeai.task SET state='RUNNING' WHERE id=?",task(id,"prep").id());
        var output=new ResultManifest.Output("output",2,"a".repeat(64),"application/json","fixture-version");
        var permit=lifecycle.prepareCommit(prep.runtime().attemptId(),1,prep.runtime().producerPodUid(),new ResultManifest(List.of(output)));
        var content=output.content(task(id,"prep").id(),prep.runtime().attemptId());
        lifecycle.commitVerified(permit,List.of(new TaskResult.Output("output",new VerifiedArtifact("fixture-only",content.objectKey(),output.versionId(),content.sha256(),content.bytes(),content.mediaType()))));
        assertThat(attempt(id,"source").state()).isEqualTo("DISPATCHING");assertThat(attempt(id,"sink").state()).isEqualTo("DISPATCHING");
        assertThat(claim(id,"source").inputs()).isEmpty();assertThat(claim(id,"sink").inputs()).hasSize(1);
        streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1);
    }
    @Test void sameDeviceFanoutSharesComponentAndWaitsForBothClaims()throws Exception{
        var device=device();var spec=service(List.of("input"),false,false);var d=new Definition(version(Map.of("a",spec,"b",spec),List.of()),
            List.of(input(device,"a","input"),input(device,"b","input")),List.of(device));
        UUID id=create(d);var records=streams.list(id,20,0);assertThat(records.stream().map(r->r.componentId()).distinct()).hasSize(1);
        claim(id,"a");streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        claim(id,"b");streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1);
    }
    @Test void missingDuplicateUnknownOversizedAndDependencyReplacingInputsRollBackPublicCreation()throws Exception{
        var d=definition(false,false);
        for(var inputs:List.of(List.of(d.inputs().getFirst()),List.of(d.inputs().getFirst(),d.inputs().getFirst()),
                List.of(input(d.devices().getFirst(),"missing","a")),List.of(input(d.devices().getFirst(),"sink","input")))){
            var body=request(d);body.put("streamInputs",inputs);rejected(d,body,400);
        }
        for(Object budget:List.of(4097,262145,0,1.5,"4096")){
            var input=new TreeMap<>(d.inputs().getFirst());input.put("maxPayloadBytes",budget);var body=request(d);body.put("streamInputs",List.of(input,d.inputs().get(1)));rejected(d,body,400);
        }
        var extra=new TreeMap<>(d.inputs().getFirst());extra.put("sessionId",UUID.randomUUID().toString());var body=request(d);body.put("streamInputs",List.of(extra,d.inputs().get(1)));rejected(d,body,400);
    }
    @Test void unsupportedRecoveryAndInactiveOrMissingDeviceCannotCreatePartialRuns()throws Exception{
        var d=definition(false,false);var body=request(d);
        body.put("execution",Map.of("mode","VD","vdId",UUID.randomUUID().toString()));rejected(d,body,409);
        body=request(d);body.put("execution",Map.of("mode","REMOTE","providerKey","reference"));rejected(d,body,409);
        body=request(d);body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",30,"retryOn",List.of("WORKLOAD_FAILED")));rejected(d,body,409);
        var missing=new TreeMap<>(d.inputs().getFirst());missing.put("deviceId",UUID.randomUUID().toString());body=request(d);body.put("streamInputs",List.of(missing,d.inputs().get(1)));rejected(d,body,404);
        devices.release(d.devices().getFirst().id());rejected(d,request(d),409);
    }
    @Test void batchWithinDeviceFanoutIsRejectedBeforeAnyRuntimeCommand()throws Exception{
        var device=device();var spec=service(List.of("input"),false,true);
        // A shared source joins both tasks despite the second task's required BATCH input.
        var a=service(List.of("input"),false,false);
        var d=new Definition(version(Map.of("a",a,"b",spec),List.of(edge("a","b","result","file-input","BATCH"))),
            List.of(input(device,"a","input"),input(device,"b","input")),List.of(device));
        rejected(d,request(d),400);
    }
    @Test void nodePolicyPinsEveryMemberAndRejectsClaimsOnAnotherNode()throws Exception{
        UUID node=UUID.randomUUID();String name="stream-node-"+UUID.randomUUID();
        jdbc.update("INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES (?,?,'amd64','linux','READY','1','1Gi','{}',now())",node,name);
        var d=definition(false,false);var request=request(d);request.put("execution",Map.of("mode","NODE","nodeId",node.toString()));
        UUID id=UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),request,201))).get("id"));
        for(String key:List.of("source","sink")){
            var a=attempt(id,key);assertThat(a.nodeId()).isEqualTo(node);assertThat(a.mode()).isEqualTo("NODE");
            UUID job=UUID.randomUUID();lifecycle.submitted(a.id(),job);
            assertThatThrownBy(()->lifecycle.claim(a.id(),1,new RuntimePod(job,UUID.randomUUID(),UUID.randomUUID(),name)))
                .isInstanceOfSatisfying(io.edgeai.app.exception.ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("PRODUCER_FENCED"));
            lifecycle.claim(a.id(),1,new RuntimePod(job,UUID.randomUUID(),node,name));
        }
        streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1);
    }
    @Test void sessionReplacementFailsWholeGroupAndNeverRepinsOrGenerates()throws Exception{
        var d=definition(false,true);UUID id=create(d);var before=store.bindings(id);claim(id,"source");
        devices.openSession(d.devices().getFirst().id(),encode(Map.of("bootId",UUID.randomUUID().toString())));worker();
        assertThat(store.bindings(id)).isEqualTo(before);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        assertThat(List.of(task(id,"source").state(),task(id,"sink").state())).containsExactlyInAnyOrder("FAILED","CANCELLING");
        assertThat(task(id,"child").state()).isEqualTo("SKIPPED");assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
        assertThat(runtimes.byAttempt(attempt(id,"independent").id()).orElseThrow().desiredState()).isEqualTo("RUNNING");
    }
    @Test void cancelledConsumerStopsItsUpstreamPeerAndDescendantsButPreservesIndependentBranch()throws Exception{
        UUID id=create(definition(false,true));claim(id,"source");claim(id,"sink");streams.prepare(id);
        perform(post("/api/v1/tasks/"+task(id,"sink").id()+"/cancel").contentType("application/json").content("{}"),200);
        assertThat(task(id,"source").state()).isEqualTo("CANCELLING");assertThat(task(id,"child").state()).isEqualTo("SKIPPED");
        assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
        for(String name:List.of("source","sink")){assertThat(runtimes.byAttempt(attempt(id,name).id()).orElseThrow().desiredState()).isEqualTo("STOPPED");lifecycle.confirmStopped(attempt(id,name).id());}
        assertThat(task(id,"source").state()).isEqualTo("SKIPPED");assertThat(task(id,"sink").state()).isEqualTo("CANCELLED");
        streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1);
    }
    @Test void revokedGenerationFailsGroupWithoutSilentlyCreatingANewGeneration()throws Exception{
        UUID id=create(definition(false,true));claim(id,"source");claim(id,"sink");streams.prepare(id);
        var route=routes.forRun(id,20,0).getFirst();var g=routes.history(route.id(),1,0).getFirst();
        routeLifecycle.fence(g.id(),"LEASE_EXPIRED");routeLifecycle.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));worker();worker();
        assertThat(routes.history(route.id(),20,0)).hasSize(1);assertThat(routes.open(route.id())).isEmpty();
        assertThat(List.of(task(id,"source").state(),task(id,"sink").state())).containsExactlyInAnyOrder("FAILED","CANCELLING");
        assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
    }
    @Test void unfinishedRecoveryFencesTheWholeGroupAndWaitsForAllPhysicalStopsAndBrokerRevocations()throws Exception{
        UUID id=recoveryRun(definition(false,true),2);claim(id,"source");claim(id,"sink");streams.prepare(id);
        var pins=store.bindings(id);var old=new HashMap<UUID,RouteGeneration>();
        routes.forRun(id,20,0).forEach(r->old.put(r.id(),routes.open(r.id()).orElseThrow()));
        lifecycle.observeFailure(attempt(id,"sink").id(),"RUNTIME_LOST");
        assertThat(task(id,"source").state()).isEqualTo("RETRY_WAIT");assertThat(task(id,"sink").state()).isEqualTo("RETRY_WAIT");
        assertThat(task(id,"independent").state()).isEqualTo("RUNNING");assertThat(task(id,"child").state()).isEqualTo("WAITING");
        var a=executions.retry(task(id,"source").id()).orElseThrow();var b=executions.retry(task(id,"sink").id()).orElseThrow();
        assertThat(a.availableAt()).isEqualTo(b.availableAt());assertThat(a.deadline()).isEqualTo(b.deadline());
        assertThat(routes.forRun(id,20,0)).allMatch(r->routes.open(r.id()).orElseThrow().state().equals("FENCED"));
        assertThat(runtimes.byAttempt(attempt(id,"source").id()).orElseThrow().failureReason()).isEqualTo("STREAM_GROUP_RESTART");
        assertThat(lifecycle.retryTask(task(id,"source").id())).isFalse();due(id);
        finishPhysical(id,"source");assertThat(lifecycle.retryTask(task(id,"source").id())).isFalse();
        finishPhysical(id,"sink");assertThat(lifecycle.retryTask(task(id,"sink").id())).isFalse();
        revokeGroup(id);
        var results=parallel(i->lifecycle.retryTask(task(id,i%2==0?"source":"sink").id()));assertThat(results).containsOnlyOnce(true);
        for(String name:List.of("source","sink")){
            assertThat(executions.attempts(task(id,name).id())).hasSize(2);assertThat(attempt(id,name).epoch()).isEqualTo(2);
            assertThat(attempt(id,name).cause()).isEqualTo("RETRY");assertThat(executions.retry(task(id,name).id())).isEmpty();claim(id,name);
        }
        assertThat(streams.prepare(id)).isEmpty();assertThat(store.bindings(id)).isEqualTo(pins);
        for(var route:routes.forRun(id,20,0)){
            var g=routes.open(route.id()).orElseThrow();assertThat(g.generation()).isEqualTo(2);assertThat(g.consumer().epoch()).isEqualTo(2);
            assertThat(g.consumer().id()).isNotEqualTo(old.get(route.id()).consumer().id());
            if(route.deviceSource())assertThat(g.producer()).isEqualTo(old.get(route.id()).producer());else assertThat(g.producer().epoch()).isEqualTo(2);
        }
        assertThat(executions.attempts(task(id,"independent").id())).hasSize(1);assertThat(executions.attempts(task(id,"child").id())).isEmpty();
    }
    @Test void simultaneousPeerFailuresReserveExactlyOneGroupRetryAndCancellationPreventsReassignment()throws Exception{
        UUID id=recoveryRun(definition(false,true),2);claim(id,"source");claim(id,"sink");streams.prepare(id);
        parallel(i->{lifecycle.observeFailure(attempt(id,i%2==0?"source":"sink").id(),"RUNTIME_LOST");return true;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.task_retry r JOIN edgeai.task t ON t.id=r.task_id WHERE t.run_id=?",Integer.class,id)).isEqualTo(2);
        perform(post("/api/v1/tasks/"+task(id,"sink").id()+"/cancel").contentType("application/json").content("{}"),200);
        finishPhysical(id,"source");finishPhysical(id,"sink");revokeGroup(id);
        for(String name:List.of("source","sink")){
            assertThat(lifecycle.retryTask(task(id,name).id())).isFalse();assertThat(executions.attempts(task(id,name).id())).hasSize(1);
            assertThat(executions.retry(task(id,name).id())).isEmpty();
        }
        assertThat(task(id,"child").state()).isEqualTo("SKIPPED");assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
    }
    @Test void groupRetryExhaustionAndExpiredWindowNeverRestartOnlyOnePeer()throws Exception{
        UUID id=recoveryRun(definition(false,true),2);claim(id,"source");claim(id,"sink");streams.prepare(id);
        lifecycle.observeFailure(attempt(id,"source").id(),"RUNTIME_LOST");finishPhysical(id,"source");finishPhysical(id,"sink");revokeGroup(id);due(id);
        assertThat(lifecycle.retryTask(task(id,"sink").id())).isTrue();claim(id,"source");claim(id,"sink");streams.prepare(id);
        lifecycle.observeFailure(attempt(id,"sink").id(),"RUNTIME_LOST");
        assertThat(task(id,"sink").state()).isEqualTo("FAILED");assertThat(task(id,"source").state()).isEqualTo("CANCELLING");
        assertThat(task(id,"child").state()).isEqualTo("SKIPPED");assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
        for(String name:List.of("source","sink")){assertThat(executions.retry(task(id,name).id())).isEmpty();assertThat(executions.attempts(task(id,name).id())).hasSize(2);}
        UUID expired=recoveryRun(definition(false,true),3);claim(expired,"source");claim(expired,"sink");streams.prepare(expired);
        lifecycle.observeFailure(attempt(expired,"source").id(),"RUNTIME_LOST");
        // Persisted clock fixture exercises restart-time deadline enforcement without waiting 300 seconds.
        jdbc.update("UPDATE edgeai.task_retry SET available_at=now()-interval '2 seconds',deadline=now()-interval '1 second' WHERE task_id IN (SELECT id FROM edgeai.task WHERE run_id=?)",expired);
        assertThat(lifecycle.retryTask(task(expired,"sink").id())).isTrue();
        assertThat(task(expired,"child").state()).isEqualTo("SKIPPED");assertThat(task(expired,"independent").state()).isEqualTo("RUNNING");
        for(String name:List.of("source","sink")){assertThat(executions.retry(task(expired,name).id())).isEmpty();assertThat(executions.attempts(task(expired,name).id())).hasSize(1);}
    }
    @Test void routeInspectionIsAuthenticatedPaginatedAndContainsOnlyProvenanceAndStatus()throws Exception{
        UUID id=create(definition(false,false));String path="/api/v1/workflow-runs/"+id+"/streams";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var first=(Map<?,?>)json.decode(perform(get(path+"?limit=2"),200));assertThat((List<?>)first.get("items")).hasSize(2);assertThat(first.get("nextOffset").toString()).isEqualTo("2");
        var last=(Map<?,?>)json.decode(perform(get(path+"?limit=2&offset=2"),200));assertThat((List<?>)last.get("items")).hasSize(1);assertThat(last.get("nextOffset")).isNull();
        assertThat(perform(get(path+"?limit=101"),400)).contains("INVALID_WORKFLOW");assertThat(perform(get("/api/v1/workflow-runs/"+UUID.randomUUID()+"/streams"),404)).contains("RUN_NOT_FOUND");
        claim(id,"source");claim(id,"sink");streams.prepare(id);
        String response=perform(get(path),200);assertThat(response).contains("PREPARING","SYNTHETIC","sourceSessionId","componentId").doesNotContain("password","mqtts://","Authorization","signedUrl");
    }
    @Test void deviceTokenDiscoversOnlyItsPinnedRoutesAndReadsPreparationWithoutRenewingAuthority()throws Exception{
        var d=definition(false,false);UUID run=create(d);var session=deviceStore.activeSession(d.devices().getFirst().id()).orElseThrow();
        var initial=mvc.perform(deviceRoutes(session,run,100,0)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.apiVersion").value("edgeai.device-routes/v1")).andExpect(jsonPath("$.runId").value(run.toString()))
            .andExpect(jsonPath("$.sessionId").value(session.id().toString())).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].generation").isEmpty()).andExpect(jsonPath("$.nextOffset").isEmpty())
            .andReturn().getResponse().getContentAsString();
        assertThat(initial).doesNotContain("password","credential","token","mqtt","signedUrl",d.devices().get(1).id().toString());
        assertThat(routes.forRun(run,20,0)).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        claim(run,"source");claim(run,"sink");streams.prepare(run);
        var before=routes.forRun(run,20,0).stream().map(r->routes.history(r.id(),1,0).getFirst()).toList();
        mvc.perform(deviceRoutes(session,run,100,0)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].generation.state").value("PREPARING"));
        assertThat(routes.forRun(run,20,0).stream().map(r->routes.history(r.id(),1,0).getFirst()).toList()).isEqualTo(before);
        var foreign=deviceStore.activeSession(device().id()).orElseThrow();
        mvc.perform(deviceRoutes(foreign,run,100,0)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STREAM_DEVICE_RUN_NOT_FOUND"));
        mvc.perform(deviceRoutes(session,UUID.randomUUID(),100,0)).andExpect(status().isNotFound());
    }
    @Test void deviceRoutePagesPreserveFanoutAndDoNotExposeOtherSources()throws Exception{
        var source=device();var spec=service(List.of("input"),false,false);
        var d=new Definition(version(Map.of("a",spec,"b",spec),List.of()),List.of(input(source,"a","input"),input(source,"b","input")),List.of(source));
        UUID run=create(d);var session=deviceStore.activeSession(source.id()).orElseThrow();var found=new ArrayList<String>();
        for(int offset=0;offset<2;offset++){
            var page=(Map<?,?>)json.decode(mvc.perform(deviceRoutes(session,run,1,offset)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn().getResponse().getContentAsString());
            found.add((String)((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("routeId"));
            assertThat(page.get("nextOffset")==null?null:page.get("nextOffset").toString()).isEqualTo(offset==0?"1":null);
        }
        assertThat(found).doesNotHaveDuplicates().isSorted();
        mvc.perform(deviceRoutes(session,run,1,2)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.nextOffset").isEmpty());
        for(int limit:List.of(0,101))mvc.perform(deviceRoutes(session,run,limit,0)).andExpect(status().isBadRequest());
        mvc.perform(deviceRoutes(session,run,100,1000001)).andExpect(status().isBadRequest());
    }
    @Test void deviceRouteDiscoveryRejectsManagementIdentityAndRotatedOrForeignSession()throws Exception{
        var d=definition(false,false);UUID run=create(d);var session=deviceStore.activeSession(d.devices().getFirst().id()).orElseThrow();
        var path="/internal/v1/devices/"+session.deviceId()+"/sessions/"+session.id()+"/streams/routes";
        mvc.perform(post(path).with(user("manager")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+deviceTokens.issue(session))).andExpect(status().isUnauthorized());
        var wrong=encode(Map.of("epoch",session.epoch()+1,"runId",run.toString(),"limit",100,"offset",0));
        mvc.perform(deviceRoutes(session,run,100,0).content(wrong)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STREAM_DEVICE_SCOPE_CHANGED"));
        devices.openSession(session.deviceId(),encode(Map.of("bootId",UUID.randomUUID().toString())));
        mvc.perform(deviceRoutes(session,run,100,0)).andExpect(status().isUnauthorized());
        var next=deviceStore.activeSession(session.deviceId()).orElseThrow();
        mvc.perform(deviceRoutes(next,run,100,0)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STREAM_DEVICE_SCOPE_CHANGED"));
    }
    @Test void provisioningAndDevicePinsAreImmutableAndCannotBeRetrofittedOntoBatchRuns()throws Exception{
        UUID id=create(definition(false,false));var pin=store.bindings(id).getFirst();
        for(String table:List.of("stream_run_configuration","stream_device_binding")){
            assertThatThrownBy(()->jdbc.update("UPDATE edgeai."+table+" SET run_id=run_id WHERE run_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM edgeai."+table+" WHERE run_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->{s.setRollbackOnly();jdbc.execute("TRUNCATE edgeai."+table+" CASCADE");return null;})).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.stream_device_binding(route_id,run_id,device_id,session_id,epoch) VALUES (?,?,?,?,?)",UUID.randomUUID(),id,pin.deviceId(),pin.sessionId(),pin.epoch())).isInstanceOf(DataIntegrityViolationException.class);
        var version=version(Map.of("batch",service(null,false,false)),List.of());
        var batch=runs.create(UUID.randomUUID().toString(),encode(Map.of("workflowVersionId",version.toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of()))).value();
        assertThatThrownBy(()->store.create(new StreamRunRepository.Configuration(batch.id(),"public-stream-test","sha256:"+"b".repeat(64),120,Instant.now()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.find(batch.id())).isEmpty();
    }
}
