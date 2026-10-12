package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.vd.*;
import io.edgeai.domain.device.DeviceResourceGateway;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Real PostgreSQL and HTTP security. Pod attestation and S3 verification receipts are explicit fixtures. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.vd.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.vd.lease-seconds=60","edgeai.runtime.namespace=vd-task-http"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(VDTaskIntegrationTest.TimeConfiguration.class)
class VDTaskIntegrationTest {
    private static final Path KEY=key();
    private static Path key(){try{var p=Files.createTempFile("edgeai-vd-task-test-",".key");byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.writeString(p,HexFormat.of().formatHex(bytes));return p;}catch(Exception e){throw new IllegalStateException("Cannot create fixture key");}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("edgeai.runtime.key-file",KEY::toString);}
    @AfterAll static void removeKey()throws Exception{Files.deleteIfExists(KEY);}
    static class TestClock extends Clock {
        private final AtomicLong offset=new AtomicLong();
        public Instant instant(){return Instant.now().plusSeconds(offset.get());}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}
    }
    @TestConfiguration static class TimeConfiguration{@Bean @Primary TestClock taskClock(){return new TestClock();}}
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;
    @Autowired ProfileService profiles;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowRepository definitions;
    @Autowired VirtualDeviceService devices;
    @Autowired VirtualDeviceRepository vds;
    @Autowired VDLifecycleService vdLifecycle;
    @Autowired VDRuntimeRepository supervisors;
    @Autowired VDPollRepository receipts;
    @Autowired VDTaskRepository allocations;
    @Autowired VDTaskService taskService;
    @Autowired VDTokenService vdTokens;
    @Autowired RunnerTokenService taskTokens;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired ExecutionRepository executions;
    @Autowired ExecutionService executionApi;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean VDGateway gateway;
    @MockitoBean RuntimeGateway jobs;
    @MockitoBean S3ArtifactStore storage;
    @MockitoBean DeviceResourceGateway sensors;
    private final UUID sensorUid=UUID.randomUUID();
    private String sensorValue="26.5";
    private String sensorType="float64";
    private long sensorObservedAt;
    private String sensorState="ONLINE";
    private boolean sensorMissing;
    private final JsonDocuments json=new JsonDocuments();
    private final Map<UUID,VDGateway.PodIdentity> identities=new ConcurrentHashMap<>();
    private record Fixture(VirtualDevice vd,VDRuntime supervisor,UUID session,WorkflowRun run,List<RuntimeInstance> work){}
    @BeforeEach void boundaries() {
        clock.offset.set(0);identities.clear();
        sensorValue="26.5";sensorType="float64";sensorObservedAt=clock.millis();sensorState="ONLINE";sensorMissing=false;
        when(sensors.request(eq("GET"),anyString(),isNull())).thenAnswer(call->{
            String path=call.getArgument(1);Object document;
            if(path.endsWith("/devices/sensehat-001"))document=Map.of("metadata",Map.of("name","sensehat-001","uid",sensorUid.toString(),"resourceVersion","1","labels",Map.of("app.kubernetes.io/part-of","edgeai"),
                "annotations",Map.of("edgeai.io/device-profile",encode(Map.of("kind","physical","properties",List.of(Map.of("name","temperature","dataType",sensorType,"scale",10,"unit","°C")))))),
                "spec",Map.of("nodeName","edge-node","deviceModelRef",Map.of("name","edgeai-model-sensehat"),"protocol",Map.of("protocolName","edgeai-sensehat"),"properties",List.of(Map.of("name","temperature"))));
            else if(path.endsWith("/devicestatuses/sensehat-001"))document=Map.of("status",Map.of("state",sensorState,"lastOnlineTime",clock.instant().toString(),"twins",sensorMissing?List.of():List.of(Map.of("propertyName","temperature","reported",Map.of("value",sensorValue,"metadata",Map.of("timestamp",Long.toString(sensorObservedAt)))))));
            else if(path.contains("/deployments/"))document=Map.of("status",Map.of("readyReplicas",1));
            else return new DeviceResourceGateway.Reply(404,"{}");
            return new DeviceResourceGateway.Reply(200,encode(document));
        });
        when(gateway.authenticatePod(any(),any())).thenAnswer(call->{
            VDRuntime r=call.getArgument(0);var p=identities.get(r.id());
            if(!"vd-task-pod-fixture".equals(call.getArgument(1)) || p==null)throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);return p;
        });
        when(storage.verify(any(),any())).thenAnswer(call->{ArtifactContent c=call.getArgument(0);return new VerifiedArtifact("fixture-only",c.objectKey(),call.getArgument(1),c.sha256(),c.bytes(),c.mediaType());});
        when(storage.download(any())).thenReturn(new ArtifactGrant(URI.create("http://storage.fixture/input"),Map.of(),Instant.now().plusSeconds(600)));
    }
    private <T>T transaction(java.util.function.Supplier<T> action){return new TransactionTemplate(transactions).execute(s->action.get());}
    private String encode(Object v){return json.canonical(v);}
    private UUID publish(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,encode(Map.of("key","vd-exchange-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    private Fixture fixture(int count,int capacity)throws Exception{return fixture(count,capacity,false);}
    private Fixture fixture(int count,int capacity,boolean retry)throws Exception {return fixture(count,capacity,retry,false);}
    private Fixture fixture(int count,int capacity,boolean retry,boolean consumer)throws Exception {
        return fixture(count,capacity,retry,consumer,false);
    }
    private Fixture fixture(int count,int capacity,boolean retry,boolean consumer,boolean sensor)throws Exception {
        var spec=new LinkedHashMap<String,Object>((Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        if(consumer)spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        UUID sp=publish(ProfileIdentity.Kind.SERVICE,spec);
        UUID vp=publish(ProfileIdentity.Kind.VD,Map.of("apiVersion",sensor?"edgeai.vd/v2":"edgeai.vd/v1","type",sensor?"sensorMirror":"emulation","serviceProfileVersionId",sp.toString(),"sources",sensor?Map.of("input",Map.of("deviceModel","edgeai-model-sensehat","required",true)):Map.of(),"state",Map.of("mode","STATELESS"),
            "runtime",Map.of("maxConcurrentTasks",capacity,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30)));
        var vd=devices.create(encode(Map.of("key","vd-exchange-"+UUID.randomUUID(),"displayName","VD exchange fixture","profileVersionId",vp.toString(),"sources",sensor?List.of(Map.of("sourceKey","input","deviceId","sensehat-001","properties",List.of("temperature"),"maxAgeSeconds",30)):List.of(),"placement",Map.of("mode","AUTO")))).value();
        String ns="vd-task-http";
        var op=vdLifecycle.provision(vd.id(),0,"provision",new RuntimeSettings(ns,"edgeai-runner",URI.create("http://fixture.invalid"),120),false);
        var vr=vdLifecycle.submitted(op.targetRuntimeId(),UUID.randomUUID());var session=UUID.randomUUID();
        identities.put(vr.id(),new VDGateway.PodIdentity(vr.podUid(),UUID.randomUUID(),"fixture-node",true));
        var wf=workflows.create(encode(Map.of("key","vd-exchange-"+UUID.randomUUID(),"displayName","VD Task exchange"))).value();
        var nodes=IntStream.range(0,count).mapToObj(i->Map.of("key","task"+i,"serviceProfileVersionId",sp.toString(),"parameters",Map.of())).toList();
        var version=workflows.publish(wf.id(),encode(Map.of("version","1.0.0","tasks",nodes,"dependencies",List.of()))).value();
        var now=clock.instant();var run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),json.digest("vd-exchange-fixture",Map.of("version",version.id().toString())),"VD",null,"{}",
            retry?new RetryPolicy(2,1,600,Set.of("RUNTIME_LOST")):RetryPolicy.disabled(),null,"PENDING",now,now,null,vd.id());
        var before=new Fixture(vd,vr,session,run,List.of());poll(before,body(before,0,List.of(),List.of()),200);
        lifecycle.validateVDRequest(vd.id(),version.id(),ns);
        var request=new LinkedHashMap<String,Object>(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","VD","vdId",vd.id().toString()),"parameters",Map.of()));
        if(retry)request.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",600,"retryOn",List.of("RUNTIME_LOST")));
        var actual=executionApi.create(run.idempotencyKey().toString(),encode(request)).value();
        var work=executions.tasks(actual.id()).stream().map(t->runtimes.byAttempt(executions.attempts(t.id()).getFirst().id()).orElseThrow()).toList();
        return new Fixture(vd,supervisors.runtime(vr.id()).orElseThrow(),session,actual,work);
    }
    private Map<String,Object> body(Fixture f,long sequence,List<RuntimeInstance> active,List<RuntimeInstance> completed) {
        return new LinkedHashMap<>(Map.of("vdId",f.vd().id().toString(),"runtimeId",f.supervisor().id().toString(),"generation",f.supervisor().generation(),"podUid",f.supervisor().podUid().toString(),
            "sessionId",f.session().toString(),"sequence",sequence,"state","RUNNING",
            "active",active.stream().map(r->Map.of("attemptId",r.attemptId().toString(),"epoch",r.epoch())).toList(),
            "completed",completed.stream().map(r->Map.of("attemptId",r.attemptId().toString(),"epoch",r.epoch(),"exitCode",0)).toList()));
    }
    private Map<?,?> poll(Fixture f,Object body,int status)throws Exception {
        var response=mvc.perform(post("/internal/v1/vd-runtimes/"+f.supervisor().id()+"/poll").header("Authorization","Bearer "+vdTokens.issue(f.supervisor())).header("X-EdgeAI-Pod-Token","vd-task-pod-fixture")
            .contentType("application/json").content(encode(body))).andExpect(status().is(status)).andReturn().getResponse();
        return response.getContentAsString().isEmpty()?Map.of():(Map<?,?>)json.decode(response.getContentAsString());
    }
    private List<RuntimeInstance> assigned(Fixture f,Map<?,?> response) {
        return ((List<?>)response.get("assignments")).stream().map(v->{UUID id=UUID.fromString((String)((Map<?,?>)v).get("attemptId"));return runtimes.byAttempt(id).orElseThrow();}).toList();
    }
    private Map<String,Object> runnerBody(Fixture f,RuntimeInstance r){return new LinkedHashMap<>(Map.of("epoch",r.epoch(),"podUid",f.supervisor().podUid().toString()));}
    private void runner(Fixture f,RuntimeInstance r,String action,Object body,int expected)throws Exception {
        mvc.perform(post("/internal/v1/attempts/"+r.attemptId()+"/"+action).header("Authorization","Bearer "+taskTokens.issue(r)).header("X-EdgeAI-Pod-Token","vd-task-pod-fixture")
            .contentType("application/json").content(encode(body))).andExpect(status().is(expected));
    }
    private Map<String,Object> resultBody(Fixture f,RuntimeInstance r){var b=runnerBody(f,r);b.put("outputs",List.of(Map.of("port","output","bytes",2,"sha256","a".repeat(64),"mediaType","application/json","versionId","fixture-version")));return b;}
    private RuntimeInstance current(RuntimeInstance r){return runtimes.runtime(r.id()).orElseThrow();}
    @Test void sensorVdRunPinsReadingsAndReplayKeepsTheOriginalTaskInput() throws Exception {
        var f=fixture(1,1,false,false,true);var task=executions.tasks(f.run().id()).getFirst();
        mvc.perform(get("/api/v1/tasks/"+task.id())).andExpect(status().isOk())
            .andExpect(jsonPath("$.vdInputs.sources.input.properties.temperature.value").value(26.5))
            .andExpect(jsonPath("$.vdInputs.sources.input.deviceUid").value(sensorUid.toString()));
        devices.update(f.vd().id(),encode(Map.of("revision",0,"displayName",f.vd().displayName(),"profileVersionId",f.vd().profileVersionId().toString(),"sources",List.of(Map.of("sourceKey","input","deviceId","sensehat-001","properties",List.of("temperature"),"maxAgeSeconds",45)),"placement",Map.of("mode","AUTO"))));
        sensorValue="41.5";clock.offset.set(45);
        var request=Map.of("workflowVersionId",f.run().workflowVersionId().toString(),"execution",Map.of("mode","VD","vdId",f.vd().id().toString()),"parameters",Map.of());
        mvc.perform(post("/api/v1/workflow-runs").with(csrf()).header("Idempotency-Key",f.run().idempotencyKey()).contentType("application/json").content(encode(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(f.run().id().toString()));
        mvc.perform(get("/api/v1/tasks/"+task.id())).andExpect(jsonPath("$.vdInputs.sources.input.properties.temperature.value").value(26.5));
    }
    @Test void runnerClaimAndRetryUseThePinnedInputAndStorageForbidsMutation() throws Exception {
        var f=fixture(1,1,true,false,true);var task=executions.tasks(f.run().id()).getFirst();
        var fixed=executions.vdInput(task.id()).orElseThrow();
        sensorValue="99";
        var work=assigned(f,poll(f,body(f,1,List.of(),List.of()),200)).getFirst();
        mvc.perform(post("/internal/v1/attempts/"+work.attemptId()+"/claim").header("Authorization","Bearer "+taskTokens.issue(work)).header("X-EdgeAI-Pod-Token","vd-task-pod-fixture")
            .contentType("application/json").content(encode(runnerBody(f,work)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.parameters.vdInputs.sources.input.properties.temperature.value").value(26.5));
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.vd_task_input SET document='{}' WHERE task_id=?",task.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM edgeai.vd_task_input WHERE task_id=?",task.id())).isInstanceOf(DataIntegrityViolationException.class);
        vdLifecycle.fail(f.supervisor().id(),"RUNTIME_LOST");taskService.reconcile(work.id());clock.offset.set(2);
        jdbc.update("UPDATE edgeai.vd_runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id=? AND kind='CREATE'",f.supervisor().id());
        vdLifecycle.confirmStopped(f.supervisor().id());assertThat(lifecycle.retryTask(task.id())).isTrue();
        assertThat(executions.attempts(task.id())).hasSize(2);assertThat(executions.vdInput(task.id()).orElseThrow()).isEqualTo(fixed);
    }
    @Test void invalidMeasurementsAndReservedParametersNeverCreateARun() throws Exception {
        var f=fixture(1,1,false,false,true);
        var request=new LinkedHashMap<String,Object>(Map.of("workflowVersionId",f.run().workflowVersionId().toString(),"execution",Map.of("mode","VD","vdId",f.vd().id().toString()),"parameters",Map.of()));
        int before=jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_run",Integer.class);
        sensorObservedAt=clock.millis()-31000;rejectSensorRun(request,"VD_SOURCE_VALUE_STALE");
        sensorObservedAt=clock.millis()+60000;rejectSensorRun(request,"VD_SOURCE_VALUE_STALE");
        sensorObservedAt=clock.millis();sensorMissing=true;rejectSensorRun(request,"VD_SOURCE_VALUE_MISSING");
        sensorMissing=false;sensorState="OFFLINE";rejectSensorRun(request,"VD_SOURCE_OFFLINE");
        sensorState="ONLINE";sensorValue="NaN";rejectSensorRun(request,"VD_SOURCE_VALUE_INVALID");
        sensorValue="26.5";request.put("parameters",Map.of("vdInputs",Map.of("forged",true)));
        mvc.perform(post("/api/v1/workflow-runs").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(encode(request))).andExpect(status().isBadRequest());
        var wf=workflows.create(encode(Map.of("key","forged-input-"+UUID.randomUUID(),"displayName","Reserved task input"))).value();
        var version=workflows.publish(wf.id(),encode(Map.of("version","1.0.0","tasks",List.of(Map.of("key","task","serviceProfileVersionId",f.vd().serviceProfileVersionId().toString(),"parameters",Map.of("vdInputs",Map.of("forged",true)))),"dependencies",List.of()))).value();
        request.put("workflowVersionId",version.id().toString());request.put("parameters",Map.of());
        mvc.perform(post("/api/v1/workflow-runs").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(encode(request))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_run",Integer.class)).isEqualTo(before);
    }
    @Test void scaledFloat32ReportsAreEngineeringValuesRatherThanRawWireValues()throws Exception {
        sensorType="float32";sensorValue="3.4e39";
        var f=fixture(1,1,false,false,true);
        var task=executions.tasks(f.run().id()).getFirst();
        var frame=(Map<?,?>)json.decode(executions.vdInput(task.id()).orElseThrow());
        var source=(Map<?,?>)((Map<?,?>)frame.get("sources")).get("input");
        var reading=(Map<?,?>)((Map<?,?>)source.get("properties")).get("temperature");
        assertThat(((Number)reading.get("value")).doubleValue()).isEqualTo(3.4e39);
    }
    @Test void concurrentReplayWaitsForTheFirstCommitAndNeverRereadsAnOfflineSensor()throws Exception {
        var f=fixture(1,1,false,false,true);String key=UUID.randomUUID().toString();
        var request=encode(Map.of("workflowVersionId",f.run().workflowVersionId().toString(),"execution",Map.of("mode","VD","vdId",f.vd().id().toString()),"parameters",Map.of()));
        var created=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->transaction(()->{
                var result=executionApi.create(key,request);created.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("First transaction was not released");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return result;
            }));
            assertThat(created.await(5,TimeUnit.SECONDS)).isTrue();
            int firstReads=mockingDetails(sensors).getInvocations().size();
            var second=pool.submit(()->executionApi.create(key,request));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid()",Boolean.class);
                    if(waiting)break;Thread.sleep(20);
                }
                assertThat(waiting).as("Second request is waiting on the first transaction").isTrue();
                sensorState="OFFLINE";
            } finally {release.countDown();}
            var accepted=first.get(5,TimeUnit.SECONDS);var replay=second.get(5,TimeUnit.SECONDS);
            assertThat(accepted.created()).isTrue();assertThat(replay.created()).isFalse();assertThat(replay.value().id()).isEqualTo(accepted.value().id());
            assertThat(mockingDetails(sensors).getInvocations().size()).isEqualTo(firstReads);
        } finally {release.countDown();}
    }
    private void rejectSensorRun(Object request,String code)throws Exception {
        mvc.perform(post("/api/v1/workflow-runs").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(encode(request)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }
    @Test void eachTaskKeepsItsOwnVdAndBindingWhenMultipleVdsShareASensor() throws Exception {
        var a=fixture(1,1,false,false,true);var b=fixture(1,1,false,false,true);
        var run=executionApi.create(UUID.randomUUID().toString(),encode(mixedRequest(a,b,false))).value();
        for(var task:executions.tasks(run.id())) {
            var frame=(Map<?,?>)json.decode(executions.vdInput(task.id()).orElseThrow());
            var vd=task.key().equals("first")?a.vd():b.vd();
            assertThat(frame.get("vdId")).isEqualTo(vd.id().toString());
            var source=(Map<?,?>)((Map<?,?>)frame.get("sources")).get("input");
            assertThat(source.get("bindingId")).isEqualTo(vds.activeSources(vd.id()).getFirst().id().toString());
        }
    }
    private void fenced(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ControlPlaneException.class);}

    private Map<String,Object> mixedRequest(Fixture a,Fixture b,boolean dependency) {
        var workflow=workflows.create(encode(Map.of("key","mixed-vd-"+UUID.randomUUID(),"displayName","Separate task authorities"))).value();
        var version=workflows.publish(workflow.id(),encode(Map.of("version","1.0.0","tasks",List.of(
            Map.of("key","first","serviceProfileVersionId",a.vd().serviceProfileVersionId().toString(),"parameters",Map.of()),
            Map.of("key","second","serviceProfileVersionId",b.vd().serviceProfileVersionId().toString(),"parameters",Map.of())),
            "dependencies",dependency?List.of(Map.of("fromTask","first","toTask","second","fromPort","output","toPort","input","mode","BATCH")):List.of()))).value();
        return new LinkedHashMap<>(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","VD","vdId",a.vd().id().toString()),
            "parameters",Map.of(),"taskExecutions",Map.of("second",Map.of("mode","VD","vdId",b.vd().id().toString()))));
    }

    @Test void differentVdServicesPinWaitingTasksAndReleaseChildrenWithoutTakingPeerAuthority()throws Exception {
        var a=fixture(1,1);var b=fixture(1,1,false,true);
        executionApi.cancelRun(a.run().id(),"{}");executionApi.cancelRun(b.run().id(),"{}");
        var request=mixedRequest(a,b,true);String key=UUID.randomUUID().toString();
        var response=mvc.perform(post("/api/v1/workflow-runs").with(user("fixture")).with(csrf()).header("Idempotency-Key",key)
            .contentType("application/json").content(encode(request))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID run=UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id"));
        var tasks=executions.tasks(run);var child=tasks.stream().filter(t->t.key().equals("second")).findFirst().orElseThrow();
        assertThat(child.initialMode()).isEqualTo("VD");assertThat(child.initialVdId()).isEqualTo(b.vd().id());
        assertThat(child.initialRemoteTarget()).isNull();assertThat(child.state()).isEqualTo("WAITING");assertThat(executions.attempts(child.id())).isEmpty();
        mvc.perform(get("/api/v1/tasks/"+child.id()).with(user("fixture"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.task.initialVdId").value(b.vd().id().toString()));
        var root=assigned(a,poll(a,body(a,1,List.of(),List.of()),200)).getFirst();
        assertThat(root.runId()).isEqualTo(run);runner(a,root,"claim",runnerBody(a,root),200);
        // A peer poll/replacement may hold VD B while result A prepares B's child. FK checks must not deadlock.
        try(var pool=Executors.newSingleThreadExecutor()) {
            transaction(()->{
                vds.find(b.vd().id(),true).orElseThrow();
                var future=pool.submit(()->{runner(a,current(root),"commit",resultBody(a,root),201);return true;});
                try{assertThat(future.get(8,TimeUnit.SECONDS)).isTrue();}
                catch(Exception e){throw new AssertionError("Child preparation acquired peer VD authority",e);}
                return null;
            });
        }
        assertThat(executions.attempts(child.id())).singleElement().satisfies(attempt->{assertThat(attempt.vdId()).isEqualTo(b.vd().id());assertThat(attempt.cause()).isEqualTo("INITIAL");});
        var next=assigned(b,poll(b,body(b,1,List.of(),List.of()),200)).getFirst();
        assertThat(next.taskId()).isEqualTo(child.id());runner(b,next,"claim",runnerBody(b,next),200);
        var inputs=lifecycle.authorize(next.attemptId(),next.epoch(),b.supervisor().podUid()).inputs();
        assertThat(inputs).singleElement().satisfies(input->{assertThat(input.port()).isEqualTo("input");assertThat(input.artifact()).isEqualTo(runtimes.result(root.taskId()).orElseThrow().outputs().getFirst().artifact());});
        runner(b,current(next),"commit",resultBody(b,next),201);
        assertThat(runtimes.result(root.taskId()).orElseThrow().vdRuntimeId()).isEqualTo(a.supervisor().id());
        assertThat(runtimes.result(child.id()).orElseThrow().vdRuntimeId()).isEqualTo(b.supervisor().id());
        poll(a,body(a,2,List.of(),List.of(root)),200);poll(b,body(b,2,List.of(),List.of(next)),200);
        assertThat(allocations.open(a.supervisor().id())).isEmpty();assertThat(allocations.open(b.supervisor().id())).isEmpty();
        assertThat(executions.run(run,false).orElseThrow().state()).isEqualTo("SUCCEEDED");
        vdLifecycle.drain(b.vd().id(),0,"after-mixed-result");
        assertThat(executionApi.create(key,encode(request)).value().id()).isEqualTo(run);
        request.put("taskExecutions",Map.of("second",Map.of("mode","VD","vdId",a.vd().id().toString())));
        assertThatThrownBy(()->executionApi.create(key,encode(request))).isInstanceOf(ControlPlaneException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.task SET initial_vd_id=? WHERE id=?",a.vd().id(),child.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void twoVdPollsShareOneRunButKeepProducerAuthorityAndCancellationIndependent()throws Exception {
        var a=fixture(1,1);var b=fixture(1,1);
        executionApi.cancelRun(a.run().id(),"{}");executionApi.cancelRun(b.run().id(),"{}");
        var run=executionApi.create(UUID.randomUUID().toString(),encode(mixedRequest(a,b,false))).value();
        RuntimeInstance first,second;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var fa=pool.submit(()->{start.await();return assigned(a,poll(a,body(a,1,List.of(),List.of()),200)).getFirst();});
            var fb=pool.submit(()->{start.await();return assigned(b,poll(b,body(b,1,List.of(),List.of()),200)).getFirst();});
            start.countDown();first=fa.get(10,TimeUnit.SECONDS);second=fb.get(10,TimeUnit.SECONDS);
            assertThat(first.runId()).isEqualTo(run.id());assertThat(second.runId()).isEqualTo(run.id());
            runner(a,first,"claim",runnerBody(a,first),200);runner(b,second,"claim",runnerBody(b,second),200);
            transaction(()->{
                vds.find(a.vd().id(),true).orElseThrow();
                var future=pool.submit(()->lifecycle.authorize(second.attemptId(),second.epoch(),b.supervisor().podUid()));
                try{assertThat(future.get(5,TimeUnit.SECONDS).runtime().vdId()).isEqualTo(b.vd().id());}
                catch(Exception e){throw new AssertionError("Producer B used the default VD A authority",e);}
                return null;
            });
            var cancel=pool.submit(()->executionApi.cancelTask(first.taskId(),"{}"));
            var commit=pool.submit(()->{runner(b,current(second),"commit",resultBody(b,second),201);return true;});
            cancel.get(10,TimeUnit.SECONDS);assertThat(commit.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(executions.task(first.taskId()).orElseThrow().state()).isEqualTo("CANCELLING");
        assertThat(runtimes.result(first.taskId())).isEmpty();assertThat(runtimes.result(second.taskId()).orElseThrow().vdRuntimeId()).isEqualTo(b.supervisor().id());
        assertThat(allocations.open(a.supervisor().id())).hasSize(1);assertThat(allocations.open(b.supervisor().id())).hasSize(1);
        poll(a,body(a,2,List.of(),List.of(first)),200);poll(b,body(b,2,List.of(),List.of(second)),200);
        assertThat(allocations.open(a.supervisor().id())).isEmpty();assertThat(allocations.open(b.supervisor().id())).isEmpty();
        assertThat(executions.task(first.taskId()).orElseThrow().state()).isEqualTo("CANCELLED");
    }

    @Test void publicVdRunIsCsrfProtectedPinnedIdempotentAndRequiresReadyCompatibleService()throws Exception {
        var f=fixture(1,1);var request=Map.of("workflowVersionId",f.run().workflowVersionId().toString(),"execution",Map.of("mode","VD","vdId",f.vd().id().toString()),"parameters",Map.of());
        String path="/api/v1/workflow-runs";
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(encode(request))).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(user("fixture")).contentType("application/json").content(encode(request))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("fixture")).with(csrf()).header("Idempotency-Key",f.run().idempotencyKey()).contentType("application/json").content(encode(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.vdId").value(f.vd().id().toString())).andExpect(jsonPath("$.mode").value("VD"));
        var other=fixture(1,1);var wrong=new LinkedHashMap<String,Object>(request);wrong.put("execution",Map.of("mode","VD","vdId",other.vd().id().toString()));
        mvc.perform(post(path).with(user("fixture")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(encode(wrong)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VD_SERVICE_MISMATCH"));
        wrong.put("execution",Map.of("mode","VD","vdId",f.vd().id().toString(),"nodeId",UUID.randomUUID().toString()));
        mvc.perform(post(path).with(user("fixture")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(encode(wrong))).andExpect(status().isBadRequest());
        vdLifecycle.drain(f.vd().id(),0,"drain");
        mvc.perform(post(path).with(user("fixture")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(encode(request)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VD_NOT_READY"));
        assertThat(executionApi.create(f.run().idempotencyKey().toString(),encode(request)).value().id()).isEqualTo(f.run().id());
    }

    @Test void concurrentExactPollRetriesAllocateOnceAndCapacityWaitsForProcessExit()throws Exception {
        var f=fixture(8,2);var request=body(f,1,List.of(),List.of());
        try(var pool=Executors.newFixedThreadPool(4)) {
            var start=new CountDownLatch(1);var calls=IntStream.range(0,4).mapToObj(i->pool.submit(()->{start.await();return assigned(f,poll(f,request,200)).stream().map(RuntimeInstance::id).toList();})).toList();start.countDown();
            var first=calls.getFirst().get(15,TimeUnit.SECONDS);assertThat(first).hasSize(2);
            for(var call:calls)assertThat(call.get(15,TimeUnit.SECONDS)).isEqualTo(first);
        }
        var open=allocations.open(f.supervisor().id()).stream().map(a->runtimes.runtime(a.runtimeId()).orElseThrow()).toList();
        assertThat(allocations.pending(f.vd().id(),100)).hasSize(6);
        for(var r:open)runner(f,r,"claim",runnerBody(f,r),200);
        var r=open.getFirst();runner(f,r,"commit",resultBody(f,r),201);runner(f,r,"commit",resultBody(f,r),200);
        assertThat(allocations.open(f.supervisor().id())).hasSize(2);assertThat(runtimes.result(r.taskId()).orElseThrow().vdRuntimeId()).isEqualTo(f.supervisor().id());
        var response=poll(f,body(f,2,List.of(open.getLast()),List.of(r)),200);
        assertThat(assigned(f,response)).hasSize(1);assertThat(response.get("acknowledgedAttempts")).isEqualTo(List.of(r.attemptId().toString()));
        assertThat(allocations.byRuntime(r.id()).orElseThrow().closeReason()).isEqualTo("PROCESS_EXIT");assertThat(current(r).observedState()).isEqualTo("TERMINATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_command WHERE runtime_id=?",Integer.class,r.id())).isZero();
        verifyNoInteractions(jobs);
    }
    @Test void completionWithoutVerifiedResultFailsAndReplayedAckDoesNotRefillCapacity()throws Exception {
        var f=fixture(3,1);var r=assigned(f,poll(f,body(f,1,List.of(),List.of()),200)).getFirst();
        var completion=body(f,2,List.of(),List.of(r));var response=poll(f,completion,200);
        assertThat(current(r).failureReason()).isEqualTo("RESULT_MISSING");assertThat(runtimes.result(r.taskId())).isEmpty();
        var newWork=assigned(f,response);assertThat(newWork).hasSize(1);
        var replay=poll(f,completion,200);assertThat(assigned(f,replay).stream().map(RuntimeInstance::id)).isEqualTo(newWork.stream().map(RuntimeInstance::id).toList());
        assertThat(replay.get("acknowledgedAttempts")).isEqualTo(List.of(r.attemptId().toString()));assertThat(allocations.pending(f.vd().id(),10)).hasSize(1);
        var forged=body(f,3,newWork,List.of(r));poll(f,forged,409);assertThat(receipts.find(f.supervisor().id()).orElseThrow().sequence()).isEqualTo(2);
    }
    @Test void lostAssignmentResponseThenCancelUsesLaterAbsenceProofWithoutKillingSharedPod()throws Exception {
        var f=fixture(3,2);var firstRequest=body(f,1,List.of(),List.of());var first=assigned(f,poll(f,firstRequest,200));
        var cancelled=first.getFirst();var other=first.getLast();executionApi.cancelTask(cancelled.taskId(),"{}");
        var replay=poll(f,firstRequest,200);assertThat(replay.get("cancelAttempts")).isEqualTo(List.of(cancelled.attemptId().toString()));
        assertThat(assigned(f,replay).stream().map(RuntimeInstance::id)).containsExactly(other.id());
        assertThat(allocations.byRuntime(cancelled.id()).orElseThrow().open()).isTrue();
        runner(f,other,"claim",runnerBody(f,other),200);
        var next=poll(f,body(f,2,List.of(other),List.of()),200);assertThat(assigned(f,next)).hasSize(1);
        var closed=allocations.byRuntime(cancelled.id()).orElseThrow();assertThat(closed.closeReason()).isEqualTo("NOT_STARTED");assertThat(closed.exitCode()).isNull();assertThat(closed.completionSequence()).isEqualTo(2);
        assertThat(executions.task(cancelled.taskId()).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(current(other).observedState()).isEqualTo("RUNNING");assertThat(supervisors.runtime(f.supervisor().id()).orElseThrow().ready(clock.instant())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.vd_runtime_command WHERE runtime_id=? AND kind='DELETE'",Integer.class,f.supervisor().id())).isZero();
    }
    @Test void lateVerifiedCommitCannotUndoCancelAndSlotWaitsForAcknowledgedExit()throws Exception {
        var f=fixture(2,2);var work=assigned(f,poll(f,body(f,1,List.of(),List.of()),200));var r=work.getFirst();
        runner(f,r,"claim",runnerBody(f,r),200);var manifest=new ResultManifest(List.of(new ResultManifest.Output("output",2,"a".repeat(64),"application/json","fixture-version")));
        var permit=lifecycle.prepareCommit(r.attemptId(),r.epoch(),f.supervisor().podUid(),manifest);
        executionApi.cancelTask(r.taskId(),"{}");
        var c=manifest.outputs().getFirst().content(r.taskId(),r.attemptId());var verified=new TaskResult.Output("output",new VerifiedArtifact("fixture-only",c.objectKey(),"fixture-version",c.sha256(),c.bytes(),c.mediaType()));
        fenced(()->lifecycle.commitVerified(permit,List.of(verified)));runner(f,r,"commit",resultBody(f,r),409);
        var response=poll(f,body(f,2,work,List.of()),200);assertThat(response.get("cancelAttempts")).isEqualTo(List.of(r.attemptId().toString()));
        assertThat(allocations.open(f.supervisor().id())).hasSize(2);assertThat(runtimes.result(r.taskId())).isEmpty();
        poll(f,body(f,3,List.of(work.getLast()),List.of(r)),200);assertThat(allocations.open(f.supervisor().id())).hasSize(1);
        assertThat(executions.task(r.taskId()).orElseThrow().state()).isEqualTo("CANCELLED");
    }
    @Test void drainWaitsForTasksAndFinalExitStopsSupervisorWithoutInventingResult()throws Exception {
        var f=fixture(1,1);var r=assigned(f,poll(f,body(f,1,List.of(),List.of()),200)).getFirst();runner(f,r,"claim",runnerBody(f,r),200);
        var leased=supervisors.runtime(f.supervisor().id()).orElseThrow();
        assertThat(lifecycle.authorizeProducerUntil(r.attemptId(),r.epoch(),f.supervisor().podUid())).isEqualTo(leased.leaseUntil()).isBefore(current(r).expiresAt());
        vdLifecycle.drain(f.vd().id(),0,"drain");fenced(()->vdLifecycle.drained(f.supervisor().id(),f.session()));
        var requested=supervisors.runtime(f.supervisor().id()).orElseThrow();
        assertThat(lifecycle.authorizeProducerUntil(r.attemptId(),r.epoch(),f.supervisor().podUid())).isEqualTo(requested.drainDeadline()).isBefore(requested.leaseUntil());
        var draining=poll(f,body(f,2,List.of(r),List.of()),200);assertThat(draining.get("command")).isEqualTo("DRAIN");assertThat(assigned(f,draining)).isEmpty();
        lifecycle.authorize(r.attemptId(),r.epoch(),f.supervisor().podUid());
        var supervisor=supervisors.runtime(f.supervisor().id()).orElseThrow();
        assertThat(lifecycle.authorizeProducerUntil(r.attemptId(),r.epoch(),f.supervisor().podUid())).isEqualTo(supervisor.leaseUntil()).isBeforeOrEqualTo(supervisor.drainDeadline());
        var done=poll(f,body(f,3,List.of(),List.of(r)),200);assertThat(done.get("command")).isEqualTo("STOP");
        assertThat(supervisors.runtime(f.supervisor().id()).orElseThrow().desiredState()).isEqualTo("STOPPED");assertThat(runtimes.result(r.taskId())).isEmpty();
        runner(f,r,"claim",runnerBody(f,r),401);
    }
    @Test void lostResponseBeforeDrainReplaysCancellationThenProvesNoChildStarted()throws Exception {
        var f=fixture(1,1);var request=body(f,1,List.of(),List.of());var r=assigned(f,poll(f,request,200)).getFirst();vdLifecycle.drain(f.vd().id(),0,"drain");
        var replay=poll(f,request,200);assertThat(replay.get("command")).isEqualTo("DRAIN");assertThat(assigned(f,replay)).isEmpty();assertThat(replay.get("cancelAttempts")).isEqualTo(List.of(r.attemptId().toString()));
        var done=poll(f,body(f,2,List.of(),List.of()),200);assertThat(done.get("command")).isEqualTo("STOP");assertThat(allocations.byRuntime(r.id()).orElseThrow().closeReason()).isEqualTo("NOT_STARTED");
    }
    @Test void supervisorLossFencesCommitAndRetryWaitsForPhysicalTermination()throws Exception {
        var f=fixture(1,1,true);var r=assigned(f,poll(f,body(f,1,List.of(),List.of()),200)).getFirst();runner(f,r,"claim",runnerBody(f,r),200);
        vdLifecycle.fail(f.supervisor().id(),"RUNTIME_LOST");runner(f,r,"commit",resultBody(f,r),409);taskService.reconcile(r.id());
        assertThat(current(r).failureReason()).isEqualTo("RUNTIME_LOST");clock.offset.addAndGet(2);assertThat(lifecycle.retryTask(r.taskId())).isFalse();assertThat(allocations.open(f.supervisor().id())).hasSize(1);
        jdbc.update("UPDATE edgeai.vd_runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id=? AND kind='CREATE'",f.supervisor().id());
        vdLifecycle.confirmStopped(f.supervisor().id());assertThat(allocations.byRuntime(r.id()).orElseThrow().closeReason()).isEqualTo("POD_GONE");
        assertThat(lifecycle.retryTask(r.taskId())).isTrue();var next=executions.attempts(r.taskId()).getFirst();assertThat(next.epoch()).isEqualTo(2);assertThat(next.vdId()).isEqualTo(f.vd().id());
        var pending=runtimes.byAttempt(next.id()).orElseThrow();assertThat(pending.observedState()).isEqualTo("PENDING");assertThat(pending.jobName()).isNull();
    }
    @Test void expiredLeaseFencesProducerButDoesNotReleaseSlotBeforePodTermination()throws Exception {
        var f=fixture(1,1);var r=assigned(f,poll(f,body(f,1,List.of(),List.of()),200)).getFirst();runner(f,r,"claim",runnerBody(f,r),200);
        clock.offset.addAndGet(61);fenced(()->lifecycle.authorizeProducerUntil(r.attemptId(),r.epoch(),f.supervisor().podUid()));
        runner(f,r,"commit",resultBody(f,r),409);taskService.reconcile(r.id());assertThat(current(r).failureReason()).isEqualTo("RUNTIME_LOST");
        assertThat(allocations.byRuntime(r.id()).orElseThrow().open()).isTrue();assertThat(current(r).observedState()).isEqualTo("RUNNING");
    }
    @Test void queueTimeoutAndUnallocatedCancellationTerminateWithoutSupervisorMutation()throws Exception {
        var f=fixture(2,1);var first=f.work().getFirst();var second=f.work().getLast();executionApi.cancelTask(first.taskId(),"{}");taskService.reconcile(first.id());
        assertThat(current(first).observedState()).isEqualTo("TERMINATED");clock.offset.addAndGet(121);taskService.reconcile(second.id());
        assertThat(current(second).failureReason()).isEqualTo("DISPATCH_TIMEOUT");assertThat(current(second).observedState()).isEqualTo("TERMINATED");
        assertThat(allocations.open(f.supervisor().id())).isEmpty();assertThat(supervisors.runtime(f.supervisor().id()).orElseThrow().desiredState()).isEqualTo("RUNNING");
    }
    @Test void foreignReportsWrongPodAndForgottenClaimedWorkCannotAdvanceReceipt()throws Exception {
        var f=fixture(1,1);var other=fixture(1,1);var r=assigned(f,poll(f,body(f,1,List.of(),List.of()),200)).getFirst();runner(f,r,"claim",runnerBody(f,r),200);
        var before=supervisors.runtime(f.supervisor().id()).orElseThrow().leaseUntil();
        poll(f,body(f,2,List.of(other.work().getFirst()),List.of()),409);poll(f,body(f,2,List.of(),List.of()),409);
        assertThat(receipts.find(f.supervisor().id()).orElseThrow().sequence()).isEqualTo(1);assertThat(supervisors.runtime(f.supervisor().id()).orElseThrow().leaseUntil()).isEqualTo(before);
        var wrong=runnerBody(f,r);wrong.put("podUid",other.supervisor().podUid().toString());runner(f,r,"claim",wrong,409);
        var identity=identities.get(f.supervisor().id());identities.put(f.supervisor().id(),new VDGateway.PodIdentity(UUID.randomUUID(),identity.nodeUid(),identity.nodeName(),true));
        runner(f,r,"claim",runnerBody(f,r),409);verifyNoInteractions(jobs);
    }
    @Test void notStartedStorageProofRequiresLaterSequenceAndNoExitCode()throws Exception {
        var f=fixture(1,1);var r=assigned(f,poll(f,body(f,1,List.of(),List.of()),200)).getFirst();executionApi.cancelTask(r.taskId(),"{}");
        transaction(()->{executions.run(r.runId(),true);runtimes.terminated(r.id(),clock.instant());return null;});
        assertThatThrownBy(()->transaction(()->{allocations.close(r.id(),"NOT_STARTED",1L,null,clock.instant());return null;})).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->transaction(()->{allocations.close(r.id(),"NOT_STARTED",2L,0,clock.instant());return null;})).isInstanceOf(DataIntegrityViolationException.class);
        transaction(()->{allocations.close(r.id(),"NOT_STARTED",2L,null,clock.instant());return null;});assertThat(allocations.byRuntime(r.id()).orElseThrow().closeReason()).isEqualTo("NOT_STARTED");
    }
}
