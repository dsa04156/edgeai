package io.edgeai.app.integration;

import com.sun.net.httpserver.HttpServer;
import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.vd.*;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Python supervisor + child Runner + HTTP + PostgreSQL + versioned S3.
 * Kubernetes Pod identity/readiness is a fixture; real Kubernetes acceptance is a separate gate. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"edgeai.runtime.enabled=true","edgeai.vd.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.vd.lease-seconds=30","edgeai.runtime.namespace=vd-artifact-test"})
class VDTaskArtifactIntegrationTest {
    private static final String BUCKET="edgeai-vd-task-"+UUID.randomUUID();
    private static final Path KEY=key();
    private static Path key(){try{var p=Files.createTempFile("edgeai-vd-artifact-",".key");byte[] b=new byte[32];new java.security.SecureRandom().nextBytes(b);Files.writeString(p,HexFormat.of().formatHex(b));return p;}catch(Exception e){throw new IllegalStateException("Fixture key unavailable");}}
    private static String required(String name){String v=System.getenv(name);if(v==null || v.isBlank())throw new IllegalStateException("Missing environment: "+name);return v;}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("edgeai.runtime.key-file",KEY::toString);r.add("edgeai.storage.bucket",()->BUCKET);r.add("edgeai.storage.endpoint",()->required("EDGEAI_STORAGE_URL"));r.add("edgeai.storage.runner-endpoint",()->required("EDGEAI_STORAGE_URL"));}
    @AfterAll static void removeKey()throws Exception{Files.deleteIfExists(KEY);}
    @Autowired ProfileService profiles;
    @Autowired WorkflowService workflows;
    @Autowired VirtualDeviceService devices;
    @Autowired VDLifecycleService vdLifecycle;
    @Autowired VDTokenService vdTokens;
    @Autowired ExecutionService executionApi;
    @Autowired ExecutionRepository executions;
    @Autowired RuntimeRepository runtimes;
    @Autowired VDTaskRepository allocations;
    @Autowired VDPollRepository polls;
    @Autowired S3ArtifactStore storage;
    @Autowired ManagedDeviceService sensors;
    @Autowired SensorHistoryService history;
    @MockitoBean VDGateway gateway;
    @MockitoBean RuntimeGateway jobs;
    @LocalServerPort int port;
    @TempDir Path directory;
    private final JsonDocuments json=new JsonDocuments();
    private MinioClient admin;
    private Process process;
    private final List<Process> processes=new ArrayList<>();
    private final Map<UUID,VDRuntime> launched=new HashMap<>();
    private final Map<UUID,Path> workDirectories=new HashMap<>();
    private final Map<UUID,UUID> nodeIdentities=new HashMap<>();
    private URI proxyOrigin;
    private HttpServer proxy;
    private ExecutorService proxyExecutor;
    private HttpClient client;
    private VDRuntime supervisor;
    private UUID vdId;
    private Path work;
    private final AtomicBoolean dropAssignment=new AtomicBoolean(),dropClaim=new AtomicBoolean(),dropCommit=new AtomicBoolean();
    private String encode(Object v){return json.canonical(v);}
    @BeforeEach void storage()throws Exception {
        admin=MinioClient.builder().endpoint(required("EDGEAI_STORAGE_URL")).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterEach void cleanup()throws Exception {
        try {
            for(var child:processes)if(child.isAlive()){child.destroy();if(!child.waitFor(8,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
            for(var runtime:launched.values())if(!vdLifecycle.get(runtime.id()).desiredState().equals("STOPPED"))vdLifecycle.fail(runtime.id(),"RUNTIME_LOST");
        } finally {
            if(proxy!=null)proxy.stop(0);if(proxyExecutor!=null)proxyExecutor.close();if(client!=null)client.close();
            if(admin!=null)try {
                for(var item:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).includeVersions(true).build())){
                    var v=item.get();admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(v.objectName()).versionId(v.versionId()).build());
                }
                admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
            }finally{admin.close();}
        }
    }
    private URI proxy(boolean loseReplies)throws Exception {
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);proxyExecutor=Executors.newVirtualThreadPerTaskExecutor();proxy.setExecutor(proxyExecutor);
        proxy.createContext("/internal/",exchange->{
            try {
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+exchange.getRequestURI())).timeout(Duration.ofSeconds(8));
                for(String name:List.of("Authorization","X-EdgeAI-Pod-Token","Content-Type")){String v=exchange.getRequestHeaders().getFirst(name);if(v!=null)request.header(name,v);}
                var response=client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());
                int status=response.statusCode();byte[] data=response.body();String path=exchange.getRequestURI().getPath();
                if(loseReplies && path.endsWith("/poll") && status==200 && !((List<?>)((Map<?,?>)json.decode(new String(data,java.nio.charset.StandardCharsets.UTF_8))).get("assignments")).isEmpty() && dropAssignment.compareAndSet(false,true)
                    || loseReplies && path.endsWith("/claim") && status==200 && dropClaim.compareAndSet(false,true)
                    || loseReplies && path.endsWith("/commit") && status==201 && dropCommit.compareAndSet(false,true)) {
                    status=503;data="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,data.length);exchange.getResponseBody().write(data);
            }catch(Exception e){exchange.sendResponseHeaders(503,-1);}finally{exchange.close();}
        });proxy.start();return URI.create("http://127.0.0.1:"+proxy.getAddress().getPort());
    }
    private UUID fixture(boolean loseReplies)throws Exception {return fixture(loseReplies,false);}
    private UUID fixture(boolean loseReplies,boolean sensor)throws Exception {return fixture(loseReplies,sensor,false);}
    private UUID fixture(boolean loseReplies,boolean sensor,boolean processing)throws Exception {
        return fixture(loseReplies,sensor,processing?Map.of("apiVersion","edgeai.processing/v1","sampleCount",1,"outputPort","output",
            "pipelines",List.of(Map.of("name","temperature","input",Map.of("sourceKey","input","property","temp_humidity"),"steps",List.of(Map.of("op","movingAverage","window",1))))):null);
    }
    @SuppressWarnings("unchecked") private UUID fixture(boolean loseReplies,boolean sensor,Map<String,Object> processing)throws Exception {
        if(proxyOrigin==null)proxyOrigin=proxy(loseReplies);URI origin=proxyOrigin;var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("command",List.of("python3",Path.of(sensor?"../../runner/examples/temperature.py":"../../runner/examples/linear.py").toAbsolutePath().normalize().toString()));
        if(processing!=null)spec.put("command",List.of("python3","-m","edgeai_runner.processing"));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        UUID sp=profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key","vd-artifact-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
        var source=sensor?sensors.get("sensehat-001"):Map.<String,Object>of();
        var vdSpec=new LinkedHashMap<String,Object>(Map.of("apiVersion",sensor || processing!=null?"edgeai.vd/v2":"edgeai.vd/v1","type",sensor?"sensorMirror":"emulation","serviceProfileVersionId",sp.toString(),"sources",sensor?Map.of("input",Map.of("deviceModel",source.get("model"),"required",true)):Map.of(),"state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",2,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30)));
        if(processing!=null){vdSpec.put("type","processing");vdSpec.put("processing",processing);}
        UUID vp=profiles.publish(ProfileIdentity.Kind.VD,encode(Map.of("key","vd-artifact-"+UUID.randomUUID(),"version","1.0.0","spec",vdSpec))).version().id();
        int maxAge=processing!=null && ((Number)processing.get("sampleCount")).intValue()>1?120:30;
        var vd=devices.create(encode(Map.of("key","vd-artifact-"+UUID.randomUUID(),"displayName","Actual VD child Runner","profileVersionId",vp.toString(),"sources",sensor?List.of(Map.of("sourceKey","input","deviceId","sensehat-001","properties",List.of("temp_humidity"),"maxAgeSeconds",maxAge)):List.of(),"placement",Map.of("mode","AUTO")))).value();vdId=vd.id();
        var op=vdLifecycle.provision(vd.id(),0,"provision",new RuntimeSettings("vd-artifact-test","edgeai-runner",origin,120),false);
        supervisor=vdLifecycle.submitted(op.targetRuntimeId(),UUID.randomUUID());UUID node=UUID.randomUUID();work=directory.resolve("work-"+supervisor.id());Files.createDirectory(work);
        launched.put(supervisor.id(),supervisor);workDirectories.put(supervisor.id(),work);
        nodeIdentities.put(supervisor.id(),node);
        doAnswer(call->{VDRuntime r=call.getArgument(0);var current=launched.get(r.id());if(!"pod-proof-fixture".equals(call.getArgument(1)) || current==null)throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return new VDGateway.PodIdentity(current.podUid(),nodeIdentities.get(r.id()),"fixture-node",Files.exists(workDirectories.get(r.id()).resolve(".vd-ready")));}).when(gateway).authenticatePod(any(),any());
        Path token=directory.resolve("claim-"+supervisor.id()),proof=directory.resolve("proof-"+supervisor.id());Files.writeString(token,vdTokens.issue(supervisor));Files.writeString(proof,"pod-proof-fixture");
        Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));Files.setPosixFilePermissions(proof,PosixFilePermissions.fromString("rw-------"));
        var command=new ProcessBuilder("python3",Path.of("../../runner/vd.py").toAbsolutePath().normalize().toString());var env=command.environment();env.keySet().removeIf(k->k.startsWith("EDGEAI_"));
        env.putAll(Map.of("EDGEAI_VD_ID",vd.id().toString(),"EDGEAI_VD_RUNTIME_ID",supervisor.id().toString(),"EDGEAI_VD_GENERATION","1","EDGEAI_POD_UID",supervisor.podUid().toString(),"EDGEAI_CONTROL_PLANE_URL",origin.toString(),"EDGEAI_VD_CLAIM_FILE",token.toString(),"EDGEAI_POD_TOKEN_FILE",proof.toString(),"EDGEAI_WORK_DIR",work.toString(),"EDGEAI_VD_MAX_CONCURRENT_TASKS","2","EDGEAI_VD_STARTUP_SECONDS","60"));
        env.put("EDGEAI_VD_DRAIN_SECONDS","30");env.put("PYTHONDONTWRITEBYTECODE","1");Path log=directory.resolve("supervisor-"+supervisor.id()+".log");Files.createFile(log,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        process=command.redirectOutput(log.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        processes.add(process);
        await(()->vdLifecycle.get(supervisor.id()).ready(Instant.now()),15,"Actual HTTP supervisor did not become Ready");return sp;
    }
    private UUID run(UUID sp,boolean dag,int delay)throws Exception {
        var wf=workflows.create(encode(Map.of("key","vd-artifact-"+UUID.randomUUID(),"displayName","Actual child task"))).value();
        var tasks=new ArrayList<Object>();
        for(String key:dag?List.of("root","child","independent"):List.of("slow","fast"))tasks.add(Map.of("key",key,"serviceProfileVersionId",sp.toString(),"parameters",Map.of("features",List.of(2,1),"weights",List.of(2,3),"bias",1,"simulationDelayMillis",key.equals("fast")?100:delay)));
        var version=workflows.publish(wf.id(),encode(Map.of("version","1.0.0","tasks",tasks,"dependencies",dag?List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH")):List.of()))).value();
        return executionApi.create(UUID.randomUUID().toString(),encode(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","VD","vdId",vdId.toString()),"parameters",Map.of()))).value().id();
    }
    private void await(BooleanSupplier condition,int seconds,String message)throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();do{if(condition.getAsBoolean())return;if(process!=null && !process.isAlive())throw new AssertionError("Supervisor exited before condition: "+message);Thread.sleep(100);}while(System.nanoTime()<end);throw new AssertionError(message);
    }
    private void drain()throws Exception {
        vdLifecycle.drain(vdId,0,"drain");assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        assertThat(allocations.open(supervisor.id())).isEmpty();assertThat(polls.find(supervisor.id()).orElseThrow().command()).isEqualTo("STOP");
        assertThat(Files.exists(work.resolve(".vd-ready"))).isFalse();try(var paths=Files.list(work.resolve("attempts"))){assertThat(paths.count()).isZero();}
    }
    @Test void actualChildDagProducesVerifiedS3ResultsDespiteLostAssignmentAndCommitResponses()throws Exception {
        UUID sp=fixture(true);UUID run=run(sp,true,500);
        await(()->executions.run(run,false).orElseThrow().state().equals("SUCCEEDED"),30,"Actual VD DAG did not succeed");
        await(()->allocations.open(supervisor.id()).isEmpty(),10,"Completed child process slots not acknowledged");
        assertThat(dropAssignment).isTrue();assertThat(dropClaim).isTrue();assertThat(dropCommit).isTrue();
        for(var task:executions.tasks(run)) {
            var result=runtimes.result(task.id()).orElseThrow();assertThat(result.vdRuntimeId()).isEqualTo(supervisor.id());assertThat(result.producerPodUid()).isEqualTo(supervisor.podUid());assertThat(executions.attempts(task.id())).hasSize(1);
            var r=runtimes.runtime(result.runtimeId()).orElseThrow();assertThat(r.jobName()).isNull();assertThat(r.observedState()).isEqualTo("TERMINATED");
            verifyStart(r);verifyResult(r);
            var artifact=result.outputs().getFirst().artifact();var grant=storage.download(artifact);
            var response=client.send(HttpRequest.newBuilder(grant.url()).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);var data=(Map<?,?>)json.decode(response.body());assertThat(data.get("sourceMode")).isEqualTo("SYNTHETIC");assertThat(((Number)data.get("score")).doubleValue()).isEqualTo(8);
        }
        drain();verifyNoInteractions(jobs);
    }
    @Test void actualCancellationStopsOnlySelectedChildAndSharedSupervisorCompletesOtherWork()throws Exception {
        UUID sp=fixture(false);UUID run=run(sp,false,8000);var tasks=executions.tasks(run);var slow=tasks.stream().filter(t->t.key().equals("slow")).findFirst().orElseThrow();var fast=tasks.stream().filter(t->t.key().equals("fast")).findFirst().orElseThrow();
        UUID attempt=executions.attempts(slow.id()).getFirst().id();await(()->executions.attempt(attempt).orElseThrow().state().equals("RUNNING"),15,"Slow child did not claim");
        executionApi.cancelTask(slow.id(),"{}");await(()->executions.task(slow.id()).orElseThrow().state().equals("CANCELLED"),15,"Cancelled child did not terminate");
        await(()->executions.task(fast.id()).orElseThrow().state().equals("SUCCEEDED"),15,"Other child did not succeed");await(()->allocations.open(supervisor.id()).isEmpty(),10,"Final slots not acknowledged");
        assertThat(runtimes.result(slow.id())).isEmpty();assertThat(runtimes.result(fast.id())).isPresent();assertThat(process.isAlive()).isTrue();assertThat(vdLifecycle.get(supervisor.id()).ready(Instant.now())).isTrue();
        assertThat(allocations.byRuntime(runtimes.byAttempt(attempt).orElseThrow().id()).orElseThrow().exitCode()).isNotZero();
        var accepted=runtimes.runtime(runtimes.result(fast.id()).orElseThrow().runtimeId()).orElseThrow();
        verifyStart(accepted);verifyResult(accepted);drain();verifyNoInteractions(jobs);
    }
    @Test @Tag("sensor-hardware") void realSenseHatTemperatureRunsThroughSupervisorRunnerAndVerifiedS3()throws Exception {
        UUID sp=fixture(false,true);
        var wf=workflows.create(encode(Map.of("key","sensehat-acceptance-"+UUID.randomUUID(),"displayName","Sense HAT temperature acceptance"))).value();
        var version=workflows.publish(wf.id(),encode(Map.of("version","1.0.0","tasks",List.of(Map.of("key","temperature","serviceProfileVersionId",sp.toString(),"parameters",Map.of())),"dependencies",List.of()))).value();
        var csrf=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        var token=(Map<?,?>)json.decode(csrf.body());
        String cookie=csrf.headers().allValues("Set-Cookie").stream().map(value->value.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));
        var created=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/workflow-runs"))
            .header("Content-Type","application/json").header("Cookie",cookie).header("X-CSRF-TOKEN",(String)token.get("token")).header("Idempotency-Key",UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString(encode(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","VD","vdId",vdId.toString()),"parameters",Map.of())))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        UUID runId=UUID.fromString((String)((Map<?,?>)json.decode(created.body())).get("id"));
        var task=executions.tasks(runId).getFirst();
        var frame=(Map<?,?>)json.decode(executions.vdInput(task.id()).orElseThrow());
        var source=(Map<?,?>)((Map<?,?>)frame.get("sources")).get("input");
        var reading=(Map<?,?>)((Map<?,?>)source.get("properties")).get("temp_humidity");
        await(()->executions.run(runId,false).orElseThrow().state().equals("SUCCEEDED"),30,"Sense HAT input did not produce a verified Result");
        await(()->allocations.open(supervisor.id()).isEmpty(),10,"Sensor child slot was not acknowledged");
        var result=runtimes.result(task.id()).orElseThrow();var artifact=result.outputs().getFirst().artifact();
        var response=client.send(HttpRequest.newBuilder(storage.download(artifact).url()).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);var output=(Map<?,?>)json.decode(response.body());
        assertThat(output.get("sourceMode")).isEqualTo("SENSOR");assertThat(output.get("deviceUid")).isEqualTo(source.get("deviceUid"));
        assertThat(((Number)output.get("temperatureC")).doubleValue()).isEqualTo(((Number)reading.get("value")).doubleValue());
        assertThat(((Number)output.get("temperatureF")).doubleValue()).isCloseTo(((Number)reading.get("value")).doubleValue()*1.8+32,within(0.000001));
        var taskResponse=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/tasks/"+task.id())).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(taskResponse.statusCode()).isEqualTo(200);
        assertThat(encode(((Map<?,?>)json.decode(taskResponse.body())).get("vdInputs"))).isEqualTo(encode(frame));
        var resultsResponse=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/tasks/"+task.id()+"/results")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(resultsResponse.statusCode()).isEqualTo(200);assertThat(resultsResponse.body()).contains(artifact.sha256());
        verifyStart(runtimes.runtime(result.runtimeId()).orElseThrow());verifyResult(runtimes.runtime(result.runtimeId()).orElseThrow());
        System.out.println("VD_SENSOR_ACCEPTANCE "+encode(Map.of("deviceId",source.get("deviceId"),"deviceUid",source.get("deviceUid"),"observedAt",reading.get("observedAt"),"capturedAt",frame.get("capturedAt"),"temperatureC",output.get("temperatureC"),"temperatureF",output.get("temperatureF"),"sha256",artifact.sha256(),"bytes",artifact.bytes(),"runState","SUCCEEDED","podBoundary","fixture")));
        drain();verifyNoInteractions(jobs);
    }
    @Test @Tag("sensor-hardware") void realSenseHatUsesTheCommonProcessingEngineAndPublishesAVerifiedResult()throws Exception {
        UUID sp=fixture(false,true,true);
        var wf=workflows.create(encode(Map.of("key","processing-acceptance-"+UUID.randomUUID(),"displayName","Physical sensor common processing"))).value();
        var version=workflows.publish(wf.id(),encode(Map.of("version","1.0.0","tasks",List.of(Map.of("key","process","serviceProfileVersionId",sp.toString(),"parameters",Map.of())),"dependencies",List.of()))).value();
        var run=executionApi.create(UUID.randomUUID().toString(),encode(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","VD","vdId",vdId.toString()),"parameters",Map.of()))).value();
        var task=executions.tasks(run.id()).getFirst();
        var frame=(Map<?,?>)json.decode(executions.vdInput(task.id()).orElseThrow());
        var reading=(Map<?,?>)((Map<?,?>)((Map<?,?>)((Map<?,?>)frame.get("sources")).get("input")).get("properties")).get("temp_humidity");
        await(()->executions.run(run.id(),false).orElseThrow().state().equals("SUCCEEDED"),30,"Physical processing task did not succeed");
        var result=runtimes.result(task.id()).orElseThrow();var artifact=result.outputs().getFirst().artifact();
        var response=client.send(HttpRequest.newBuilder(storage.download(artifact).url()).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);var output=(Map<?,?>)json.decode(response.body());
        assertThat(output.get("apiVersion")).isEqualTo("edgeai.processing-output/v1");
        var values=(List<?>)((Map<?,?>)((Map<?,?>)output.get("outputs")).get("temperature")).get("values");
        assertThat(((Number)values.getFirst()).doubleValue()).isEqualTo(((Number)reading.get("value")).doubleValue());
        assertThat(output.get("vdId")).isEqualTo(vdId.toString());
        var preview=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/tasks/"+task.id()+"/results/output/content")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(json.canonical(json.decode(preview.body()))).isEqualTo(json.canonical(output));
        verifyStart(runtimes.runtime(result.runtimeId()).orElseThrow());verifyResult(runtimes.runtime(result.runtimeId()).orElseThrow());
        System.out.println("VD_SENSOR_ACCEPTANCE "+encode(Map.of("processing","movingAverage","value",values.getFirst(),"observedAt",reading.get("observedAt"),"runState","SUCCEEDED","sha256",artifact.sha256(),"podBoundary","fixture")));
        await(()->allocations.open(supervisor.id()).isEmpty(),10,"Processing slot was not acknowledged");drain();verifyNoInteractions(jobs);
    }
    @Test @Tag("sensor-hardware") void realSenseHatMovingAverageUsesThreeDistinctMapperObservations()throws Exception {
        UUID sp=fixture(false,true,Map.of("apiVersion","edgeai.processing/v1","sampleCount",3,"outputPort","output",
            "pipelines",List.of(Map.of("name","average","input",Map.of("sourceKey","input","property","temp_humidity"),"steps",List.of(Map.of("op","movingAverage","window",3))))));
        UUID uid=UUID.fromString((String)sensors.get("sensehat-001").get("uid"));
        await(()->{
            history.collect();
            try {return history.window(uid,"temp_humidity",SensorHistoryService.configuration(sensors.get("sensehat-001")),Instant.now(),120,3).size()==3;}
            catch(io.edgeai.app.exception.ControlPlaneException e){if(!e.code().equals("VD_PROCESSING_WINDOW_PENDING"))throw e;return false;}
        },90,"Three distinct physical Mapper observations were not available within the configured 120-second age");
        var wf=workflows.create(encode(Map.of("key","window-acceptance-"+UUID.randomUUID(),"displayName","Physical three-sample mean"))).value();
        var version=workflows.publish(wf.id(),encode(Map.of("version","1.0.0","tasks",List.of(Map.of("key","average","serviceProfileVersionId",sp.toString(),"parameters",Map.of())),"dependencies",List.of()))).value();
        var run=executionApi.create(UUID.randomUUID().toString(),encode(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","VD","vdId",vdId.toString()),"parameters",Map.of()))).value();
        var task=executions.tasks(run.id()).getFirst();var frame=(Map<?,?>)json.decode(executions.vdInput(task.id()).orElseThrow());
        var reading=(Map<?,?>)((Map<?,?>)((Map<?,?>)((Map<?,?>)frame.get("sources")).get("input")).get("properties")).get("temp_humidity");
        var samples=(List<?>)reading.get("samples");
        assertThat(samples).hasSize(3);assertThat(samples.stream().map(value->((Map<?,?>)value).get("observedAt")).distinct().count()).isEqualTo(3);
        double expected=samples.stream().mapToDouble(value->((Number)((Map<?,?>)value).get("value")).doubleValue()/3).sum();
        await(()->executions.run(run.id(),false).orElseThrow().state().equals("SUCCEEDED"),30,"Physical observation window did not produce a Result");
        var result=runtimes.result(task.id()).orElseThrow();var artifact=result.outputs().getFirst().artifact();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/tasks/"+task.id()+"/results/output/content")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);var output=(Map<?,?>)json.decode(response.body());
        var values=(List<?>)((Map<?,?>)((Map<?,?>)output.get("outputs")).get("average")).get("values");
        assertThat(((Number)values.getFirst()).doubleValue()).isCloseTo(expected,within(0.000001));
        verifyStart(runtimes.runtime(result.runtimeId()).orElseThrow());verifyResult(runtimes.runtime(result.runtimeId()).orElseThrow());
        System.out.println("VD_SENSOR_ACCEPTANCE "+encode(Map.of("processing","movingAverage","samples",samples,"value",values.getFirst(),"runState","SUCCEEDED","sha256",artifact.sha256(),"podBoundary","fixture")));
        await(()->allocations.open(supervisor.id()).isEmpty(),10,"Window-processing slot was not acknowledged");drain();verifyNoInteractions(jobs);
    }
    @Test @Tag("sensor-hardware") void realSensorProcessingFeedsAnotherVdAndAnAiWorkflowThroughVerifiedArtifacts()throws Exception {
        UUID sensorService=fixture(false,true,true),sensorVd=vdId;
        UUID derivedService=fixture(false,false,Map.of("apiVersion","edgeai.processing/v1","sampleCount",1,"outputPort","output",
            "pipelines",List.of(Map.of("name","energy","input",Map.of("port","input","output","temperature"),"steps",List.of(Map.of("op","rms","window",1)))))),derivedVd=vdId;
        UUID aiService=fixture(false),aiVd=vdId;
        var wf=workflows.create(encode(Map.of("key","vd-chain-"+UUID.randomUUID(),"displayName","Sensor VD → derived VD → AI"))).value();
        var version=workflows.publish(wf.id(),encode(Map.of("version","1.0.0","tasks",List.of(
            Map.of("key","sensor","serviceProfileVersionId",sensorService.toString(),"parameters",Map.of()),
            Map.of("key","derived","serviceProfileVersionId",derivedService.toString(),"parameters",Map.of()),
            Map.of("key","inference","serviceProfileVersionId",aiService.toString(),"parameters",Map.of("weights",List.of(2),"bias",1,"inputPipeline","energy"))),
            "dependencies",List.of(Map.of("fromTask","sensor","toTask","derived","fromPort","output","toPort","input","mode","BATCH"),
                Map.of("fromTask","derived","toTask","inference","fromPort","output","toPort","input","mode","BATCH"))))).value();
        var run=executionApi.create(UUID.randomUUID().toString(),encode(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of(),
            "taskExecutions",Map.of("sensor",Map.of("mode","VD","vdId",sensorVd.toString()),"derived",Map.of("mode","VD","vdId",derivedVd.toString()),"inference",Map.of("mode","VD","vdId",aiVd.toString()))))).value();
        await(()->executions.run(run.id(),false).orElseThrow().state().equals("SUCCEEDED"),40,"Sensor → VD → VD → AI execution failed");
        var tasks=executions.tasks(run.id());var sensorTask=tasks.stream().filter(t->t.key().equals("sensor")).findFirst().orElseThrow();
        var frame=(Map<?,?>)json.decode(executions.vdInput(sensorTask.id()).orElseThrow());
        var reading=(Map<?,?>)((Map<?,?>)((Map<?,?>)((Map<?,?>)frame.get("sources")).get("input")).get("properties")).get("temp_humidity");
        double temperature=((Number)reading.get("value")).doubleValue();
        for(var task:tasks) {
            var result=runtimes.result(task.id()).orElseThrow();var runtime=runtimes.runtime(result.runtimeId()).orElseThrow();
            verifyStart(runtime);verifyResult(runtime);assertThat(executions.attempts(task.id())).hasSize(1);
            if(task.key().equals("inference")) {
                var artifact=result.outputs().getFirst().artifact();
                var response=client.send(HttpRequest.newBuilder(storage.download(artifact).url()).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);var output=(Map<?,?>)json.decode(response.body());
                assertThat(output.get("sourceMode")).isEqualTo("VD");assertThat(((Number)output.get("score")).doubleValue()).isCloseTo(Math.abs(temperature)*2+1,within(0.000001));
                System.out.println("VD_SENSOR_ACCEPTANCE "+encode(Map.of("path","sensor→processingVD→derivedVD→referenceAI→Result","temperature",temperature,"score",output.get("score"),"runState","SUCCEEDED","sha256",artifact.sha256(),"podBoundary","fixture")));
            }
        }
        for(var runtime:launched.values())vdLifecycle.drain(runtime.vdId(),0,"chain-drain");
        for(var child:processes){assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();}
        verifyNoInteractions(jobs);
    }
    private void verifyResult(RuntimeInstance runtime)throws Exception {
        String key="authority/vd-task-result/"+runtime.id()+".json";var versions=new ArrayList<String>();
        for(var value:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(key).includeVersions(true).build()))versions.add(value.get().versionId());
        assertThat(versions).hasSize(1);var stat=admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
        assertThat(stat.versionId()).isEqualTo(versions.getFirst());assertThat(stat.contentType()).isEqualTo("application/vnd.edgeai.vd-task-result+json");
        var result=runtimes.result(runtime.taskId()).orElseThrow();var allocation=allocations.byRuntime(runtime.id()).orElseThrow();
        try(var input=admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).versionId(stat.versionId()).build())){
            var document=(Map<?,?>)json.decode(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertThat(document.get("apiVersion")).isEqualTo("edgeai.vd.task.result/v1");
            assertThat(document.get("resultId")).isEqualTo(result.id().toString());assertThat(document.get("committedAt")).isEqualTo(result.createdAt().toString());
            assertThat(document.get("allocationId")).isEqualTo(allocation.id().toString());assertThat(document.get("sessionId")).isEqualTo(allocation.sessionId().toString());
            assertThat(document.get("vdRuntimeId")).isEqualTo(allocation.vdRuntimeId().toString());assertThat(document.get("podUid")).isEqualTo(runtime.producerPodUid().toString());
            assertThat(document.get("manifestDigest")).isEqualTo(result.manifestDigest());
            var expected=result.outputs().stream().sorted(Comparator.comparing(io.edgeai.domain.storage.TaskResult.Output::port)).map(o->{var a=o.artifact();
                return Map.of("port",o.port(),"bucket",a.bucket(),"objectKey",a.objectKey(),"versionId",a.versionId(),"bytes",a.bytes(),"sha256",a.sha256(),"mediaType",a.mediaType());}).toList();
            assertThat(json.canonical(document.get("outputs"))).isEqualTo(json.canonical(expected));
        }
    }
    private void verifyStart(RuntimeInstance runtime)throws Exception {
        String key="authority/vd-task-start/"+runtime.id()+".json";var versions=new ArrayList<String>();
        for(var value:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(key).includeVersions(true).build()))versions.add(value.get().versionId());
        assertThat(versions).hasSize(1);var stat=admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
        assertThat(stat.versionId()).isEqualTo(versions.getFirst());assertThat(stat.contentType()).isEqualTo("application/vnd.edgeai.vd-task-start+json");
        var allocation=allocations.byRuntime(runtime.id()).orElseThrow();
        try(var input=admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).versionId(stat.versionId()).build())){
            var document=(Map<?,?>)json.decode(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertThat(document.get("runtimeId")).isEqualTo(runtime.id().toString());assertThat(document.get("attemptId")).isEqualTo(runtime.attemptId().toString());
            assertThat(document.get("allocationId")).isEqualTo(allocation.id().toString());assertThat(document.get("sessionId")).isEqualTo(allocation.sessionId().toString());
            assertThat(document.get("vdRuntimeId")).isEqualTo(allocation.vdRuntimeId().toString());assertThat(document.get("podUid")).isEqualTo(runtime.producerPodUid().toString());
            assertThat(Instant.parse((String)document.get("admittedAt"))).isBefore(runtime.expiresAt()).isBefore(Instant.parse((String)document.get("supervisorLeaseUntil")));
        }
    }
}
