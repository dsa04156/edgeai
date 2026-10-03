package io.edgeai.app.integration;

import io.edgeai.app.config.*;
import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.exception.ControlPlaneException;
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
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/service transactions. Runtime, broker receipts and verified S3 metadata are explicit fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false",
    "edgeai.stream.enabled=true","edgeai.stream.bindings-enabled=true","edgeai.stream.broker-digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
class StreamExecutionIntegrationTest {
    private static final Path KEY=keyFile();
    private static Path keyFile() {
        try {var p=Files.createTempFile("edgeai-completion-",".key",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.writeString(p,HexFormat.of().formatHex(bytes));return p;
        } catch(Exception e){throw new IllegalStateException("Cannot create test signing key",e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("edgeai.runtime.key-file",KEY::toString);p.add("edgeai.stream.device-key-file",KEY::toString);
    }
    @AfterAll static void cleanupKey()throws Exception {Files.delete(KEY);}
    @MockitoBean RuntimeGateway podGateway;@MockitoBean S3ArtifactStore storage;
    @MockitoBean MosquittoStreamBroker broker;@MockitoBean StreamAuthorityWorker worker;@MockitoBean StreamConnectionSettings connection;
    @Autowired RunnerTokenService runnerTokens;@Autowired DeviceStreamTokenService deviceTokens;@Autowired DeviceRepository deviceStore;
    @org.springframework.boot.test.web.server.LocalServerPort int apiPort;
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired DeviceService devices;
    @Autowired ExecutionRepository executions;@Autowired WorkflowRepository definitions;@Autowired RuntimeRepository runtimes;
    @Autowired DataRouteService routes;@Autowired DataRouteRepository routeStore;@Autowired StreamCheckpointRepository checkpoints;
    @Autowired StreamExecutionRepository store;@Autowired StreamExecutionService service;@Autowired RuntimeLifecycleService lifecycle;
    @Autowired ExecutionService runs;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;
    private final JsonDocuments json=new JsonDocuments();
    private record Fixture(UUID run,Map<String,Task> tasks,Map<String,RunnerPrincipal> principals,List<DataRoute> routes,
        Map<UUID,RouteGeneration> generations,Map<UUID,DeviceStreamPrincipal> devices,Map<String,UUID> profiles) {}
    private <T>T tx(java.util.function.Supplier<T> body){return new TransactionTemplate(transactions).execute(s->body.get());}
    private UUID profile(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,json.canonical(Map.of("key","completion-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    @SuppressWarnings("unchecked") private UUID serviceProfile(boolean output)throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));
        var stream=(Map<String,Object>)spec.get("stream");var port=Map.of("mediaType","application/json","maxPayloadBytes",4096);
        stream.put("inputs",Map.of("input",port));stream.put("outputs",output?Map.of("output",port):Map.of());
        return profile(ProfileIdentity.Kind.SERVICE,spec);
    }
    private DeviceStreamPrincipal device() {
        var p=profile(ProfileIdentity.Kind.DEVICE,Map.of("protocol","mqtt"));
        var d=devices.create(json.canonical(Map.of("key","completion-"+UUID.randomUUID(),"displayName","Completion source","profileVersionId",p.toString(),"sourceMode","SYNTHETIC"))).value();
        var session=devices.openSession(d.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
        return new DeviceStreamPrincipal(d.id(),session.id(),session.epoch());
    }
    private Fixture fixture()throws Exception{return fixture(false,false,false);}
    private Fixture fixture(boolean other,boolean shared,boolean batchInside)throws Exception {
        var profiles=new HashMap<String,UUID>();profiles.put("source",serviceProfile(true));profiles.put("sink",serviceProfile(false));
        if(other)profiles.put("other",serviceProfile(false));
        var w=workflows.create(json.canonical(Map.of("key","completion-"+UUID.randomUUID(),"displayName","Completion fixture"))).value();
        var nodes=profiles.entrySet().stream().map(e->Map.of("key",e.getKey(),"serviceProfileVersionId",e.getValue().toString(),"parameters",Map.of())).toList();
        var edges=new ArrayList<Object>();edges.add(Map.of("fromTask","source","toTask","sink","fromPort","output","toPort","input","mode","STREAM"));
        if(batchInside)edges.add(Map.of("fromTask","source","toTask","sink","fromPort","result","toPort","file-input","mode","BATCH"));
        var version=workflows.publish(w.id(),json.canonical(Map.of("version","1.0.0","tasks",nodes,"dependencies",edges))).value();
        var now=Instant.now();var run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),json.digest("completion-fixture",version.id().toString()),"AUTO",null,"{}",RetryPolicy.disabled(),null,"PENDING",now,now);
        tx(()->{executions.create(run);executions.initialize(run,definitions.definitions(version.id()),profiles.keySet());return null;});
        var tasks=new HashMap<String,Task>();var principals=new HashMap<String,RunnerPrincipal>();
        for(var task:executions.tasks(run.id())) {
            tasks.put(task.key(),task);var a=executions.attempts(task.id()).getFirst();
            var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
            var runtime=new RuntimeInstance(UUID.randomUUID(),a.id(),task.id(),run.id(),1,"completion-fixture","edgeai-"+a.id(),UUID.randomUUID(),
                "RUNNING","PENDING",null,null,null,null,null,null,now,now);
            tx(()->{executions.run(run.id(),true);runtimes.create(runtime);runtimes.submitted(runtime.id(),pod.jobUid(),now.plusSeconds(600),now);runtimes.claimed(runtime.id(),pod,now);return null;});
            principals.put(task.key(),new RunnerPrincipal(a.id(),1,pod));
        }
        var device=device();var devices=new HashMap<UUID,DeviceStreamPrincipal>();devices.put(device.deviceId(),device);
        var routeList=new ArrayList<DataRoute>();
        routeList.add(routes.fromDevice(run.id(),device.deviceId(),"samples",tasks.get("source").id(),"input",4096));
        routeList.add(routes.fromTask(run.id(),tasks.get("source").id(),"output",tasks.get("sink").id(),"input",4096));
        if(other) {
            var next=shared?device:device();devices.put(next.deviceId(),next);
            routeList.add(routes.fromDevice(run.id(),next.deviceId(),"samples",tasks.get("other").id(),"input",4096));
        }
        var generations=new HashMap<UUID,RouteGeneration>();
        for(var route:routeList) {
            var consumer=principals.get(tasks.values().stream().filter(t->t.id().equals(route.consumerTaskId())).findFirst().orElseThrow().key());
            RouteGeneration.Actor producer;
            if(route.deviceSource()){var d=devices.get(route.sourceDeviceId());producer=new RouteGeneration.Actor(d.sessionId(),d.epoch());}
            else {var p=principals.get("source");producer=new RouteGeneration.Actor(p.attemptId(),p.epoch());}
            var g=routes.prepare(route.id(),UUID.randomUUID(),producer,new RouteGeneration.Actor(consumer.attemptId(),consumer.epoch()),"sha256:"+"b".repeat(64),120);
            generations.put(route.id(),routes.activate(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest())));
        }
        return new Fixture(run.id(),tasks,principals,List.copyOf(routeList),generations,devices,profiles);
    }
    private String body(RunnerPrincipal p,Object... extra) {
        var value=new TreeMap<String,Object>(Map.of("epoch",p.epoch(),"podUid",p.podUid().toString()));
        for(int i=0;i<extra.length;i+=2)value.put((String)extra[i],extra[i+1]);return json.canonical(value);
    }
    private String state(Object value){return (String)((Map<?,?>)value).get("state");}
    private void assigned(Fixture f) {var p=f.principals().get("source");assertThat(state(service.execution(p,body(p)))).isEqualTo("READY");}
    private StreamCheckpoint checkpoint(Fixture f,String key,long sequence,boolean pending) {
        var task=f.tasks().get(key);var p=f.principals().get(key);var runtime=runtimes.byAttempt(p.attemptId()).orElseThrow();
        var relevant=f.routes().stream().filter(r->task.id().equals(r.sourceTaskId()) || task.id().equals(r.consumerTaskId())).toList();
        var cursors=new ArrayList<Object>();var inputs=new ArrayList<Object>();var outputs=new ArrayList<Object>();
        for(var route:relevant) {
            var g=f.generations().get(route.id());boolean input=task.id().equals(route.consumerTaskId());
            Object producer=route.deviceSource()?Map.of("kind","DEVICE_SESSION","deviceId",route.sourceDeviceId().toString(),"sessionId",g.producer().id().toString(),"epoch",g.producer().epoch())
                :Map.of("kind","TASK_ATTEMPT","attemptId",g.producer().id().toString(),"epoch",g.producer().epoch());
            (input?inputs:outputs).add(Map.of("routeId",route.id().toString(),"generation",g.generation(),"producer",producer));
            cursors.add(Map.of("routeId",route.id().toString(),"received",sequence,"committed",pending && !input?sequence-1:sequence,"ended",true));
        }
        var previous=checkpoints.latest(task.id()).orElse(null);long serial=previous==null?10:previous.request().serial()+1;
        var request=new StreamCheckpoint.Request(previous==null?null:previous.id(),serial,"d".repeat(64),100,"e".repeat(64),relevant.stream().map(r->f.generations().get(r.id()).id()).toList());
        var summary=json.canonical(Map.of("manifest",Map.of("version",1,"inputs",inputs,"outputs",outputs,"limits",Map.of("max_frames",128,"max_buffer_bytes",16777216,"max_state_bytes",262144)),
            "revision",3,"routes",cursors,"stateSha256","f".repeat(64),"stateBytes",2));
        var content=request.content(task.id(),p.attemptId());
        var value=new StreamCheckpoint(UUID.randomUUID(),f.run(),task.id(),p.attemptId(),runtime.id(),1,p.podUid(),f.profiles().get(key),request,3,summary,
            new VerifiedArtifact("fixture-only",content.objectKey(),UUID.randomUUID().toString(),content.sha256(),content.bytes(),content.mediaType()),Instant.now(),null);
        tx(()->{executions.run(f.run(),true);checkpoints.insert(value);return null;});return value;
    }
    private Object complete(Fixture f,String key,StreamCheckpoint checkpoint) {
        var p=f.principals().get(key);return service.complete(p,body(p,"checkpointId",checkpoint.id().toString()));
    }
    private Object deviceComplete(Fixture f,int index,long sequence) {
        var r=f.routes().get(index);var d=f.devices().get(r.sourceDeviceId());var g=f.generations().get(r.id());
        return service.deviceComplete(d,json.canonical(Map.of("epoch",d.epoch(),"generationId",g.id().toString(),"sequence",sequence)));
    }
    private void conflict(String code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo(code));
    }
    private ResultManifest result(){return new ResultManifest(List.of(new ResultManifest.Output("result",2,"a".repeat(64),"application/json","fixture-version")));}
    private void commit(Fixture f,String key) {
        var p=f.principals().get(key);var permit=lifecycle.prepareCommit(p.attemptId(),1,p.podUid(),result());var o=result().outputs().getFirst();
        var a=o.content(f.tasks().get(key).id(),p.attemptId());
        lifecycle.commitVerified(permit,List.of(new TaskResult.Output("result",new VerifiedArtifact("fixture-only",a.objectKey(),o.versionId(),a.sha256(),a.bytes(),a.mediaType()))));
    }

    @Test void executionPinsCompleteMembershipAndCurrentGenerationMappings()throws Exception {
        var f=fixture();var p=f.principals().get("source");var response=(Map<?,?>)service.execution(p,body(p));
        assertThat(response.get("state")).isEqualTo("READY");assertThat(response.get("recovery")).isEqualTo("NEW");
        assertThat(((Map<?,?>)response.get("inputs")).get("input")).isEqualTo(f.generations().get(f.routes().getFirst().id()).id().toString());
        var checkpoint=checkpoint(f,"source",3,false);
        assertThat(((Map<?,?>)service.execution(p,body(p))).get("recovery")).isEqualTo("RESTORE");
        assertThat(store.bindingDigest(f.run())).isPresent();
        var old=f.routes().getFirst();var extra=new DataRoute(UUID.randomUUID(),old.runId(),null,old.sourceDeviceId(),old.sourceProfileVersionId(),old.sourceMode(),old.sourcePort(),old.consumerTaskId(),"late",old.mediaType(),old.maxPayloadBytes(),Instant.now());
        assertThatThrownBy(()->tx(()->{routeStore.create(extra);return null;})).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(checkpoint.request().generationIds()).hasSize(2);
    }
    @Test void lastDeviceAcknowledgementReleasesBothTasksAndGrantSurvivesPeerCompletion()throws Exception {
        var f=fixture();assigned(f);var source=checkpoint(f,"source",3,false);var sink=checkpoint(f,"sink",3,false);
        assertThat(state(complete(f,"source",source))).isEqualTo("WAITING");
        var p=f.principals().get("source");conflict("STREAM_COMPLETION_REQUIRED",()->lifecycle.prepareCommit(p.attemptId(),1,p.podUid(),result()));
        assertThat(state(complete(f,"sink",sink))).isEqualTo("WAITING");assertThat(state(deviceComplete(f,0,3))).isEqualTo("FINALIZE");
        commit(f,"source");
        for(var g:f.generations().values()) {routes.fence(g.id(),"COMPLETED");routes.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));}
        assertThat(state(complete(f,"sink",sink))).isEqualTo("FINALIZE");commit(f,"sink");
        assertThat(executions.run(f.run(),false).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(state(deviceComplete(f,0,3))).isEqualTo("FINALIZE");
    }
    @Test void outputEndWithoutFinalProcessingAckCannotBeReported()throws Exception {
        var f=fixture();assigned(f);var pending=checkpoint(f,"source",3,true);
        conflict("STREAM_END_NOT_CONFIRMED",()->complete(f,"source",pending));assertThat(store.task(pending.attemptId())).isEmpty();
        var sealed=checkpoint(f,"source",3,false);assertThat(state(complete(f,"source",sealed))).isEqualTo("WAITING");
        assertThatThrownBy(()->checkpoint(f,"source",3,false)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(checkpoints.latest(f.tasks().get("source").id()).orElseThrow().id()).isEqualTo(sealed.id());
    }
    @Test void independentComponentDoesNotHoldCompletedComponentButDeviceFanoutDoes()throws Exception {
        for(boolean shared:List.of(false,true)) {
            var f=fixture(true,shared,false);assigned(f);var source=checkpoint(f,"source",3,false);var sink=checkpoint(f,"sink",3,false);
            complete(f,"source",source);complete(f,"sink",sink);
            assertThat(state(deviceComplete(f,0,3))).isEqualTo(shared?"WAITING":"FINALIZE");
            if(shared) {var other=checkpoint(f,"other",3,false);complete(f,"other",other);assertThat(state(deviceComplete(f,2,3))).isEqualTo("FINALIZE");}
            assertThat(store.task(source.attemptId()).orElseThrow().grantedAt()).isNotNull();
        }
    }
    @Test void inconsistentTerminalCursorsCannotGrantAnyParticipant()throws Exception {
        var f=fixture();assigned(f);var source=checkpoint(f,"source",3,false);var sink=checkpoint(f,"sink",4,false);
        complete(f,"source",source);complete(f,"sink",sink);
        conflict("STREAM_TERMINAL_CURSOR_MISMATCH",()->deviceComplete(f,0,3));
        assertThat(store.task(source.attemptId()).orElseThrow().grantedAt()).isNull();assertThat(store.device(f.generations().get(f.routes().getFirst().id()).id())).isEmpty();
    }
    @Test void cancellationAndForeignCheckpointCannotBecomeLateSuccess()throws Exception {
        var f=fixture();assigned(f);var source=checkpoint(f,"source",3,false);var sink=checkpoint(f,"sink",3,false);
        conflict("STREAM_CHECKPOINT_STALE",()->complete(f,"source",sink));complete(f,"source",source);runs.cancelRun(f.run(),"{}");
        conflict("STREAM_RUN_INACTIVE",()->complete(f,"source",source));conflict("STREAM_RUN_INACTIVE",()->deviceComplete(f,0,3));
        assertThat(store.task(source.attemptId()).orElseThrow().grantedAt()).isNull();
    }
    @Test void concurrentReportsAreIdempotentAndGrantInOneTransaction()throws Exception {
        var f=fixture();assigned(f);var source=checkpoint(f,"source",3,false);var sink=checkpoint(f,"sink",3,false);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var actions=new ArrayList<Callable<Object>>();for(int i=0;i<2;i++){actions.add(()->complete(f,"source",source));actions.add(()->complete(f,"sink",sink));actions.add(()->deviceComplete(f,0,3));}
            for(var future:pool.invokeAll(actions))assertThat(state(future.get(10,TimeUnit.SECONDS))).isIn("WAITING","FINALIZE");
        }
        assertThat(state(complete(f,"source",source))).isEqualTo("FINALIZE");assertThat(state(complete(f,"sink",sink))).isEqualTo("FINALIZE");
        var grant=store.task(source.attemptId()).orElseThrow().grantedAt();assertThat(store.task(sink.attemptId()).orElseThrow().grantedAt()).isEqualTo(grant);
        assertThat(store.device(f.generations().get(f.routes().getFirst().id()).id()).orElseThrow().grantedAt()).isEqualTo(grant);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.stream_task_completion SET checkpoint_id=? WHERE attempt_id=?",sink.id(),source.attemptId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.stream_task_completion SET granted_at=NULL WHERE attempt_id=?",source.attemptId())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void batchDependencyInsideSimultaneousStreamComponentIsRejected()throws Exception {
        var f=fixture(false,false,true);var p=f.principals().get("source");
        assertThatThrownBy(()->service.execution(p,body(p))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.bindingDigest(f.run())).isEmpty();
    }
    private String runnerPath(RunnerPrincipal p,String action){return "/internal/v1/attempts/"+p.attemptId()+"/streams/"+action;}
    private String devicePath(DeviceStreamPrincipal p){return "/internal/v1/devices/"+p.deviceId()+"/sessions/"+p.sessionId()+"/streams/complete";}
    private Map<String,String> headers(RunnerPrincipal p){return Map.of("Authorization","Bearer "+runnerTokens.issue(runtimes.byAttempt(p.attemptId()).orElseThrow()),"X-EdgeAI-Pod-Token","completion-pod-fixture");}
    private Map<?,?> post(HttpClient client,String path,String body,Map<String,String> headers,int status)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+apiPort+path)).timeout(Duration.ofSeconds(5))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));headers.forEach(request::header);
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP status for %s",path).isEqualTo(status);
        if(status==200)assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return response.body().isBlank()?Map.of():(Map<?,?>)json.decode(response.body());
    }
    @Test void realHttpAuthenticationScopesAndDurableCompletionReplay()throws Exception {
        var f=fixture();var source=f.principals().get("source");var sink=f.principals().get("sink");
        when(podGateway.authenticatePod(any(),any())).thenAnswer(call->{
            RuntimeInstance r=call.getArgument(0);if(!"completion-pod-fixture".equals(call.getArgument(1)))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return f.principals().values().stream().filter(p->p.attemptId().equals(r.attemptId())).findFirst().orElseThrow().pod();
        });
        var device=f.devices().values().iterator().next();var generation=f.generations().get(f.routes().getFirst().id());
        var credential=Map.of("Authorization","Bearer "+deviceTokens.issue(deviceStore.activeSession(device.deviceId()).orElseThrow()));
        String deviceBody=json.canonical(Map.of("epoch",device.epoch(),"generationId",generation.id().toString(),"sequence",3));
        try(var client=HttpClient.newHttpClient()) {
            post(client,runnerPath(source,"execution"),body(source),Map.of(),401);
            post(client,runnerPath(source,"execution"),body(source),headers(sink),401);
            post(client,runnerPath(source,"execution"),body(source),Map.of("Authorization",headers(source).get("Authorization")),401);
            assertThat(post(client,runnerPath(source,"execution"),body(source),headers(source),200).get("state")).isEqualTo("READY");
            var sourceCheckpoint=checkpoint(f,"source",3,false);var sinkCheckpoint=checkpoint(f,"sink",3,false);
            String sourceBody=body(source,"checkpointId",sourceCheckpoint.id().toString()),sinkBody=body(sink,"checkpointId",sinkCheckpoint.id().toString());
            post(client,runnerPath(source,"complete"),sourceBody,headers(sink),401);
            post(client,runnerPath(source,"complete"),body(source,"checkpointId",sourceCheckpoint.id().toString(),"extra",true),headers(source),400);
            assertThat(post(client,runnerPath(source,"complete"),sourceBody,headers(source),200).get("state")).isEqualTo("WAITING");
            assertThat(post(client,runnerPath(sink,"complete"),sinkBody,headers(sink),200).get("state")).isEqualTo("WAITING");
            post(client,devicePath(device),deviceBody,headers(source),401);
            assertThat(post(client,devicePath(device),deviceBody,credential,200).get("state")).isEqualTo("FINALIZE");
            commit(f,"source");for(var g:f.generations().values()){routes.fence(g.id(),"COMPLETED");routes.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));}
            assertThat(post(client,runnerPath(sink,"complete"),sinkBody,headers(sink),200).get("state")).isEqualTo("FINALIZE");
            commit(f,"sink");assertThat(post(client,devicePath(device),deviceBody,credential,200).get("state")).isEqualTo("FINALIZE");
            devices.openSession(device.deviceId(),json.canonical(Map.of("bootId",UUID.randomUUID().toString())));
            post(client,devicePath(device),deviceBody,credential,401);
        }
    }
}
