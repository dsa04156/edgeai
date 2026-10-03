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

/** Public Run MVC, actual Spring/MinIO over TLS, PG, TLS broker, SDK and independent Runners.
 * Pod provisioning/identity are fixtures; the recovery tests additionally model peer-triggered closure. */
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
    @Autowired StreamRunService streamRuns;@Autowired WorkflowRepository workflowStore;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean RuntimeGateway gateway;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private final JsonDocuments json=new JsonDocuments();
    private StreamTlsProxy apiTls;
    private final Map<UUID,RuntimePod> pods=new ConcurrentHashMap<>();private final List<UUID> runIds=new ArrayList<>();
    private record Execution(UUID run,UUID task,UUID attempt,List<UUID> generations,Path folder){}
    private record DagExecution(UUID run,Map<String,UUID> tasks,Path folder){}
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
    @SuppressWarnings("unchecked") private UUID dagService(String name)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/"+(name.equals("report")?"service-execution":"service-stream")+".example.json")));
        String python=System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3");
        spec.put("command",List.of(python,Path.of("../../runner/examples/"+(name.equals("report")?"stream_report":"stream_result")+".py").toAbsolutePath().normalize().toString()));
        if(name.equals("report")){
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
        }
        return profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
    }
    private Map<String,Object> edge(String from,String to,String output,String input,String mode){
        return Map.of("fromTask",from,"toTask",to,"fromPort",output,"toPort",input,"mode",mode);
    }
    private DagExecution dagFixture(boolean cancel,boolean recovery)throws Exception{
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
        UUID id;
        if(recovery){
            // Internal policy fixture until finalization recovery and automatic source reconnect are connected.
            id=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->{
                var bindings=io.edgeai.app.support.StreamRunInput.parse(json.decode(json.canonical(inputs)));
                var sessions=streamRuns.pin(bindings,"AUTO",RetryPolicy.disabled(),false);var now=Instant.now();
                var run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),"sha256:"+"d".repeat(64),"AUTO",null,"{}",
                    new RetryPolicy(2,1,300,Set.of("RUNTIME_LOST")),null,"PENDING",now,now);
                assertThat(executions.create(run)).isTrue();executions.initialize(run,workflowStore.definitions(version.id()),Set.of());
                streamRuns.configure(run,BUCKET,bindings,sessions);lifecycle.startRun(run.id(),BUCKET);return run.id();
            });
        }else{
            var response=mvc.perform(post("/api/v1/workflow-runs").with(user("test")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content(json.canonical(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of(),"streamInputs",inputs))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            id=UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id"));
        }
        runIds.add(id);
        var tasks=new TreeMap<String,UUID>();executions.tasks(id).forEach(t->tasks.put(t.key(),t.id()));
        assertThat(executions.attempts(tasks.get("report"))).isEmpty();
        secret(folder,"request.json",json.canonical(Map.of("origin",apiTls.origin,"runId",id.toString(),"mode",recovery?"recover":cancel?"cancel":"complete","sources",sources)));
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
            var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");pods.put(attempt.id(),pod);
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
        var e=dagFixture(cancel,recovery);provision(e);
        var builder=new ProcessBuilder(System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3"),"-W","error::ResourceWarning","src/test/fixtures/stream_dag_probe.py",e.folder().toString())
            .redirectOutput(e.folder().resolve("probe.log").toFile()).redirectError(e.folder().resolve("probe-error.log").toFile());
        builder.environment().put("SSL_CERT_FILE",BROKER.file("server.crt"));var child=builder.start();
        try{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);boolean cancelled=false,failed=false,stopped=false,retried=false,restored=false;
            var oldCheckpoints=new HashMap<String,StreamCheckpoint>();
            while(child.isAlive() && System.nanoTime()<end){
                provision(e);
                if(recovery && !failed && Files.exists(e.folder().resolve("recovery-request"))){
                    lifecycle.observeFailure(executions.attempts(e.tasks().get("sink")).getFirst().id(),"RUNTIME_LOST");failed=true;
                    // The Run fence has committed: a concurrent old publisher can no longer advance this snapshot.
                    for(String name:List.of("root","sink"))oldCheckpoints.put(name,checkpoints.latest(e.tasks().get(name)).orElseThrow());
                    for(String name:oldCheckpoints.keySet())assertThat(executions.task(e.tasks().get(name)).orElseThrow().state()).isEqualTo("RETRY_WAIT");
                }
                if(recovery && failed && !stopped && Files.exists(e.folder().resolve("producers-stopped"))){
                    for(String name:oldCheckpoints.keySet()){
                        var previous=oldCheckpoints.get(name);var r=runtimes.byAttempt(previous.attemptId()).orElseThrow();
                        jdbc.update("UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=? AND kind='CREATE'",r.id());
                        lifecycle.confirmStopped(previous.attemptId());
                    }
                    stopped=true;
                }
                if(stopped && !retried)retried=lifecycle.retryTask(e.tasks().get("root"));
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
                for(String name:List.of("root","sink"))assertThat(executions.attempts(e.tasks().get(name))).hasSize(2);
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
        }finally{
            if(child.isAlive()){
                child.descendants().forEach(ProcessHandle::destroy);child.destroy();
                if(!child.waitFor(5,TimeUnit.SECONDS)){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();}
            }
        }
    }
    @Test void independentRunnersComputeTaskToTaskStreamAndReleaseBatchFromFixedS3Results()throws Exception{dagProbe(false);}
    @Test void cancellingLiveSinkStopsIndependentStreamRunnersAndNeverReleasesBatch()throws Exception{dagProbe(true);}
    @Test void realGroupRetryRestoresBothIndependentRunnersAndHandsOverBothDeviceJournals()throws Exception{dagProbe(false,true);}
    private void until(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(20);assertThat(condition.getAsBoolean()).as("Actual stream completion boundary reached").isTrue();}
}
