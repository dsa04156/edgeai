package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.stream.*;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public Run MVC, actual Spring/MinIO over TLS, PG, TLS broker, SDK, model and Runner finalizer.
 * Pod provisioning/identity and peer-triggered route closure are fixtures. Public execution is explicitly enabled in this test. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.stream.enabled=true",
    "edgeai.stream.bindings-enabled=true","edgeai.stream.runs-enabled=true","edgeai.stream.reconcile-ms=50"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StreamSourceCompletionIntegrationTest {
    private static final StreamBrokerFixture BROKER=new StreamBrokerFixture();
    private static final StreamTlsProxy STORAGE_TLS=new StreamTlsProxy(BROKER,required("EDGEAI_STORAGE_URL"));
    private static final String DIGEST="sha256:"+"c".repeat(64);
    private static final String BUCKET="edgeai-source-"+UUID.randomUUID();
    private static final MinioClient MINIO=storage();
    private static StreamAuthorityWorker runningWorker;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("edgeai.runtime.namespace",()->BUCKET);p.add("edgeai.runtime.key-file",()->BROKER.file("runner.key"));
        p.add("edgeai.stream.broker-url",()->"ssl://localhost:"+BROKER.port);p.add("edgeai.stream.broker-digest",()->DIGEST);
        p.add("edgeai.stream.ca-file",()->BROKER.file("server.crt"));p.add("edgeai.stream.admin-password-file",()->BROKER.file("admin.password"));
        p.add("edgeai.stream.principal-key-file",()->BROKER.file("principal.key"));p.add("edgeai.stream.device-key-file",()->BROKER.file("device.key"));
        p.add("edgeai.storage.endpoint",()->required("EDGEAI_STORAGE_URL"));p.add("edgeai.storage.runner-endpoint",()->STORAGE_TLS.origin);
        p.add("edgeai.storage.access-key",()->required("EDGEAI_MINIO_USER"));p.add("edgeai.storage.secret-key",()->required("EDGEAI_MINIO_PASSWORD"));
        p.add("edgeai.storage.bucket",()->BUCKET);
    }
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired DeviceService devices;
    @Autowired ExecutionService runs;@Autowired ExecutionRepository executions;
    @Autowired RuntimeRepository runtimes;@Autowired RuntimeLifecycleService lifecycle;@Autowired RunnerTokenService tokens;
    @Autowired DeviceStreamTokenService deviceTokens;@Autowired DataRouteService routes;@Autowired DataRouteRepository routeStore;
    @Autowired StreamCheckpointRepository checkpoints;@Autowired StreamExecutionRepository completions;
    @Autowired StreamAuthorityWorker worker;@Autowired MockMvc mvc;
    @MockitoBean RuntimeGateway gateway;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private final JsonDocuments json=new JsonDocuments();
    private StreamTlsProxy apiTls;
    private final Map<UUID,RuntimePod> pods=new ConcurrentHashMap<>();private final List<UUID> runIds=new ArrayList<>();
    private record Execution(UUID run,UUID task,UUID attempt,List<UUID> generations,Path folder){}
    @BeforeEach void setup(){apiTls=new StreamTlsProxy(BROKER,"http://127.0.0.1:"+port);runningWorker=worker;when(gateway.authenticatePod(any(),any())).thenAnswer(c->{
        RuntimeInstance r=c.getArgument(0);if(!"source-pod-proof".equals(c.getArgument(1)) || !pods.containsKey(r.attemptId()))
            throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);return pods.get(r.attemptId());});}
    @AfterEach void cleanup()throws Exception{try{for(var id:runIds){
        if(!Set.of("SUCCEEDED","FAILED","CANCELLED").contains(executions.run(id,false).orElseThrow().state()))runs.cancelRun(id,"{}");}
        until(()->runIds.stream().flatMap(id->routeStore.forRun(id,100,0).stream()).noneMatch(r->routeStore.open(r.id()).isPresent()));
        }finally{if(apiTls!=null)apiTls.close();}}
    @AfterAll static void stop()throws Exception{
        try{if(runningWorker!=null)runningWorker.close();
            var versions=new ArrayList<Map.Entry<String,String>>();
            for(var result:MINIO.listObjects(ListObjectsArgs.builder().bucket(BUCKET).includeVersions(true).recursive(true).build())){
                var value=result.get();assertThat(value.versionId()).isNotBlank();versions.add(Map.entry(value.objectName(),value.versionId()));}
            for(var value:versions)MINIO.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(value.getKey()).versionId(value.getValue()).build());
            assertThat(MINIO.listObjects(ListObjectsArgs.builder().bucket(BUCKET).includeVersions(true).recursive(true).build()).iterator().hasNext()).isFalse();
            MINIO.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
        }finally{MINIO.close();try{STORAGE_TLS.close();}finally{BROKER.close();}}
    }
    private static String required(String name){return Objects.requireNonNull(System.getenv(name),name+" is required");}
    private static MinioClient storage(){try{
        var client=MinioClient.builder().endpoint(required("EDGEAI_STORAGE_URL")).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        client.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());
        return client;
    }catch(Exception e){throw new IllegalStateException("Real source completion storage unavailable; private details suppressed");}}
    private void secret(Path folder,String name,String value)throws Exception{var file=folder.resolve(name);Files.writeString(file,value);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}
    @SuppressWarnings("unchecked") private Execution fixture(String mode)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));
        spec.put("command",List.of(System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3"),Path.of("../../runner/examples/stream_result.py").toAbsolutePath().normalize().toString()));
        var stream=(Map<String,Object>)spec.get("stream");stream.put("outputs",Map.of());
        stream.put("command",List.of(System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3"),Path.of("../../runner/examples/stream_sum.py").toAbsolutePath().normalize().toString()));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Actual source completion"))).value();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",List.of(Map.of("key","sum","serviceProfileVersionId",profile.id().toString(),"parameters",Map.of("mode","zip"))),"dependencies",List.of()))).value();
        var folder=Files.createDirectory(BROKER.root.resolve("probe-"+UUID.randomUUID()));Files.setPosixFilePermissions(folder,PosixFilePermissions.fromString("rwx------"));
        var sessions=new TreeMap<String,io.edgeai.domain.device.DeviceSession>();var inputs=new ArrayList<Object>();
        for(String name:List.of("a","b")){
            var dp=profiles.publish(ProfileIdentity.Kind.DEVICE,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",Map.of("protocol","mqtt")))).version();
            var d=devices.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Real TLS source","profileVersionId",dp.id().toString(),"sourceMode","SYNTHETIC"))).value();
            var session=devices.openSession(d.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
            sessions.put(name,session);secret(folder,name+".token",deviceTokens.issue(session));
            inputs.add(Map.of("deviceId",d.id().toString(),"sourcePort","samples","toTask","sum","toPort",name,"maxPayloadBytes",4096));
        }
        var response=mvc.perform(post("/api/v1/workflow-runs").with(user("test")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(json.canonical(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of(),"streamInputs",inputs))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var run=executions.run(UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id")),false).orElseThrow();runIds.add(run.id());
        var task=executions.tasks(run.id()).getFirst();var attempt=executions.attempts(task.id()).getFirst();
        assertThat(routeStore.forRun(run.id(),20,0)).hasSize(2).allMatch(r->routeStore.open(r.id()).isEmpty());
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");pods.put(attempt.id(),pod);
        lifecycle.submitted(attempt.id(),pod.jobUid());lifecycle.claim(attempt.id(),1,pod);
        secret(folder,"claim",tokens.issue(runtimes.byAttempt(attempt.id()).orElseThrow()));secret(folder,"pod","source-pod-proof");
        // The actual scheduled Run worker prepares generations; the authority worker activates them in the TLS broker.
        until(()->routeStore.forRun(run.id(),20,0).stream().allMatch(r->routeStore.open(r.id()).filter(g->g.state().equals("ACTIVE")).isPresent()));
        var sources=new TreeMap<String,Object>();var ids=new ArrayList<UUID>();
        for(var route:routeStore.forRun(run.id(),20,0)){
            var session=sessions.get(route.consumerPort());var generation=routeStore.open(route.id()).orElseThrow();ids.add(generation.id());
            sources.put(route.consumerPort(),Map.of("deviceId",session.deviceId().toString(),"sessionId",session.id().toString(),"epoch",session.epoch(),"generationId",generation.id().toString(),"routeId",route.id().toString()));
        }
        secret(folder,"request.json",json.canonical(Map.of("origin",apiTls.origin,"runId",run.id().toString(),"attemptId",attempt.id().toString(),"podUid",pod.podUid().toString(),"sources",sources,"stream",stream,"mode",mode)));
        return new Execution(run.id(),task.id(),attempt.id(),ids,folder);
    }
    private void probe(Execution e,boolean cancel)throws Exception{
        var builder=new ProcessBuilder(System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3"),"-W","error::ResourceWarning","src/test/fixtures/stream_source_completion_probe.py",e.folder().toString())
            .redirectOutput(e.folder().resolve("probe.log").toFile()).redirectError(e.folder().resolve("probe-error.log").toFile());
        builder.environment().put("SSL_CERT_FILE",BROKER.file("server.crt"));var child=builder.start();
        try{
            until(()->Files.exists(e.folder().resolve("waiting")) || !child.isAlive());
            assertThat(Files.exists(e.folder().resolve("waiting"))).as("SDK terminal WAITING: %s",Files.readString(e.folder().resolve("probe.log"))).isTrue();
            var checkpoint=checkpoints.latest(e.task()).orElseThrow();assertThat(checkpoint.artifact().bucket()).isEqualTo(BUCKET);
            assertThat(completions.task(e.attempt())).isEmpty();
            for(var id:e.generations())assertThat(completions.device(id).orElseThrow().grantedAt()).isNull();
            if(cancel)runs.cancelRun(e.run(),"{}");
            Files.writeString(e.folder().resolve("continue"),"");
            if(!cancel){
                until(()->Files.exists(e.folder().resolve("granted")) || !child.isAlive());
                assertThat(Files.exists(e.folder().resolve("granted"))).as("SDK grant: %s",Files.readString(e.folder().resolve("probe.log"))).isTrue();
                // Model a peer closing the completed component; the actual worker revokes broker authority.
                for(var id:e.generations())routes.fence(id,"COMPLETED");
                until(()->e.generations().stream().allMatch(id->routeStore.generation(id).orElseThrow().closedAt()!=null));
                Files.writeString(e.folder().resolve("routes-closed"),"");
                until(()->Files.exists(e.folder().resolve("committed")) || !child.isAlive());
                assertThat(Files.exists(e.folder().resolve("committed"))).as("Runner Result: %s",Files.readString(e.folder().resolve("probe.log"))).isTrue();
            }
            assertThat(child.waitFor(45,TimeUnit.SECONDS)).as("Actual SDK completion probe finished").isTrue();
            assertThat(child.exitValue()).as("SDK completion probe: %s",Files.readString(e.folder().resolve("probe.log"))).isZero();
            assertThat(Files.readString(e.folder().resolve("probe.log"))).isEqualTo("STREAM_SOURCE_COMPLETION_"+(cancel?"CANCELLED":"PASS")+"\n");
            assertThat(Files.size(e.folder().resolve("probe-error.log"))).isZero();
            assertThat(executions.run(e.run(),false).orElseThrow().state()).isEqualTo(cancel?"CANCELLING":"SUCCEEDED");
            if(cancel){assertThat(completions.task(e.attempt())).isEmpty();for(var id:e.generations())assertThat(completions.device(id).orElseThrow().grantedAt()).isNull();}
            else {var grant=completions.task(e.attempt()).orElseThrow();assertThat(grant.grantedAt()).isNotNull();
                assertThat(grant.checkpointId()).isEqualTo(checkpoint.id());
                for(var id:e.generations())assertThat(completions.device(id).orElseThrow().grantedAt()).isEqualTo(grant.grantedAt());}
        }finally{if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
    }
    @Test void realSourcesAndRunnerRecoverGrantedCheckpointAfterRouteClosureAndCommitActualResult()throws Exception{probe(fixture("complete"),false);}
    @Test void actualCancellationBetweenTerminalCheckpointAndTaskReportNeverGrantsSources()throws Exception{probe(fixture("cancel"),true);}
    private void until(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(20);assertThat(condition.getAsBoolean()).as("Actual stream completion boundary reached").isTrue();}
}
