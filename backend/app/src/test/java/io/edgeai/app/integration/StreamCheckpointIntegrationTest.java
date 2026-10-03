package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.config.DeviceStreamPrincipal;
import io.edgeai.domain.execution.*;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.app.support.StreamCheckpointDocument;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.StreamBrokerGateway.Permission;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP, PostgreSQL and S3. Pod authentication and broker activation receipts are explicit fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class StreamCheckpointIntegrationTest {
    private static final Storage STORAGE=new Storage();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("edgeai.runtime.namespace",()->"checkpoint-api-"+STORAGE.id);p.add("edgeai.runtime.key-file",()->STORAGE.key.toString());
        p.add("edgeai.storage.endpoint",()->required("EDGEAI_STORAGE_URL"));p.add("edgeai.storage.runner-endpoint",()->required("EDGEAI_STORAGE_URL"));
        p.add("edgeai.storage.access-key",()->required("EDGEAI_MINIO_USER"));p.add("edgeai.storage.secret-key",()->required("EDGEAI_MINIO_PASSWORD"));
        p.add("edgeai.storage.bucket",()->STORAGE.bucket);
    }
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired ExecutionService runs;@Autowired DeviceService devices;
    @Autowired RuntimeRepository runtimes;@Autowired RuntimeLifecycleService lifecycle;@Autowired RunnerTokenService tokens;
    @Autowired StreamExecutionRepository streamExecutions;
    @Autowired StreamExecutionService streamExecution;
    @Autowired DataRouteService routes;@Autowired DataRouteRepository routeStore;@Autowired StreamCheckpointRepository checkpoints;
    @Autowired ExecutionRepository executions;@Autowired WorkflowRepository definitions;@Autowired PlatformTransactionManager transactions;
    @Autowired S3ArtifactStore storage;@Autowired StreamCheckpointService service;@Autowired JdbcTemplate jdbc;
    @MockitoBean RuntimeGateway gateway;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @TempDir Path directory;
    private final JsonDocuments json=new JsonDocuments();
    private final Map<UUID,RuntimePod> pods=new ConcurrentHashMap<>();private final List<UUID> runIds=new ArrayList<>();
    private record Execution(UUID run,UUID task,UUID attempt,RuntimePod pod,List<Permission> permissions){}
    private record Reply(int status,Map<?,?> body){}
    @BeforeEach void setup(){when(gateway.authenticatePod(any(),any())).thenAnswer(c->{RuntimeInstance r=c.getArgument(0);
        if(!"checkpoint-pod-proof".equals(c.getArgument(1)) || !pods.containsKey(r.attemptId()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
        return pods.get(r.attemptId());});}
    @AfterEach void cleanup(){for(var id:runIds)runs.cancelRun(id,"{}");}
    @AfterAll static void stop()throws Exception{STORAGE.close();}
    private Execution execution()throws Exception {return execution(false);}
    private Execution execution(boolean retry)throws Exception{return execution(retry,false);}
    @SuppressWarnings("unchecked") private Execution execution(boolean retry,boolean streaming)throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/"+(streaming?"service-stream.example.json":"service-execution.example.json"))));
        if(streaming)((Map<String,Object>)spec.get("stream")).put("outputs",Map.of());
        else spec.put("inputs",Map.of("a",Map.of("mediaType","application/json","maxBytes",4096,"required",false),"b",Map.of("mediaType","application/json","maxBytes",4096,"required",false)));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","checkpoint-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","checkpoint-"+UUID.randomUUID(),"displayName","Checkpoint fixture"))).value();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",List.of(Map.of("key","sum","serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())),"dependencies",List.of()))).value();
        var input=new TreeMap<String,Object>(Map.of("workflowVersionId",version.id().toString(),"parameters",Map.of(),"execution",Map.of("mode","AUTO")));
        if(retry)input.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",600,"retryOn",List.of("WORKLOAD_FAILED")));
        WorkflowRun run;
        if(streaming){
            // Public STREAM remains disabled; seed only its already-running Pod boundary.
            var now=Instant.now();run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),json.digest("stream-checkpoint-fixture",input),"AUTO",null,"{}",RetryPolicy.disabled(),null,"PENDING",now,now);
            new TransactionTemplate(transactions).execute(s->{executions.create(run);executions.initialize(run,definitions.definitions(version.id()),Set.of("sum"));
                var task=executions.tasks(run.id()).getFirst();var attempt=executions.attempts(task.id()).getFirst();
                runtimes.create(new RuntimeInstance(UUID.randomUUID(),attempt.id(),task.id(),run.id(),1,"checkpoint-api-"+STORAGE.id,"edgeai-"+attempt.id(),UUID.randomUUID(),
                    "RUNNING","PENDING",null,null,null,null,null,null,now,now));return null;});
        }else run=runs.create(UUID.randomUUID().toString(),json.canonical(input)).value();
        runIds.add(run.id());
        var task=runs.detail(run.id()).tasks().getFirst();var attempt=runs.taskDetail(task.id()).attempts().getFirst();
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");pods.put(attempt.id(),pod);
        lifecycle.submitted(attempt.id(),pod.jobUid());lifecycle.claim(attempt.id(),1,pod);
        var permissions=new ArrayList<Permission>();
        for(String name:List.of("a","b")){
            var dp=profiles.publish(ProfileIdentity.Kind.DEVICE,json.canonical(Map.of("key","checkpoint-"+UUID.randomUUID(),"version","1.0.0","spec",Map.of("protocol","mqtt")))).version();
            var device=devices.create(json.canonical(Map.of("key","checkpoint-"+UUID.randomUUID(),"displayName","Checkpoint source","profileVersionId",dp.id().toString(),"sourceMode","SYNTHETIC"))).value();
            var session=devices.openSession(device.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
            var route=routes.fromDevice(run.id(),device.id(),"samples",task.id(),name,4096);
            var generation=routes.prepare(route.id(),UUID.randomUUID(),new RouteGeneration.Actor(session.id(),session.epoch()),new RouteGeneration.Actor(attempt.id(),1),"sha256:"+"b".repeat(64),120);
            generation=routes.activate(new RouteGeneration.BrokerReceipt(generation.id(),generation.brokerDigest(),generation.policyDigest()));
            permissions.add(new Permission(route,generation));
        }
        return new Execution(run.id(),task.id(),attempt.id(),pod,List.copyOf(permissions));
    }
    private byte[] snapshot(Execution e)throws Exception {return snapshot(e,false);}
    private byte[] snapshot(Execution e,boolean advance)throws Exception{return snapshot(e,advance,false);}
    private byte[] snapshot(Execution e,boolean advance,boolean terminal)throws Exception {
        var folder=Files.createTempDirectory(directory,"snapshot-");
        var bindings=e.permissions().stream().map(p->Map.of("routeId",p.route().id().toString(),"generation",p.generation().generation(),"producer",
            Map.of("kind","DEVICE_SESSION","deviceId",p.route().sourceDeviceId().toString(),"sessionId",p.generation().producer().id().toString(),"epoch",p.generation().producer().epoch()))).toList();
        Files.writeString(folder.resolve("request.json"),json.canonical(Map.of("bindings",bindings,"advance",advance,"terminal",terminal)));
        var child=new ProcessBuilder("python3",Path.of("src/test/fixtures/stream_checkpoint_api_probe.py").toAbsolutePath().toString(),folder.toString()).redirectErrorStream(true).start();
        try{
            assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();
            assertThat(new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip()).isEqualTo("STREAM_CHECKPOINT_API_FIXTURE_PASS");
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}}
        return Files.readAllBytes(folder.resolve("snapshot.json"));
    }
    private Map<String,Object> request(Execution e,byte[] bytes,UUID previous)throws Exception {
        var value=(Map<?,?>)json.decode(new String(bytes,StandardCharsets.UTF_8));var q=new TreeMap<String,Object>();
        q.put("previousCheckpointId",previous==null?null:previous.toString());q.put("serial",value.get("serial"));q.put("sha256",sha(bytes));q.put("bytes",bytes.length);
        q.put("executionSha256",value.get("executionSha256"));q.put("generationIds",e.permissions().stream().map(p->p.generation().id().toString()).toList());return q;
    }
    private long epoch(Execution e){return e.permissions().getFirst().generation().consumer().epoch();}
    private Map<String,Object> body(Execution e){return new TreeMap<>(Map.of("epoch",epoch(e),"podUid",e.pod().podUid().toString()));}
    private String commitBody(Execution e,Map<String,Object> q,String version){var body=body(e);body.put("checkpoint",q);body.put("versionId",version);return json.canonical(body);}
    private Reply send(Execution e,String operation,Object value,String podToken,boolean bearer)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/attempts/"+e.attempt()+"/streams/checkpoints/"+operation))
            .timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("X-EdgeAI-Pod-Token",podToken);
        if(bearer)request.header("Authorization","Bearer "+tokens.issue(runtimes.byAttempt(e.attempt()).orElseThrow()));
        try(var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()){
            var response=client.send(request.POST(HttpRequest.BodyPublishers.ofString(value instanceof String s?s:json.canonical(value))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=401)assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            return new Reply(response.statusCode(),response.body().isBlank()?Map.of():(Map<?,?>)json.decode(response.body()));
        }
    }
    private Reply send(Execution e,String operation,Object value)throws Exception{return send(e,operation,value,"checkpoint-pod-proof",true);}
    private String upload(Execution e,Map<String,Object> q,byte[] bytes)throws Exception {
        var body=body(e);body.put("checkpoint",q);var response=send(e,"uploads",body);assertThat(response.status()).isEqualTo(200);
        var grant=(Map<?,?>)response.body().get("upload");assertThat(grant).isNotNull();
        var request=HttpRequest.newBuilder(URI.create((String)grant.get("url"))).timeout(Duration.ofSeconds(10));
        ((Map<?,?>)grant.get("headers")).forEach((k,v)->request.header((String)k,(String)v));
        try(var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()){
            var result=client.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding());
            assertThat(result.statusCode()).isEqualTo(200);String version=result.headers().firstValue("x-amz-version-id").orElseThrow();
            STORAGE.versions.add(Map.entry(key(e,q),version));return version;
        }
    }
    private String key(Execution e,Map<String,Object> q){return "tasks/"+e.task()+"/attempts/"+e.attempt()+"/stream-checkpoint/"+q.get("sha256");}
    private RunnerPrincipal principal(Execution e){return new RunnerPrincipal(e.attempt(),epoch(e),e.pod());}
    private Map<String,Object> query(Execution e){var value=body(e);value.put("generationIds",e.permissions().stream().map(p->p.generation().id().toString()).toList());return value;}
    private Map<String,Object> handoverBody(Execution e){var value=query(e);value.put("executionSha256","a".repeat(64));return value;}
    private StreamCheckpoint seal(Execution e)throws Exception{
        byte[] bytes=snapshot(e);var q=request(e,bytes,null);String version=upload(e,q,bytes);
        assertThat(send(e,"commit",commitBody(e,q,version)).status()).isEqualTo(201);
        return checkpoints.latest(e.task()).orElseThrow();
    }
    private Execution transition(Execution source,boolean retry)throws Exception{
        UUID attempt=source.attempt();RuntimePod pod=source.pod();long epoch=epoch(source);
        if(retry){
            jdbc.update("UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE kind='CREATE' AND runtime_id=(SELECT id FROM edgeai.runtime_instance WHERE attempt_id=?)",attempt);
            lifecycle.fail(attempt,epoch,pod.podUid(),"WORKLOAD_FAILED");assertThat(lifecycle.retryTask(source.task())).isFalse();
            lifecycle.confirmStopped(attempt);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean ready=false;
            while(!(ready=lifecycle.retryTask(source.task())) && System.nanoTime()<until)Thread.sleep(20);
            assertThat(ready).isTrue();var next=executions.attempts(source.task()).getFirst();attempt=next.id();epoch=next.epoch();
            pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-next-node");pods.put(attempt,pod);
            lifecycle.submitted(attempt,pod.jobUid());lifecycle.claim(attempt,epoch,pod);
        }
        var permissions=new ArrayList<Permission>();
        for(var p:source.permissions()){
            var g=p.generation();routes.fence(g.id(),"REPLACED");
            routes.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));
            var next=routes.prepare(p.route().id(),UUID.randomUUID(),g.producer(),new RouteGeneration.Actor(attempt,epoch),g.brokerDigest(),120);
            next=routes.activate(new RouteGeneration.BrokerReceipt(next.id(),next.brokerDigest(),next.policyDigest()));
            permissions.add(new Permission(p.route(),next));
        }
        return new Execution(source.run(),source.task(),attempt,pod,List.copyOf(permissions));
    }
    @Test void actualSdkTerminalSnapshotAndVerifiedS3CommitGateComponentCompletion()throws Exception {
        var e=execution(false,true);var principal=principal(e);String identity=json.canonical(body(e));
        assertThat(((Map<?,?>)streamExecution.execution(principal,identity)).get("state")).isEqualTo("READY");
        var initial=seal(e);var report=body(e);report.put("checkpointId",initial.id().toString());
        assertThatThrownBy(()->streamExecution.complete(principal,json.canonical(report))).isInstanceOfSatisfying(ControlPlaneException.class,x->assertThat(x.code()).isEqualTo("STREAM_END_NOT_CONFIRMED"));
        byte[] bytes=snapshot(e,false,true);var q=request(e,bytes,initial.id());String version=upload(e,q,bytes);
        assertThat(send(e,"commit",commitBody(e,q,version)).status()).isEqualTo(201);var sealed=checkpoints.latest(e.task()).orElseThrow();
        assertThat(sealed.artifact().versionId()).isEqualTo(version);assertThat(sealed.artifact().sha256()).isEqualTo(sha(bytes));
        report.put("checkpointId",sealed.id().toString());assertThat(((Map<?,?>)streamExecution.complete(principal,json.canonical(report))).get("state")).isEqualTo("WAITING");
        var manifest=new ResultManifest(List.of(new ResultManifest.Output("result",9,"a".repeat(64),"application/json","unused-preparation-version")));
        assertThatThrownBy(()->lifecycle.prepareCommit(e.attempt(),1,e.pod().podUid(),manifest)).isInstanceOfSatisfying(ControlPlaneException.class,x->assertThat(x.code()).isEqualTo("STREAM_COMPLETION_REQUIRED"));
        byte[] later=snapshot(e,true,true);var uploadBody=body(e);uploadBody.put("checkpoint",request(e,later,sealed.id()));
        var denied=send(e,"uploads",uploadBody);assertThat(denied.status()).isEqualTo(409);assertThat(denied.body().get("code")).isEqualTo("STREAM_CHECKPOINT_TERMINAL");
        assertThat(send(e,"commit",commitBody(e,q,version)).status()).isEqualTo(200);
        for(int i=0;i<e.permissions().size();i++){
            var p=e.permissions().get(i);var g=p.generation();var device=new DeviceStreamPrincipal(p.route().sourceDeviceId(),g.producer().id(),g.producer().epoch());
            var response=(Map<?,?>)streamExecution.deviceComplete(device,json.canonical(Map.of("epoch",device.epoch(),"generationId",g.id().toString(),"sequence",2)));
            assertThat(response.get("state")).isEqualTo(i==0?"WAITING":"FINALIZE");
        }
        assertThat(((Map<?,?>)streamExecution.complete(principal,json.canonical(report))).get("state")).isEqualTo("FINALIZE");
        assertThat(lifecycle.prepareCommit(e.attempt(),1,e.pod().podUid(),manifest).runtime().attemptId()).isEqualTo(e.attempt());
    }
    @Test void newAttemptReceivesServerReboundSnapshotAndActualPythonContinuesFromNineToFourteen()throws Exception{
        var source=execution(true);var original=seal(source);var next=transition(source,true);
        assertThat(next.task()).isEqualTo(source.task());assertThat(next.attempt()).isNotEqualTo(source.attempt());assertThat(epoch(next)).isEqualTo(2);
        assertThat(send(next,"latest",query(next)).status()).isEqualTo(409);
        var folder=Files.createTempDirectory(directory,"handover-client-");
        Files.writeString(folder.resolve("claim"),tokens.issue(runtimes.byAttempt(next.attempt()).orElseThrow()));
        Files.writeString(folder.resolve("pod"),"checkpoint-pod-proof");
        for(String name:List.of("claim","pod"))Files.setPosixFilePermissions(folder.resolve(name),PosixFilePermissions.fromString("rw-------"));
        Files.writeString(folder.resolve("request.json"),json.canonical(Map.of("origin","http://127.0.0.1:"+port,"runId",next.run().toString(),
            "attemptId",next.attempt().toString(),"epoch",epoch(next),"podUid",next.pod().podUid().toString(),"generationIds",query(next).get("generationIds"),
            "sourceId",original.id().toString(),"sourceSerial",original.request().serial())));
        var child=new ProcessBuilder("python3",Path.of("src/test/fixtures/stream_checkpoint_handover_probe.py").toAbsolutePath().toString(),folder.toString()).redirectErrorStream(true).start();
        try{
            assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();
            assertThat(new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip()).isEqualTo("STREAM_CHECKPOINT_HANDOVER_PASS");
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}}
        var latest=checkpoints.latest(next.task()).orElseThrow();assertThat(latest.attemptId()).isEqualTo(next.attempt());assertThat(latest.revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.stream_checkpoint WHERE task_id=?",Integer.class,next.task())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.stream_checkpoint WHERE task_id=? AND handover_from_id=?",Integer.class,next.task(),original.id())).isEqualTo(1);
        assertThat(send(source,"latest",query(source)).status()).isIn(401,409);
    }
    @Test void concurrentGenerationHandoverSealsOneSnapshotAndKeepsTheSameCalculation()throws Exception{
        var source=execution();var original=seal(source);var next=transition(source,false);
        assertThat(send(next,"latest",query(next)).status()).isEqualTo(409);
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->send(next,"handover",handoverBody(next)));var second=pool.submit(()->send(next,"handover",handoverBody(next)));
            var a=first.get(15,TimeUnit.SECONDS);var b=second.get(15,TimeUnit.SECONDS);
            assertThat(a.status()).isEqualTo(200);assertThat(b.status()).isEqualTo(200);
            assertThat(a.body().get("checkpoint")).isEqualTo(b.body().get("checkpoint"));
        }
        var latest=checkpoints.latest(next.task()).orElseThrow();assertThat(latest.handoverFromId()).isEqualTo(original.id());
        assertThat(latest.request().serial()).isEqualTo(original.request().serial()+1);assertThat(latest.revision()).isEqualTo(original.revision());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.stream_checkpoint WHERE task_id=?",Integer.class,next.task())).isEqualTo(2);
        assertThat(send(next,"handover",handoverBody(next)).status()).isEqualTo(200);
    }
    @Test void handoverRejectsMissingStateChangedExecutionAndUnauthenticatedProducer()throws Exception{
        var source=execution();assertThat(send(source,"handover",handoverBody(source)).status()).isEqualTo(409);
        var original=seal(source);var next=transition(source,false);
        var changed=handoverBody(next);changed.put("executionSha256","f".repeat(64));
        assertThat(send(next,"handover",changed).status()).isEqualTo(409);
        assertThat(send(next,"handover",handoverBody(next),"incorrect-proof",true).status()).isEqualTo(401);
        assertThat(send(next,"handover",handoverBody(next),"checkpoint-pod-proof",false).status()).isEqualTo(401);
        assertThat(checkpoints.latest(source.task()).orElseThrow().id()).isEqualTo(original.id());
    }
    @Test void aNewDeviceSessionCannotImplicitlyReuseThePreviousSensorSequenceHistory()throws Exception{
        var source=execution();var original=seal(source);var previous=source.permissions().getFirst();var g=previous.generation();
        routes.fence(g.id(),"REPLACED");routes.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));
        var session=devices.openSession(previous.route().sourceDeviceId(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
        var current=routes.prepare(previous.route().id(),UUID.randomUUID(),new RouteGeneration.Actor(session.id(),session.epoch()),g.consumer(),g.brokerDigest(),120);
        current=routes.activate(new RouteGeneration.BrokerReceipt(current.id(),current.brokerDigest(),current.policyDigest()));
        var next=new Execution(source.run(),source.task(),source.attempt(),source.pod(),List.of(new Permission(previous.route(),current),source.permissions().getLast()));
        var reply=send(next,"handover",handoverBody(next));assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.body().get("code")).isEqualTo("DEVICE_STREAM_HANDOVER_REQUIRED");
        assertThat(checkpoints.latest(source.task()).orElseThrow().id()).isEqualTo(original.id());
    }
    @Test @SuppressWarnings("unchecked") void databaseHandoverConstraintsKeepStateCursorsLimitsAndCurrentBindingsImmutable()throws Exception{
        var source=execution(true);var original=seal(source);var next=transition(source,true);
        var sourceFile=directory.resolve("sealed.json");storage.downloadFile(original.artifact(),sourceFile);
        var transformed=StreamCheckpointDocument.rebind(sourceFile,original,source.permissions(),next.permissions(),next.attempt(),directory.resolve("rebound.json"));
        var r=runtimes.byAttempt(next.attempt()).orElseThrow();var tx=new TransactionTemplate(transactions);
        for(String mutation:List.of("missing-marker","serial","state","cursor","limits","binding","valid")){
            var summary=(Map<String,Object>)json.decode(transformed.summary().json());var q=transformed.request();
            if(mutation.equals("state"))summary.put("stateSha256","f".repeat(64));
            if(mutation.equals("cursor"))((List<Map<String,Object>>)summary.get("routes")).getFirst().put("ended",true);
            var manifest=(Map<String,Object>)summary.get("manifest");
            if(mutation.equals("limits"))((Map<String,Object>)manifest.get("limits")).put("max_frames",4096);
            if(mutation.equals("binding"))((List<Map<String,Object>>)manifest.get("inputs")).getFirst().put("generation",999);
            if(mutation.equals("serial"))q=new StreamCheckpoint.Request(q.previousId(),q.serial()+1,q.sha256(),q.bytes(),q.executionSha256(),q.generationIds());
            var fixture=new StreamCheckpoint(UUID.randomUUID(),r.runId(),r.taskId(),r.attemptId(),r.id(),r.epoch(),next.pod().podUid(),original.serviceProfileVersionId(),q,
                original.revision(),json.canonical(summary),new VerifiedArtifact("fixture-only",q.content(r.taskId(),r.attemptId()).objectKey(),"database-constraint-fixture",q.sha256(),q.bytes(),StreamCheckpoint.MEDIA_TYPE),
                Instant.now(),mutation.equals("missing-marker")?null:original.id());
            tx.executeWithoutResult(s->{s.setRollbackOnly();
                if(mutation.equals("valid"))checkpoints.insert(fixture);
                else assertThatThrownBy(()->checkpoints.insert(fixture)).as(mutation).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            });
            assertThat(checkpoints.latest(source.task()).orElseThrow().id()).isEqualTo(original.id());
        }
        assertThat(send(next,"handover",handoverBody(next)).status()).isEqualTo(200);
    }
    @Test void cancellationDuringHandoverStorageReadCannotSealRewrittenSnapshot()throws Exception{
        var source=execution();var original=seal(source);var next=transition(source,false);
        var read=new CountDownLatch(1);var release=new CountDownLatch(1);
        ArtifactFiles boundary=new ArtifactFiles(){
            public void downloadFile(VerifiedArtifact a,Path p){storage.downloadFile(a,p);read.countDown();try{
                if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Fixture wait timed out");
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Fixture interrupted");}}
            public String uploadFile(ArtifactContent c,Path p){return storage.uploadFile(c,p);}
        };
        var wrapped=new StreamCheckpointService(checkpoints,runtimes,executions,definitions,routes,lifecycle,storage,boundary,Clock.systemUTC(),transactions,routeStore,streamExecutions);
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->wrapped.handover(principal(next),json.canonical(handoverBody(next))));
            try{assertThat(read.await(5,TimeUnit.SECONDS)).isTrue();runs.cancelRun(next.run(),"{}");}finally{release.countDown();}
            assertThatThrownBy(()->pending.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ControlPlaneException.class);
        }
        assertThat(checkpoints.latest(source.task()).orElseThrow().id()).isEqualTo(original.id());
    }
    @Test void actualHttpSdkS3AndDbCommitReplayLatestAndServerRecreation()throws Exception {
        var e=execution();byte[] bytes=snapshot(e);var q=request(e,bytes,null);String version=upload(e,q,bytes);
        var committed=send(e,"commit",commitBody(e,q,version));assertThat(committed.status()).isEqualTo(201);
        var receipt=(Map<?,?>)committed.body().get("checkpoint");assertThat(receipt.get("sha256")).isEqualTo(sha(bytes));
        assertThat(send(e,"commit",commitBody(e,q,version)).status()).isEqualTo(200);
        var stored=checkpoints.latest(e.task()).orElseThrow();assertThat(stored.artifact().versionId()).isEqualTo(version);
        var query=body(e);query.put("generationIds",q.get("generationIds"));var latest=send(e,"latest",query);assertThat(latest.status()).isEqualTo(200);
        var grant=(Map<?,?>)latest.body().get("download");
        try(var client=HttpClient.newHttpClient()){
            var downloaded=client.send(HttpRequest.newBuilder(URI.create((String)grant.get("url"))).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);assertThat(downloaded.body()).isEqualTo(bytes);
        }
        var recreated=new StreamCheckpointService(checkpoints,runtimes,executions,definitions,routes,lifecycle,storage,storage,Clock.systemUTC(),transactions,routeStore,streamExecutions);
        assertThat(recreated.commit(principal(e),commitBody(e,q,version)).value().id()).isEqualTo(stored.id());
        assertThat(checkpoints.latest(e.task()).orElseThrow().summaryJson()).doesNotContain("\"stateBase64\"","\"frames\"");
    }
    @Test void actualPythonClientVerifiesAuthenticatedReceiptAfterRealS3Upload()throws Exception {
        var e=execution();byte[] bytes=snapshot(e);var q=request(e,bytes,null);
        var folder=Files.createTempDirectory(directory,"client-");Files.write(folder.resolve("snapshot.json"),bytes);
        var credential=folder.resolve("claim");Files.writeString(credential,tokens.issue(runtimes.byAttempt(e.attempt()).orElseThrow()));
        Files.setPosixFilePermissions(credential,PosixFilePermissions.fromString("rw-------"));
        var pod=folder.resolve("pod");Files.writeString(pod,"checkpoint-pod-proof");Files.setPosixFilePermissions(pod,PosixFilePermissions.fromString("rw-------"));
        Files.writeString(folder.resolve("request.json"),json.canonical(Map.of("origin","http://127.0.0.1:"+port,"runId",e.run().toString(),
            "attemptId",e.attempt().toString(),"podUid",e.pod().podUid().toString(),"generationIds",q.get("generationIds"))));
        var child=new ProcessBuilder("python3",Path.of("src/test/fixtures/stream_checkpoint_client_probe.py").toAbsolutePath().toString(),folder.toString()).redirectErrorStream(true).start();
        try{
            assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();
            assertThat(new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip()).isEqualTo("STREAM_CHECKPOINT_CLIENT_PASS");
            var receipt=(Map<?,?>)json.decode(Files.readString(folder.resolve("receipt.json")));
            assertThat(receipt.get("id")).isEqualTo(checkpoints.latest(e.task()).orElseThrow().id().toString());
        }finally{
            if(child.isAlive()){child.destroyForcibly();child.waitFor();}
            if(Files.exists(folder.resolve("version.txt")))STORAGE.versions.add(Map.entry(key(e,q),Files.readString(folder.resolve("version.txt"))));
        }
    }
    @Test void actualSdkAdvanceRejectsStaleReceiptAndKeepsFirstVerifiedVersion()throws Exception {
        var e=execution();byte[] bytes=snapshot(e);var q=request(e,bytes,null);String version=upload(e,q,bytes);
        String duplicate=upload(e,q,bytes);
        assertThat(send(e,"commit",commitBody(e,q,version)).status()).isEqualTo(201);
        var replay=send(e,"commit",commitBody(e,q,duplicate));assertThat(replay.status()).isEqualTo(200);
        assertThat(((Map<?,?>)replay.body().get("checkpoint")).get("versionId")).isEqualTo(version);
        var first=checkpoints.latest(e.task()).orElseThrow();
        byte[] advanced=snapshot(e,true);var next=request(e,advanced,first.id());String nextVersion=upload(e,next,advanced);
        assertThat(send(e,"commit",commitBody(e,next,nextVersion)).status()).isEqualTo(201);
        var latest=checkpoints.latest(e.task()).orElseThrow();assertThat(latest.revision()).isEqualTo(2);
        assertThat(latest.request().previousId()).isEqualTo(first.id());
        assertThat(latest.request().serial()).isGreaterThan(first.request().serial());
        assertThat(send(e,"commit",commitBody(e,q,version)).status()).isEqualTo(409);
        var stale=body(e);stale.put("checkpoint",q);assertThat(send(e,"uploads",stale).status()).isEqualTo(409);
        assertThat(checkpoints.latest(e.task()).orElseThrow().id()).isEqualTo(latest.id());
    }
    @Test void twoConcurrentHttpCommitsSealOneImmutableCheckpoint()throws Exception {
        var e=execution();byte[] bytes=snapshot(e);var q=request(e,bytes,null);String version=upload(e,q,bytes);String body=commitBody(e,q,version);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->send(e,"commit",body).status());var b=pool.submit(()->send(e,"commit",body).status());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,201);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.stream_checkpoint WHERE task_id=?",Integer.class,e.task())).isEqualTo(1);
        var id=checkpoints.latest(e.task()).orElseThrow().id();
        // Include the referencing table so PostgreSQL reaches the immutable-history trigger,
        // rather than its unsupported lone-table TRUNCATE guard (SQLSTATE 0A000).
        for(String sql:List.of("UPDATE edgeai.stream_checkpoint SET serial=serial+1 WHERE id='"+id+"'","DELETE FROM edgeai.stream_checkpoint WHERE id='"+id+"'","TRUNCATE edgeai.stream_checkpoint,edgeai.stream_task_completion"))
            new TransactionTemplate(transactions).executeWithoutResult(s->{s.setRollbackOnly();assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);});
        assertThat(checkpoints.latest(e.task()).orElseThrow().id()).isEqualTo(id);
    }
    @Test @SuppressWarnings("unchecked") void oldCursorsWrongStateRevisionAndChangedExecutionCannotAdvanceLatest()throws Exception {
        var e=execution();byte[] bytes=snapshot(e);var q=request(e,bytes,null);String version=upload(e,q,bytes);
        assertThat(send(e,"commit",commitBody(e,q,version)).status()).isEqualTo(201);var first=checkpoints.latest(e.task()).orElseThrow();
        var document=(Map<String,Object>)json.decode(new String(bytes,StandardCharsets.UTF_8));document.put("serial",4);document.put("stateBase64",Base64.getEncoder().encodeToString("14".getBytes(StandardCharsets.US_ASCII)));
        byte[] changed=json.canonical(document).getBytes(StandardCharsets.UTF_8);var next=request(e,changed,first.id());String nextVersion=upload(e,next,changed);
        assertThat(send(e,"commit",commitBody(e,next,nextVersion)).status()).isEqualTo(409);
        document.put("revision",2);for(var row:(List<Map<String,Object>>)document.get("routes")){row.put("received",0);row.put("committed",0);}
        byte[] regressed=json.canonical(document).getBytes(StandardCharsets.UTF_8);var back=request(e,regressed,first.id());String backVersion=upload(e,back,regressed);
        assertThat(send(e,"commit",commitBody(e,back,backVersion)).status()).isEqualTo(409);
        var wrong=new TreeMap<>(next);wrong.put("executionSha256","c".repeat(64));var body=body(e);body.put("checkpoint",wrong);
        assertThat(send(e,"uploads",body).status()).isEqualTo(409);assertThat(checkpoints.latest(e.task()).orElseThrow().id()).isEqualTo(first.id());
    }
    @Test void forgedS3BytesAndForeignOrIncompleteAuthorityCannotCreateCheckpoint()throws Exception {
        var e=execution();byte[] bytes=snapshot(e);var q=request(e,bytes,null);String version=upload(e,q,bytes);
        byte[] corrupt=bytes.clone();corrupt[corrupt.length/2]^=1;
        String wrong=STORAGE.admin.putObject(PutObjectArgs.builder().bucket(STORAGE.bucket).object(key(e,q)).data(corrupt,corrupt.length)
            .contentType(StreamCheckpoint.MEDIA_TYPE).userMetadata(Map.of("sha256",(String)q.get("sha256"))).build()).versionId();STORAGE.versions.add(Map.entry(key(e,q),wrong));
        assertThat(send(e,"commit",commitBody(e,q,wrong)).status()).isEqualTo(400);
        assertThat(send(e,"commit",commitBody(e,q,version),"wrong",true).status()).isEqualTo(401);
        assertThat(send(e,"commit",commitBody(e,q,version),"checkpoint-pod-proof",false).status()).isEqualTo(401);
        var partial=new TreeMap<>(q);partial.put("generationIds",List.of(e.permissions().getFirst().generation().id().toString()));
        assertThat(send(e,"commit",commitBody(e,partial,version)).status()).isEqualTo(409);
        var other=execution();assertThat(send(other,"commit",commitBody(other,q,version)).status()).isEqualTo(409);
        assertThat(checkpoints.latest(e.task())).isEmpty();assertThat(checkpoints.latest(other.task())).isEmpty();
    }
    @Test void cancellationDuringRealS3ReadDoesNotHoldRunLockOrSealLateCheckpoint()throws Exception {
        var e=execution();byte[] bytes=snapshot(e);var q=request(e,bytes,null);String version=upload(e,q,bytes);
        var read=new CountDownLatch(1);var release=new CountDownLatch(1);
        ArtifactStore boundary=new ArtifactStore(){
            public ArtifactGrant upload(ArtifactContent c){return storage.upload(c);}public ArtifactGrant download(VerifiedArtifact a){return storage.download(a);}
            public VerifiedArtifact verify(ArtifactContent c,String v){var verified=storage.verify(c,v);read.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Fixture wait timed out");}catch(InterruptedException x){Thread.currentThread().interrupt();throw new IllegalStateException("Fixture interrupted");}return verified;}
        };
        var wrapped=new StreamCheckpointService(checkpoints,runtimes,executions,definitions,routes,lifecycle,boundary,storage,Clock.systemUTC(),transactions,routeStore,streamExecutions);
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->wrapped.commit(principal(e),commitBody(e,q,version)));
            try{assertThat(read.await(5,TimeUnit.SECONDS)).isTrue();runs.cancelRun(e.run(),"{}");}finally{release.countDown();}
            assertThatThrownBy(()->pending.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ControlPlaneException.class);
        }
        assertThat(checkpoints.latest(e.task())).isEmpty();
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String required(String key){String value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalStateException("Real checkpoint test requires "+key);return value;}
    private static final class Storage implements AutoCloseable {
        final UUID id=UUID.randomUUID();final String bucket="edgeai-checkpoint-"+id;final Path root,key;final MinioClient admin;
        final List<Map.Entry<String,String>> versions=new CopyOnWriteArrayList<>();
        Storage(){try{
            root=Files.createTempDirectory("edgeai-checkpoint-api-");key=root.resolve("runner.key");byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
            Files.writeString(key,HexFormat.of().formatHex(secret));Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));
            admin=MinioClient.builder().endpoint(required("EDGEAI_STORAGE_URL")).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();
            admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
                .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());
        }catch(Exception e){throw new IllegalStateException("Real checkpoint storage fixture unavailable; private details suppressed");}}
        public void close()throws Exception{try{
            var owned=new ArrayList<Map.Entry<String,String>>();
            for(var object:admin.listObjects(ListObjectsArgs.builder().bucket(bucket).includeVersions(true).recursive(true).build())){
                var value=object.get();assertThat(value.isDir()).isFalse();assertThat(value.versionId()).isNotBlank();
                owned.add(Map.entry(value.objectName(),value.versionId()));
            }
            for(var value:owned)admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(value.getKey()).versionId(value.getValue()).build());
            assertThat(admin.listObjects(ListObjectsArgs.builder().bucket(bucket).includeVersions(true).recursive(true).build()).iterator().hasNext()).isFalse();
            admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());}finally{admin.close();Files.deleteIfExists(key);Files.deleteIfExists(root);}}
    }
}
