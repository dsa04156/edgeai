package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.vd.*;
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

/** Public Run MVC, actual Spring/MinIO over TLS, PG, TLS broker, SDK and independent Runners.
 * Pod provisioning/identity are fixtures; the recovery tests additionally model peer-triggered closure. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.stream.enabled=true","edgeai.vd.enabled=true","edgeai.vd.lease-seconds=60",
    "edgeai.stream.bindings-enabled=true","edgeai.stream.runs-enabled=true","edgeai.stream.reconcile-ms=50"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
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
    @Autowired StreamRunService streamRuns;
    @Autowired OffloadService offloads;@Autowired NodeService nodes;@Autowired NodeRepository nodeStore;
    @Autowired OffloadRepository offloadStore;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean RuntimeGateway gateway;
    @MockitoBean VDGateway vdGateway;
    @Autowired VirtualDeviceService virtualDevices;@Autowired VDLifecycleService vdLifecycle;@Autowired VDTokenService vdTokens;
    @Autowired VDTaskRepository vdAllocations;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private final JsonDocuments json=new JsonDocuments();
    private StreamTlsProxy apiTls;
    private final Map<UUID,RuntimePod> pods=new ConcurrentHashMap<>();private final List<UUID> runIds=new ArrayList<>();
    private record Supervisor(VDRuntime runtime,UUID node,Path folder,Process process){}
    private final Map<UUID,Supervisor> supervisors=new ConcurrentHashMap<>();
    private record Execution(UUID run,UUID task,UUID attempt,List<UUID> generations,Path folder){}
    private record DagExecution(UUID run,Map<String,UUID> tasks,Path folder){}
    @BeforeEach void setup(){apiTls=new StreamTlsProxy(BROKER,"http://127.0.0.1:"+port);runningWorker=worker;when(gateway.authenticatePod(any(),any())).thenAnswer(c->{
        RuntimeInstance r=c.getArgument(0);if(!"source-pod-proof".equals(c.getArgument(1)) || !pods.containsKey(r.attemptId()))
            throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);return pods.get(r.attemptId());});
        when(vdGateway.authenticatePod(any(),any())).thenAnswer(c->{
            VDRuntime r=c.getArgument(0);var s=supervisors.get(r.id());
            if(!"source-vd-pod-proof".equals(c.getArgument(1)) || s==null)throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return new VDGateway.PodIdentity(s.runtime().podUid(),s.node(),"vd-fixture-node",Files.exists(s.folder().resolve("work/.vd-ready")));
        });}
    @AfterEach void cleanup()throws Exception{try{for(var id:runIds){
        if(!Set.of("SUCCEEDED","FAILED","CANCELLED").contains(executions.run(id,false).orElseThrow().state()))runs.cancelRun(id,"{}");}
        until(()->runIds.stream().flatMap(id->routeStore.forRun(id,100,0).stream()).noneMatch(r->routeStore.open(r.id()).isPresent()));
        }finally{
            try{for(var s:supervisors.values())if(s.process()!=null && s.process().isAlive()){
                s.process().destroy();if(!s.process().waitFor(8,TimeUnit.SECONDS)){s.process().descendants().forEach(ProcessHandle::destroyForcibly);s.process().destroyForcibly();assertThat(s.process().waitFor(5,TimeUnit.SECONDS)).isTrue();}
            }}finally{if(apiTls!=null)apiTls.close();}}}
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
    private WorkflowRun publicRun(UUID version,List<Object> inputs,boolean retry)throws Exception{
        return publicRun(version,inputs,retry,false);
    }
    private WorkflowRun publicRun(UUID version,List<Object> inputs,boolean retry,boolean automatic)throws Exception{
        return publicRun(version,inputs,retry,automatic,Map.of("mode","AUTO"),Map.of());
    }
    private WorkflowRun publicRun(UUID version,List<Object> inputs,boolean retry,boolean automatic,Map<String,Object> execution,Map<String,Object> placements)throws Exception{
        var body=new TreeMap<String,Object>(Map.of("workflowVersionId",version.toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of(),"streamInputs",inputs));
        body.put("execution",execution);if(!placements.isEmpty())body.put("taskExecutions",placements);
        if(retry)body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        if(automatic){var policy=new TreeMap<String,Object>(Map.of("latencyMicros",1000,"consecutiveSamples",2,"maxSampleAgeSeconds",30,
            "maxGapSeconds",10,"minRunningSeconds",10,"cooldownSeconds",20,"maxTransfers",1,"drainTimeoutSeconds",60,"startTimeoutSeconds",60));
            policy.put("cpuPercent",null);policy.put("memoryPercent",null);body.put("offload",policy);}
        var response=mvc.perform(post("/api/v1/workflow-runs").with(user("test")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(json.canonical(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return executions.run(UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id")),false).orElseThrow();
    }
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
        var run=publicRun(version.id(),inputs,mode.equals("finalizer-retry"));
        runIds.add(run.id());
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
        boolean recovery=Files.readString(e.folder().resolve("request.json")).contains("finalizer-retry");
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
                if(recovery){
                    // The Python owner has closed all Session/Device producers before reporting granted.
                    lifecycle.observeFailure(e.attempt(),"RUNTIME_LOST");
                    assertThat(lifecycle.retryTask(e.task())).isFalse();
                    var old=runtimes.byAttempt(e.attempt()).orElseThrow();
                    jdbc.update("UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=? AND kind='CREATE'",old.id());
                    lifecycle.confirmStopped(e.attempt());
                    until(()->e.generations().stream().allMatch(id->routeStore.generation(id).orElseThrow().closedAt()!=null));
                    until(()->{lifecycle.retryTask(e.task());return executions.attempts(e.task()).getFirst().epoch()==2;});
                    var next=executions.attempts(e.task()).getFirst();assertThat(next.epoch()).isEqualTo(2);
                    var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");pods.put(next.id(),pod);
                    lifecycle.submitted(next.id(),pod.jobUid());lifecycle.claim(next.id(),next.epoch(),pod);
                    assertThat(streamRuns.prepare(e.run())).isEmpty();
                    var folder=Files.createDirectory(e.folder().resolve("successor"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    secret(folder,"claim",tokens.issue(runtimes.byAttempt(next.id()).orElseThrow()));secret(folder,"pod","source-pod-proof");
                    secret(folder,"identity.json",json.canonical(Map.of("attemptId",next.id().toString(),"epoch",next.epoch(),"podUid",pod.podUid().toString())));
                }else{
                    // Model a peer closing the completed component; the actual worker revokes broker authority.
                    for(var id:e.generations())routes.fence(id,"COMPLETED");
                    until(()->e.generations().stream().allMatch(id->routeStore.generation(id).orElseThrow().closedAt()!=null));
                }
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
                for(var id:e.generations())assertThat(completions.device(id).orElseThrow().grantedAt()).isEqualTo(grant.grantedAt());
                if(recovery){
                    var next=executions.attempts(e.task()).getFirst();var result=runtimes.result(e.task()).orElseThrow();
                    assertThat(result.attemptId()).isEqualTo(next.id());assertThat(result.epoch()).isEqualTo(2);
                    assertThat(completions.task(next.id())).isEmpty();assertThat(completions.granted(next.id()).orElseThrow()).isEqualTo(grant);
                    assertThat(checkpoints.latest(e.task()).orElseThrow().id()).isEqualTo(checkpoint.id());
                    assertThat(routeStore.forRun(e.run(),20,0)).allMatch(r->routeStore.history(r.id(),20,0).size()==1);
                    var output=result.outputs().getFirst().artifact();
                    try(var content=MINIO.getObject(GetObjectArgs.builder().bucket(output.bucket()).object(output.objectKey()).versionId(output.versionId()).build())){
                        assertThat(json.canonical(json.decode(new String(content.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)))).isEqualTo("{\"sum\":14}");
                    }
                }
            }
        }finally{if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
    }
    @Test void realSourcesAndRunnerRecoverGrantedCheckpointAfterRouteClosureAndCommitActualResult()throws Exception{probe(fixture("complete"),false);}
    @Test void newAttemptFinalizerInheritsRealGrantedS3CheckpointAndCommitsWithoutNewGenerations()throws Exception{probe(fixture("finalizer-retry"),false);}
    @Test void actualCancellationBetweenTerminalCheckpointAndTaskReportNeverGrantsSources()throws Exception{probe(fixture("cancel"),true);}
    @SuppressWarnings("unchecked") private UUID dagService(String name)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/"+(name.equals("report")?"service-execution":"service-stream")+".example.json")));
        String python=System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3");
        spec.put("command",List.of(python,Path.of("../../runner/examples/"+(name.equals("report")?"stream_report":"stream_result")+".py").toAbsolutePath().normalize().toString()));
        if(name.equals("report")){
            spec.put("recovery",Map.of("mode","RESTART"));
            var input=Map.of("mediaType","application/json","maxBytes",1048576,"required",true);
            spec.put("inputs",Map.of("root",input,"sink",input));
            spec.put("outputs",Map.of("report",Map.of("mediaType","application/json","maxBytes",1048576)));
        }else{
            var stream=(Map<String,Object>)spec.get("stream");
            stream.put("command",List.of(python,Path.of("../../runner/examples/stream_sum.py").toAbsolutePath().normalize().toString()));
            if(name.equals("sink")){
                stream.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxPayloadBytes",262144)));
                stream.put("outputs",Map.of());
            }
            if(name.equals("fanout"))stream.put("outputs",Map.of());
        }
        return profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
    }
    private Map<String,Object> edge(String from,String to,String output,String input,String mode){
        return Map.of("fromTask",from,"toTask",to,"fromPort",output,"toPort",input,"mode",mode);
    }
    private DagExecution dagFixture(boolean cancel,boolean recovery,boolean automatic)throws Exception{
        var folder=Files.createDirectory(BROKER.root.resolve("dag-"+UUID.randomUUID()));Files.setPosixFilePermissions(folder,PosixFilePermissions.fromString("rwx------"));
        var definitions=new ArrayList<Object>();
        for(String name:List.of("root","sink","report"))definitions.add(Map.of("key",name,"serviceProfileVersionId",dagService(name).toString(),
            "parameters",name.equals("root")?Map.of("mode","zip"):Map.of()));
        var workflow=workflows.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Actual multi-runner stream DAG"))).value();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",definitions,"dependencies",List.of(
            edge("root","sink","sum","input","STREAM"),edge("root","report","result","root","BATCH"),edge("sink","report","result","sink","BATCH"))))).value();
        var sources=new TreeMap<String,Object>();var inputs=new ArrayList<Object>();
        for(String name:List.of("a","b")){
            var profile=profiles.publish(ProfileIdentity.Kind.DEVICE,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",Map.of("protocol","mqtt")))).version();
            var device=devices.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Synthetic DAG source","profileVersionId",profile.id().toString(),"sourceMode","SYNTHETIC"))).value();
            var session=devices.openSession(device.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
            secret(folder,name+".token",deviceTokens.issue(session));
            sources.put(name,Map.of("deviceId",device.id().toString(),"sessionId",session.id().toString(),"epoch",session.epoch()));
            inputs.add(Map.of("deviceId",device.id().toString(),"sourcePort","samples","toTask","root","toPort",name,"maxPayloadBytes",4096));
        }
        UUID id=publicRun(version.id(),inputs,recovery,automatic).id();
        runIds.add(id);
        var tasks=new TreeMap<String,UUID>();executions.tasks(id).forEach(t->tasks.put(t.key(),t.id()));
        assertThat(executions.attempts(tasks.get("report"))).isEmpty();
        secret(folder,"request.json",json.canonical(Map.of("origin",apiTls.origin,"runId",id.toString(),"mode",recovery?"recover":cancel?"cancel":"complete","sources",sources,"automatic",automatic)));
        return new DagExecution(id,tasks,folder);
    }
    private void publish(Path folder,String name,Object value)throws Exception{
        secret(folder,name+".tmp",json.canonical(value));Files.move(folder.resolve(name+".tmp"),folder.resolve(name),StandardCopyOption.ATOMIC_MOVE);
    }
    private Path taskDirectory(DagExecution e,String name){
        var history=executions.attempts(e.tasks().get(name));int number=history.isEmpty()?1:history.getFirst().number();
        return e.folder().resolve(number==1?name:name+"-"+number);
    }
    private void provision(DagExecution e)throws Exception{
        for(var entry:e.tasks().entrySet()){
            var attempts=executions.attempts(entry.getValue());if(attempts.isEmpty())continue;
            var attempt=attempts.getFirst();var folder=taskDirectory(e,entry.getKey());if(Files.exists(folder.resolve("launch.json")))continue;
            assertThat(attempt.state()).isEqualTo("DISPATCHING");
            if(entry.getKey().equals("report"))for(String parent:List.of("root","sink"))assertThat(runtimes.result(e.tasks().get(parent))).isPresent();
            Files.createDirectory(folder);Files.setPosixFilePermissions(folder,PosixFilePermissions.fromString("rwx------"));
            var node=attempt.nodeId()==null?null:nodeStore.find(attempt.nodeId()).orElseThrow();
            // Explicit scheduler fixture; the Runner's HTTPS claim/telemetry/checkpoints are real.
            if(!attempt.excludedNodeNames().isEmpty())node=nodeStore.list(1000,0).stream()
                .filter(n->n.status(Instant.now()).equals("READY") && !attempt.excludedNodeNames().contains(n.name())).findFirst().orElseThrow();
            var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),node==null?UUID.randomUUID():node.id(),node==null?"fixture-node":node.name());pods.put(attempt.id(),pod);
            lifecycle.submitted(attempt.id(),pod.jobUid()); // The actual Runner performs the HTTP claim.
            secret(folder,"claim",tokens.issue(runtimes.byAttempt(attempt.id()).orElseThrow()));secret(folder,"pod","source-pod-proof");
            publish(folder,"launch.json",Map.of("attemptId",attempt.id().toString(),"epoch",attempt.epoch(),"podUid",pod.podUid().toString()));
        }
        int round=executions.attempts(e.tasks().get("root")).getFirst().number();String routing=round==1?"routing.json":"routing-"+round+".json";
        if(Files.exists(e.folder().resolve(routing)))return;
        var records=routeStore.forRun(e.run(),20,0);assertThat(records).hasSize(3);
        if(records.stream().anyMatch(r->routeStore.open(r.id()).filter(g->g.state().equals("ACTIVE")).isEmpty()))return;
        var sources=new TreeMap<String,Object>();var taskRoutes=new TreeMap<String,List<String>>();taskRoutes.put("root",new ArrayList<>());taskRoutes.put("sink",new ArrayList<>());
        for(var r:records){
            var g=routeStore.open(r.id()).orElseThrow();
            if(r.deviceSource())sources.put(r.consumerPort(),Map.of("generationId",g.id().toString(),"routeId",r.id().toString()));
            for(String name:taskRoutes.keySet())if(e.tasks().get(name).equals(r.consumerTaskId()) || e.tasks().get(name).equals(r.sourceTaskId()))taskRoutes.get(name).add(g.id().toString());
        }
        publish(e.folder(),routing,Map.of("sources",sources,"tasks",taskRoutes));
    }
    private void dagProbe(boolean cancel)throws Exception{dagProbe(cancel,false);}
    private void dagProbe(boolean cancel,boolean recovery)throws Exception{
        dagProbe(cancel,recovery,false);
    }
    private void dagProbe(boolean cancel,boolean recovery,boolean offload)throws Exception{
        dagProbe(cancel,recovery,offload,false);
    }
    private void dagProbe(boolean cancel,boolean recovery,boolean offload,boolean automatic)throws Exception{
        var e=dagFixture(cancel,recovery,automatic);provision(e);
        var builder=new ProcessBuilder(System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3"),"-W","error::ResourceWarning","src/test/fixtures/stream_dag_probe.py",e.folder().toString())
            .redirectOutput(e.folder().resolve("probe.log").toFile()).redirectError(e.folder().resolve("probe-error.log").toFile());
        builder.environment().put("SSL_CERT_FILE",BROKER.file("server.crt"));var child=builder.start();
        try{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);boolean cancelled=false,failed=false,stopped=false,retried=false,restored=false;
            var oldCheckpoints=new HashMap<String,StreamCheckpoint>();
            UUID operation=null,target=null;
            while(child.isAlive() && System.nanoTime()<end){
                provision(e);
                if(recovery && !failed && Files.exists(e.folder().resolve("recovery-request"))){
                    if(offload){
                        if(target==null){target=UUID.randomUUID();nodes.recordSnapshot(List.of(new io.edgeai.domain.node.ExecutionNode(target,"transfer-"+target,
                            "amd64","linux","READY","4","4Gi","{}",Instant.now())),Instant.now());}
                        if(automatic){
                            var decision=offloads.evaluate(e.tasks().get("root"),BUCKET);
                            if(decision.isEmpty()){Thread.sleep(20);continue;}
                            operation=decision.get().id();assertThat(decision.get().trigger()).isEqualTo("LATENCY");
                            assertThat(decision.get().excludedNodeNames()).containsExactly("fixture-node");
                            assertThat(decision.get().decisionJson()).contains("1200","WORKLOAD","eligibleSince");
                        }else{
                        var a=executions.attempts(e.tasks().get("root")).getFirst();
                        var response=mvc.perform(post("/api/v1/tasks/"+e.tasks().get("root")+"/offload").with(user("test")).with(csrf())
                            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                            .content(json.canonical(Map.of("sourceAttemptId",a.id().toString(),"targetNodeId",target.toString(),"drainTimeoutSeconds",60,"startTimeoutSeconds",60))))
                            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
                        operation=UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id"));
                        }
                    }else lifecycle.observeFailure(executions.attempts(e.tasks().get("sink")).getFirst().id(),"RUNTIME_LOST");
                    failed=true;
                    // The Run fence has committed: a concurrent old publisher can no longer advance this snapshot.
                    for(String name:List.of("root","sink"))oldCheckpoints.put(name,checkpoints.latest(e.tasks().get(name)).orElseThrow());
                    for(String name:oldCheckpoints.keySet())assertThat(executions.task(e.tasks().get(name)).orElseThrow().state()).isEqualTo(offload?"OFFLOADING":"RETRY_WAIT");
                }
                if(recovery && failed && !stopped && Files.exists(e.folder().resolve("producers-stopped"))){
                    for(String name:oldCheckpoints.keySet()){
                        var previous=oldCheckpoints.get(name);var r=runtimes.byAttempt(previous.attemptId()).orElseThrow();
                        jdbc.update("UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=? AND kind='CREATE'",r.id());
                        lifecycle.confirmStopped(previous.attemptId());
                    }
                    stopped=true;
                }
                if(stopped && !retried){
                    if(offload){offloads.advance(operation);retried=executions.attempts(e.tasks().get("root")).getFirst().number()==2;}
                    else retried=lifecycle.retryTask(e.tasks().get("root"));
                }
                if(retried && !restored){
                    restored=true;
                    for(String name:oldCheckpoints.keySet()){
                        var attempt=executions.attempts(e.tasks().get(name)).getFirst();
                        var current=checkpoints.byAttemptSerial(attempt.id(),oldCheckpoints.get(name).request().serial()+1).orElse(null);
                        if(current==null){restored=false;break;}
                        // Inspect the immutable handover entry even if a subsequent ACK has already advanced latest.
                        assertThat(current.handoverFromId()).isEqualTo(oldCheckpoints.get(name).id());
                        assertThat(current.revision()).isEqualTo(oldCheckpoints.get(name).revision());
                        assertThat(((Map<?,?>)json.decode(current.summaryJson())).get("stateSha256"))
                            .isEqualTo(((Map<?,?>)json.decode(oldCheckpoints.get(name).summaryJson())).get("stateSha256"));
                    }
                    if(restored)Files.writeString(e.folder().resolve("restored"),"");
                }
                if(cancel && !cancelled && Files.exists(e.folder().resolve("cancel-request"))){
                    for(String name:List.of("root","sink")){
                        assertThat(checkpoints.latest(e.tasks().get(name))).isPresent();assertThat(runtimes.result(e.tasks().get(name))).isEmpty();
                    }
                    mvc.perform(post("/api/v1/tasks/"+e.tasks().get("sink")+"/cancel").with(user("test")).with(csrf()).contentType("application/json").content("{}"))
                        .andExpect(status().isOk());
                    cancelled=true;Files.writeString(e.folder().resolve("cancelled"),"");
                }
                Thread.sleep(20);
            }
            assertThat(child.isAlive()).as("Actual multi-runner probe deadline").isFalse();
            assertThat(child.exitValue()).as("Actual multi-runner probe: %s",Files.readString(e.folder().resolve("probe.log"))).isZero();
            assertThat(Files.readString(e.folder().resolve("probe.log"))).isEqualTo(recovery?"STREAM_DAG_RECOVERED\n":cancel?"STREAM_DAG_CANCELLED\n":"STREAM_DAG_PASS\n");
            assertThat(Files.size(e.folder().resolve("probe-error.log"))).isZero();
            if(recovery){
                assertThat(failed && stopped && retried && restored).isTrue();
                for(String name:List.of("root","sink")){
                    assertThat(executions.attempts(e.tasks().get(name))).hasSize(2);
                    assertThat(executions.attempts(e.tasks().get(name)).getFirst().cause()).isEqualTo(offload?"OFFLOAD":"RETRY");
                }
                if(offload){
                    var moved=offloads.find(operation);assertThat(moved.state()).isEqualTo("SUCCEEDED");assertThat(moved.members()).hasSize(2);
                    assertThat(runtimes.byAttempt(moved.targetAttemptId()).orElseThrow().nodeUid()).isEqualTo(target);
                    for(var member:moved.members())assertThat(member.checkpointId()).isEqualTo(oldCheckpoints.values().stream().filter(cp->cp.taskId().equals(member.taskId())).findFirst().orElseThrow().id());
                }
            }
            if(cancel){
                assertThat(cancelled).isTrue();
                for(String name:List.of("root","sink")){
                    UUID attempt=executions.attempts(e.tasks().get(name)).getFirst().id();
                    assertThat(completions.task(attempt)).isEmpty();lifecycle.confirmStopped(attempt);
                }
                assertThat(executions.task(e.tasks().get("sink")).orElseThrow().state()).isEqualTo("CANCELLED");
                assertThat(executions.task(e.tasks().get("root")).orElseThrow().state()).isEqualTo("SKIPPED");
                assertThat(executions.task(e.tasks().get("report")).orElseThrow().state()).isEqualTo("SKIPPED");
                assertThat(executions.attempts(e.tasks().get("report"))).isEmpty();
                e.tasks().values().forEach(t->assertThat(runtimes.result(t)).isEmpty());
            }else{
                assertThat(executions.run(e.run(),false).orElseThrow().state()).isEqualTo("SUCCEEDED");
                for(String name:e.tasks().keySet()){
                    var result=runtimes.result(e.tasks().get(name)).orElseThrow();var attempt=executions.attempts(e.tasks().get(name)).getFirst();
                    assertThat(result.attemptId()).isEqualTo(attempt.id());assertThat(result.producerPodUid()).isEqualTo(pods.get(attempt.id()).podUid());
                    assertThat(result.outputs()).hasSize(1);var output=result.outputs().getFirst();var artifact=output.artifact();
                    assertThat(artifact.bucket()).isEqualTo(BUCKET);assertThat(artifact.versionId()).isNotBlank().isNotEqualTo("null");
                    try(var bytes=MINIO.getObject(GetObjectArgs.builder().bucket(BUCKET).object(artifact.objectKey()).versionId(artifact.versionId()).build())){
                        byte[] actual=bytes.readAllBytes();assertThat(actual).isEqualTo(Files.readAllBytes(taskDirectory(e,name).resolve("work/outputs").resolve(output.port())));
                        assertThat(actual.length).isEqualTo(artifact.bytes());
                        assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(actual))).isEqualTo(artifact.sha256());
                    }
                }
                var root=completions.task(executions.attempts(e.tasks().get("root")).getFirst().id()).orElseThrow();
                var sink=completions.task(executions.attempts(e.tasks().get("sink")).getFirst().id()).orElseThrow();
                assertThat(root.grantedAt()).isNotNull().isEqualTo(sink.grantedAt());
            }
            until(()->routeStore.forRun(e.run(),20,0).stream().noneMatch(r->routeStore.open(r.id()).isPresent()));
            for(var r:routeStore.forRun(e.run(),20,0))assertThat(routeStore.history(r.id(),20,0)).hasSize(recovery?2:1).allMatch(g->g.closedAt()!=null);
            assertStartJournals(e.run());
        }finally{
            if(child.isAlive()){
                child.descendants().forEach(ProcessHandle::destroy);child.destroy();
                if(!child.waitFor(5,TimeUnit.SECONDS)){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();}
            }
        }
    }
    @Test void independentRunnersComputeTaskToTaskStreamAndReleaseBatchFromFixedS3Results()throws Exception{dagProbe(false);}
    @Test void cancellingLiveSinkStopsIndependentStreamRunnersAndNeverReleasesBatch()throws Exception{dagProbe(true);}
    @Test void realGroupRetryAutomaticallyReconnectsSameDeviceOwnersAndRestoresBothRunners()throws Exception{dagProbe(false,true);}
    @Test void publicNodeOffloadRestoresActualGroupCheckpointsAndReconnectsSameDeviceOwners()throws Exception{dagProbe(false,true,true);}
    @Test void runnerMeasurementsAutomaticallyTransferActualGroupStateAndReconnectSameDeviceOwners()throws Exception{dagProbe(false,true,true,true);}

    /** Inspect the actual records from HTTP claims, including both transferred peers and the BATCH child. */
    private void assertStartJournals(UUID run)throws Exception{
        int checked=0;
        for(var task:executions.tasks(run))for(var attempt:executions.attempts(task.id())){
            var runtime=runtimes.byAttempt(attempt.id()).orElseThrow();if(runtime.vd() || runtime.producerPodUid()==null)continue;
            String key="authority/runtime-start/"+runtime.id()+".json";
            var versions=new ArrayList<io.minio.messages.Item>();
            for(var result:MINIO.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(key).recursive(true).includeVersions(true).build()))versions.add(result.get());
            assertThat(versions).hasSize(1);assertThat(versions.getFirst().versionId()).isNotBlank();
            Map<?,?> document;
            try(var input=MINIO.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).versionId(versions.getFirst().versionId()).build())){
                document=(Map<?,?>)json.decode(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            var admitted=Instant.parse((String)document.get("admittedAt"));
            assertThat(admitted).isBefore(runtime.expiresAt()).isAfterOrEqualTo(runtime.createdAt());
            var transfer=offloadStore.forTask(task.id()).stream().filter(o->attempt.id().equals(o.targetAttemptId()) ||
                o.members().stream().anyMatch(m->attempt.id().equals(m.targetAttemptId()))).findFirst().orElse(null);
            if(transfer!=null)assertThat(admitted).isBefore(transfer.startDeadline());
            var expected=new RuntimeStartAuthority(runtime,(String)document.get("workDigest"),transfer==null?null:transfer.id(),transfer==null?null:transfer.startDeadline(),admitted);
            assertThat(json.canonical(document)).isEqualTo(json.canonical(expected.document())).doesNotContain(runtime.claimNonce().toString());checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(2);
    }

    /** Real supervisor poll and child processes. Only Kubernetes submission/Pod identity are fixtures. */
    private Supervisor startSupervisor(UUID service,int capacity,Path parent,String name)throws Exception{
        var vp=profiles.publish(ProfileIdentity.Kind.VD,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",
            Map.of("apiVersion","edgeai.vd/v1","type","emulation","serviceProfileVersionId",service.toString(),"sources",Map.of(),"state",Map.of("mode","STATELESS"),
                "runtime",Map.of("maxConcurrentTasks",capacity,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30))))).version();
        var vd=virtualDevices.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Actual stream supervisor","profileVersionId",vp.id().toString(),"sources",List.of(),"placement",Map.of("mode","AUTO")))).value();
        var op=vdLifecycle.provision(vd.id(),0,"provision",new io.edgeai.app.config.RuntimeSettings(BUCKET,"edgeai-runner",java.net.URI.create(apiTls.origin),120),false);
        var runtime=vdLifecycle.submitted(op.targetRuntimeId(),UUID.randomUUID());
        var folder=Files.createDirectory(parent.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createDirectory(folder.resolve("work"));secret(folder,"claim",vdTokens.issue(runtime));secret(folder,"pod","source-vd-pod-proof");secret(folder,"supervisor.log","");
        var builder=new ProcessBuilder(System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3"),Path.of("../../runner/vd.py").toAbsolutePath().normalize().toString());
        var env=builder.environment();env.keySet().removeIf(k->k.startsWith("EDGEAI_"));
        env.putAll(Map.of("EDGEAI_VD_ID",vd.id().toString(),"EDGEAI_VD_RUNTIME_ID",runtime.id().toString(),"EDGEAI_VD_GENERATION",Long.toString(runtime.generation()),
            "EDGEAI_POD_UID",runtime.podUid().toString(),"EDGEAI_CONTROL_PLANE_URL",apiTls.origin,"EDGEAI_VD_CLAIM_FILE",folder.resolve("claim").toString(),
            "EDGEAI_POD_TOKEN_FILE",folder.resolve("pod").toString(),"EDGEAI_WORK_DIR",folder.resolve("work").toString(),"EDGEAI_VD_MAX_CONCURRENT_TASKS",Integer.toString(capacity),"EDGEAI_VD_STARTUP_SECONDS","60"));
        env.put("EDGEAI_VD_DRAIN_SECONDS","30");env.put("SSL_CERT_FILE",BROKER.file("server.crt"));env.put("PYTHONDONTWRITEBYTECODE","1");
        // Register identity before starting HTTP poll. The record's process is replaced before waiting for Ready.
        var node=UUID.randomUUID();supervisors.put(runtime.id(),new Supervisor(runtime,node,folder,null));
        Process process=builder.redirectOutput(folder.resolve("supervisor.log").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var result=new Supervisor(runtime,node,folder,process);supervisors.put(runtime.id(),result);
        until(()->{assertThat(process.isAlive()).as("Actual VD supervisor stays alive").isTrue();return vdLifecycle.get(runtime.id()).ready(Instant.now());});return result;
    }
    private Map<String,Object> target(Supervisor s){return Map.of("mode","VD","vdId",s.runtime().vdId().toString());}
    private boolean stateCheckpoint(UUID task,int value){
        var cp=checkpoints.latest(task).orElse(null);if(cp==null)return false;
        try{return ((Map<?,?>)json.decode(cp.summaryJson())).get("stateSha256").equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Integer.toString(value).getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private Process startTransferredRunner(TaskAttempt attempt,Path parent)throws Exception{
        var folder=Files.createDirectory(parent.resolve("transferred-runner"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var work=Files.createDirectory(folder.resolve("work"));var node=nodeStore.find(attempt.nodeId()).orElseThrow();
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),node.id(),node.name());pods.put(attempt.id(),pod);
        lifecycle.submitted(attempt.id(),pod.jobUid());secret(folder,"claim",tokens.issue(runtimes.byAttempt(attempt.id()).orElseThrow()));
        secret(folder,"pod","source-pod-proof");secret(folder,"runner.log","");secret(folder,"runner-error.log","");
        var builder=new ProcessBuilder(System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3"),"-W","error::ResourceWarning",Path.of("../../runner/runner.py").toAbsolutePath().normalize().toString());
        var env=builder.environment();env.keySet().removeIf(k->k.startsWith("EDGEAI_"));
        env.putAll(Map.of("EDGEAI_ATTEMPT_ID",attempt.id().toString(),"EDGEAI_ATTEMPT_EPOCH",Long.toString(attempt.epoch()),
            "EDGEAI_POD_UID",pod.podUid().toString(),"EDGEAI_CONTROL_PLANE_URL",apiTls.origin,"EDGEAI_CLAIM_FILE",folder.resolve("claim").toString(),
            "EDGEAI_POD_TOKEN_FILE",folder.resolve("pod").toString(),"EDGEAI_WORK_DIR",work.toString(),"SSL_CERT_FILE",BROKER.file("server.crt")));
        return builder.redirectOutput(folder.resolve("runner.log").toFile()).redirectError(folder.resolve("runner-error.log").toFile()).start();
    }
    private void vdStreams(boolean shared,boolean recovery,boolean cancel)throws Exception{vdStreams(shared,recovery,cancel,false);}
    private void vdStreams(boolean shared,boolean recovery,boolean cancel,boolean offload)throws Exception{
        var folder=Files.createDirectory(BROKER.root.resolve("vd-stream-"+UUID.randomUUID()),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        UUID rootService=dagService(shared?"fanout":"root"),sinkService=shared?rootService:dagService("sink"),reportService=dagService("report");
        var root=startSupervisor(rootService,shared?2:1,folder,"root-vd");
        var sink=shared?root:startSupervisor(sinkService,1,folder,"sink-vd");var report=startSupervisor(reportService,1,folder,"report-vd");
        var definitions=List.of(Map.of("key","root","serviceProfileVersionId",rootService.toString(),"parameters",Map.of("mode","zip")),
            Map.of("key","sink","serviceProfileVersionId",sinkService.toString(),"parameters",shared?Map.of("mode","zip"):Map.of()),
            Map.of("key","report","serviceProfileVersionId",reportService.toString(),"parameters",Map.of()));
        var edges=new ArrayList<Map<String,Object>>();if(!shared)edges.add(edge("root","sink","sum","input","STREAM"));
        edges.add(edge("root","report","result","root","BATCH"));edges.add(edge("sink","report","result","sink","BATCH"));
        var workflow=workflows.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Actual VD stream data"))).value();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",definitions,"dependencies",edges))).value();
        var sources=new TreeMap<String,Object>();var inputs=new ArrayList<Object>();
        for(String name:List.of("a","b")){
            var dp=profiles.publish(ProfileIdentity.Kind.DEVICE,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",Map.of("protocol","mqtt")))).version();
            var device=devices.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Actual synthetic VD stream","profileVersionId",dp.id().toString(),"sourceMode","SYNTHETIC"))).value();
            var session=devices.openSession(device.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();secret(folder,name+".token",deviceTokens.issue(session));
            sources.put(name,Map.of("deviceId",device.id().toString(),"sessionId",session.id().toString(),"epoch",session.epoch()));
            for(String task:shared?List.of("root","sink"):List.of("root"))inputs.add(Map.of("deviceId",device.id().toString(),"sourcePort","samples","toTask",task,"toPort",name,"maxPayloadBytes",4096));
        }
        UUID run=publicRun(version.id(),inputs,recovery,false,target(root),Map.of("sink",target(sink),"report",target(report))).id();runIds.add(run);
        var tasks=new TreeMap<String,UUID>();executions.tasks(run).forEach(t->tasks.put(t.key(),t.id()));
        var targets=Map.of("root",root,"sink",sink,"report",report);
        secret(folder,"request.json",json.canonical(Map.of("origin",apiTls.origin,"runId",run.toString(),"sources",sources,"shared",shared,"mode",recovery?"recover":cancel?"cancel":"complete")));
        for(String log:List.of("probe.log","probe-error.log"))secret(folder,log,"");
        var builder=new ProcessBuilder(System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3"),"-W","error::ResourceWarning","src/test/fixtures/vd_stream_probe.py",folder.toString());
        builder.environment().put("SSL_CERT_FILE",BROKER.file("server.crt"));
        var driver=builder.redirectOutput(folder.resolve("probe.log").toFile()).redirectError(folder.resolve("probe-error.log").toFile()).start();
        String phase="CONNECT";Process transferredRunner=null;
        try{
            until(()->Files.exists(folder.resolve("first-sent")) || !driver.isAlive());assertThat(driver.isAlive()).as("VD stream sources connected").isTrue();
            phase="FIRST_CHECKPOINT";
            until(()->stateCheckpoint(tasks.get("root"),9) && stateCheckpoint(tasks.get("sink"),9));
            assertThat(executions.attempts(tasks.get("report"))).isEmpty();
            for(String name:List.of("root","sink")){
                var attempt=executions.attempts(tasks.get(name)).getFirst();var r=runtimes.byAttempt(attempt.id()).orElseThrow();
                assertThat(attempt.mode()).isEqualTo("VD");assertThat(r.producerPodUid()).isEqualTo(targets.get(name).runtime().podUid());
                assertThat(vdAllocations.byRuntime(r.id()).orElseThrow().open()).isTrue();
            }
            if(cancel){
                phase="CANCEL";
                runs.cancelRun(run,"{}");Files.writeString(folder.resolve("cancelled"),"");
                until(()->executions.run(run,false).orElseThrow().state().equals("CANCELLED"));
                tasks.values().forEach(id->assertThat(runtimes.result(id)).isEmpty());assertThat(executions.attempts(tasks.get("report"))).isEmpty();
            }else{
                if(recovery){
                    phase="OLD_CHILD_EXIT";
                    UUID operation=null,target=null;
                    if(offload){
                        target=UUID.randomUUID();nodes.recordSnapshot(List.of(new io.edgeai.domain.node.ExecutionNode(target,"vd-transfer-"+target,
                            "amd64","linux","READY","4","4Gi","{}",Instant.now())),Instant.now());
                        var response=mvc.perform(post("/api/v1/tasks/"+tasks.get("sink")+"/offload").with(user("test")).with(csrf())
                            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                            .content(json.canonical(Map.of("sourceAttemptId",executions.attempts(tasks.get("sink")).getFirst().id().toString(),
                                "targetNodeId",target.toString(),"drainTimeoutSeconds",60,"startTimeoutSeconds",60)))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
                        operation=UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id"));
                    }else lifecycle.observeFailure(executions.attempts(tasks.get("sink")).getFirst().id(),"RUNTIME_LOST");
                    var old=new HashMap<String,StreamCheckpoint>();for(String name:List.of("root","sink"))old.put(name,checkpoints.latest(tasks.get(name)).orElseThrow());
                    assertThat(lifecycle.retryTask(tasks.get("root"))).isFalse();Files.writeString(folder.resolve("fault-injected"),"");
                    // Only actual supervisor completion polls close the old slots; no confirmStopped fixture here.
                    until(()->old.values().stream().allMatch(cp->runtimes.runtime(cp.runtimeId()).orElseThrow().observedState().equals("TERMINATED")
                        && !vdAllocations.byRuntime(cp.runtimeId()).orElseThrow().open()));
                    phase="RETRY";
                    if(offload){
                        UUID id=operation;until(()->{offloads.advance(id);return offloads.find(id).state().equals("STARTING");});
                        transferredRunner=startTransferredRunner(executions.attempts(tasks.get("sink")).getFirst(),folder);
                    }else until(()->lifecycle.retryTask(tasks.get("root")));
                    phase="HANDOVER";
                    until(()->old.entrySet().stream().allMatch(entry->{
                        var next=executions.attempts(tasks.get(entry.getKey())).getFirst();
                        return checkpoints.byAttemptSerial(next.id(),entry.getValue().request().serial()+1).isPresent();
                    }));
                    for(String name:old.keySet()){
                        var previous=old.get(name);var next=executions.attempts(tasks.get(name)).getFirst();
                        var transferred=checkpoints.byAttemptSerial(next.id(),previous.request().serial()+1).orElseThrow();
                        if(offload && name.equals("sink")){assertThat(next.mode()).isEqualTo("NODE");assertThat(next.nodeId()).isEqualTo(target);assertThat(next.vdId()).isNull();}
                        else assertThat(next.vdId()).isEqualTo(targets.get(name).runtime().vdId());
                        assertThat(next.epoch()).isEqualTo(2);assertThat(next.cause()).isEqualTo(offload?"OFFLOAD":"RETRY");
                        assertThat(transferred.handoverFromId()).isEqualTo(previous.id());assertThat(transferred.revision()).isEqualTo(previous.revision());
                        assertThat(((Map<?,?>)json.decode(transferred.summaryJson())).get("stateSha256")).isEqualTo(((Map<?,?>)json.decode(previous.summaryJson())).get("stateSha256"));
                        assertThat(transferred.artifact().versionId()).isNotBlank();assertThat(transferred.artifact().objectKey()).isNotEqualTo(previous.artifact().objectKey());
                    }
                    if(offload){
                        var moved=offloads.find(operation);assertThat(moved.state()).isEqualTo("SUCCEEDED");
                        assertThat(moved.members()).anySatisfy(m->{assertThat(m.taskId()).isEqualTo(tasks.get("root"));assertThat(m.targetVdId()).isEqualTo(root.runtime().vdId());});
                    }
                    Files.writeString(folder.resolve("restored"),"");
                }else Files.writeString(folder.resolve("first-verified"),"");
                phase="SECOND_CHECKPOINT";
                until(()->stateCheckpoint(tasks.get("root"),14) && stateCheckpoint(tasks.get("sink"),shared?14:23));
                Files.writeString(folder.resolve("second-verified"),"");
                phase="RESULT";
                until(()->executions.run(run,false).orElseThrow().state().equals("SUCCEEDED"));
                for(String name:tasks.keySet()){
                    var result=runtimes.result(tasks.get(name)).orElseThrow();var expected=targets.get(name).runtime();
                    if(offload && name.equals("sink")){
                        assertThat(result.vdRuntimeId()).isNull();assertThat(result.producerPodUid()).isEqualTo(pods.get(result.attemptId()).podUid());
                    }else{assertThat(result.vdRuntimeId()).isEqualTo(expected.id());assertThat(result.producerPodUid()).isEqualTo(expected.podUid());}
                    assertThat(executions.attempts(tasks.get(name))).hasSize(recovery && !name.equals("report")?2:1);
                    var artifact=result.outputs().getFirst().artifact();assertThat(artifact.versionId()).isNotBlank().isNotEqualTo("null");
                    try(var input=MINIO.getObject(GetObjectArgs.builder().bucket(artifact.bucket()).object(artifact.objectKey()).versionId(artifact.versionId()).build())){
                        byte[] bytes=input.readAllBytes();assertThat(bytes.length).isEqualTo(artifact.bytes());
                        assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(artifact.sha256());
                        Object value=name.equals("report")?Map.of("sourceMode","SYNTHETIC","sum",shared?28:37,"inputs",Map.of("root",14,"sink",shared?14:23)):Map.of("sum",name.equals("root") || shared?14:23);
                        assertThat(json.canonical(json.decode(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)))).isEqualTo(json.canonical(value));
                    }
                }
                var a=completions.task(executions.attempts(tasks.get("root")).getFirst().id()).orElseThrow();
                var b=completions.task(executions.attempts(tasks.get("sink")).getFirst().id()).orElseThrow();assertThat(a.grantedAt()).isNotNull().isEqualTo(b.grantedAt());
            }
            phase="DRIVER_EXIT";assertThat(driver.waitFor(15,TimeUnit.SECONDS)).isTrue();
            assertThat(driver.exitValue()).as("VD device driver: %s",Files.readString(folder.resolve("probe.log"))).isZero();
            assertThat(Files.readString(folder.resolve("probe.log"))).isEqualTo(cancel?"VD_STREAM_CANCELLED\n":recovery?"VD_STREAM_RECOVERED\n":"VD_STREAM_PASS\n");
            assertThat(Files.size(folder.resolve("probe-error.log"))).isZero();
            if(transferredRunner!=null){assertThat(transferredRunner.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(transferredRunner.exitValue()).isZero();
                lifecycle.confirmStopped(executions.attempts(tasks.get("sink")).getFirst().id());}
            phase="CLEANUP";until(()->supervisors.values().stream().allMatch(s->vdAllocations.open(s.runtime().id()).isEmpty()));
            until(()->routeStore.forRun(run,20,0).stream().noneMatch(r->routeStore.open(r.id()).isPresent()));
            assertThat(routeStore.forRun(run,20,0)).allMatch(r->routeStore.history(r.id(),20,0).size()==(recovery?2:1));
            for(var s:supervisors.values()){
                assertThat(s.process().isAlive()).isTrue();vdLifecycle.drain(s.runtime().vdId(),0,"drain");
                assertThat(s.process().waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(s.process().exitValue()).isZero();
                try(var paths=Files.list(s.folder().resolve("work/attempts"))){assertThat(paths.count()).isZero();}
            }
        }catch(Exception|AssertionError error){
            var states=new TreeMap<String,Object>();
            for(var item:tasks.entrySet()){
                var attempts=executions.attempts(item.getValue());var a=attempts.isEmpty()?null:attempts.getFirst();
                var r=a==null?null:runtimes.byAttempt(a.id()).orElse(null);var cp=checkpoints.latest(item.getValue()).orElse(null);
                states.put(item.getKey(),Map.of("task",executions.task(item.getValue()).orElseThrow().state(),"attempts",attempts.size(),
                    "runtime",r==null?"NONE":r.desiredState()+"/"+r.observedState(),"reason",r==null || r.failureReason()==null?"NONE":r.failureReason(),
                    "checkpointSerial",cp==null?-1:cp.request().serial()));
            }
            var lines=Files.readAllLines(folder.resolve("probe.log")).stream().filter(s->s.matches("VD_STREAM_[A-Za-z0-9_ .,:-]+") && s.length()<2000).toList();
            System.out.println("VD_STREAM_DIAGNOSTIC "+json.canonical(Map.of("phase",phase,"shared",shared,"recovery",recovery,"cancel",cancel,"offload",offload,
                "runState",executions.run(run,false).orElseThrow().state(),"tasks",states,"driverAlive",driver.isAlive(),"driver",lines)));
            throw error;
        }finally{
            if(transferredRunner!=null && transferredRunner.isAlive()){
                transferredRunner.descendants().forEach(ProcessHandle::destroy);transferredRunner.destroy();
                if(!transferredRunner.waitFor(5,TimeUnit.SECONDS)){transferredRunner.descendants().forEach(ProcessHandle::destroyForcibly);transferredRunner.destroyForcibly();assertThat(transferredRunner.waitFor(5,TimeUnit.SECONDS)).isTrue();}
            }
            if(driver.isAlive()){driver.destroy();if(!driver.waitFor(5,TimeUnit.SECONDS)){driver.destroyForcibly();assertThat(driver.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
        }
    }
    @Test void actualSharedVdChildrenCompleteFanoutAndReleaseVerifiedBatch()throws Exception{vdStreams(true,false,false);}
    @Test void actualDistinctVdsStreamAcrossSupervisorsAndReleaseVerifiedBatch()throws Exception{vdStreams(false,false,false);}
    @Test void actualSharedVdGroupRetryTransfersS3StateAndReconnectsSameSources()throws Exception{vdStreams(true,true,false);}
    @Test void actualDistinctVdGroupRetryTransfersS3StateAndReconnectsSameSources()throws Exception{vdStreams(false,true,false);}
    @Test void actualSharedVdCancellationWaitsForChildExitsAndNeverReleasesBatch()throws Exception{vdStreams(true,false,true);}
    @Test void publicSharedVdNodeOffloadTransfersStateAndPreservesVdPeer()throws Exception{vdStreams(true,true,false,true);}
    @Test void publicDistinctVdNodeOffloadTransfersStateAndPreservesVdPeer()throws Exception{vdStreams(false,true,false,true);}
    private void until(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        boolean reached=condition.getAsBoolean();
        while(!reached && System.nanoTime()<end){Thread.sleep(20);reached=condition.getAsBoolean();}
        assertThat(reached).as("Actual stream completion boundary reached").isTrue();}
}
