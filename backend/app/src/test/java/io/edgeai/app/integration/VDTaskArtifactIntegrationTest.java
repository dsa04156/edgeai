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
    @MockitoBean VDGateway gateway;
    @MockitoBean RuntimeGateway jobs;
    @LocalServerPort int port;
    @TempDir Path directory;
    private final JsonDocuments json=new JsonDocuments();
    private MinioClient admin;
    private Process process;
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
            if(process!=null && process.isAlive()){process.destroy();if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
            if(supervisor!=null && !vdLifecycle.get(supervisor.id()).desiredState().equals("STOPPED"))vdLifecycle.fail(supervisor.id(),"RUNTIME_LOST");
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
    @SuppressWarnings("unchecked") private UUID fixture(boolean loseReplies)throws Exception {
        URI origin=proxy(loseReplies);var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("command",List.of("python3",Path.of("../../runner/examples/linear.py").toAbsolutePath().normalize().toString()));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        UUID sp=profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key","vd-artifact-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
        UUID vp=profiles.publish(ProfileIdentity.Kind.VD,encode(Map.of("key","vd-artifact-"+UUID.randomUUID(),"version","1.0.0","spec",Map.of("apiVersion","edgeai.vd/v1","type","emulation","serviceProfileVersionId",sp.toString(),"sources",Map.of(),"state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",2,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30))))).version().id();
        var vd=devices.create(encode(Map.of("key","vd-artifact-"+UUID.randomUUID(),"displayName","Actual VD child Runner","profileVersionId",vp.toString(),"sources",List.of(),"placement",Map.of("mode","AUTO")))).value();vdId=vd.id();
        var op=vdLifecycle.provision(vd.id(),0,"provision",new RuntimeSettings("vd-artifact-test","edgeai-runner",origin,120),false);
        supervisor=vdLifecycle.submitted(op.targetRuntimeId(),UUID.randomUUID());UUID node=UUID.randomUUID();work=directory.resolve("work");Files.createDirectory(work);
        when(gateway.authenticatePod(any(),any())).thenAnswer(call->{VDRuntime r=call.getArgument(0);if(!"pod-proof-fixture".equals(call.getArgument(1)) || !r.id().equals(supervisor.id()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return new VDGateway.PodIdentity(supervisor.podUid(),node,"fixture-node",Files.exists(work.resolve(".vd-ready")));});
        Path token=directory.resolve("claim"),proof=directory.resolve("proof");Files.writeString(token,vdTokens.issue(supervisor));Files.writeString(proof,"pod-proof-fixture");
        Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));Files.setPosixFilePermissions(proof,PosixFilePermissions.fromString("rw-------"));
        var command=new ProcessBuilder("python3",Path.of("../../runner/vd.py").toAbsolutePath().normalize().toString());var env=command.environment();env.keySet().removeIf(k->k.startsWith("EDGEAI_"));
        env.putAll(Map.of("EDGEAI_VD_ID",vd.id().toString(),"EDGEAI_VD_RUNTIME_ID",supervisor.id().toString(),"EDGEAI_VD_GENERATION","1","EDGEAI_POD_UID",supervisor.podUid().toString(),"EDGEAI_CONTROL_PLANE_URL",origin.toString(),"EDGEAI_VD_CLAIM_FILE",token.toString(),"EDGEAI_POD_TOKEN_FILE",proof.toString(),"EDGEAI_WORK_DIR",work.toString(),"EDGEAI_VD_MAX_CONCURRENT_TASKS","2","EDGEAI_VD_STARTUP_SECONDS","60"));
        env.put("EDGEAI_VD_DRAIN_SECONDS","30");env.put("PYTHONDONTWRITEBYTECODE","1");Path log=directory.resolve("supervisor.log");Files.createFile(log,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        process=command.redirectOutput(log.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
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
            verifyStart(r);
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
        verifyStart(runtimes.runtime(runtimes.result(fast.id()).orElseThrow().runtimeId()).orElseThrow());drain();verifyNoInteractions(jobs);
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
            assertThat(document.get("vdRuntimeId")).isEqualTo(supervisor.id().toString());assertThat(document.get("podUid")).isEqualTo(supervisor.podUid().toString());
            assertThat(Instant.parse((String)document.get("admittedAt"))).isBefore(runtime.expiresAt()).isBefore(Instant.parse((String)document.get("supervisorLeaseUntil")));
        }
    }
}
