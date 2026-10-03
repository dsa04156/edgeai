package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
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
    @SuppressWarnings("unchecked") private Execution execution()throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("a",Map.of("mediaType","application/json","maxBytes",4096,"required",false),"b",Map.of("mediaType","application/json","maxBytes",4096,"required",false)));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","checkpoint-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","checkpoint-"+UUID.randomUUID(),"displayName","Checkpoint fixture"))).value();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",List.of(Map.of("key","sum","serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())),"dependencies",List.of()))).value();
        var run=runs.create(UUID.randomUUID().toString(),json.canonical(Map.of("workflowVersionId",version.id().toString(),"parameters",Map.of(),"execution",Map.of("mode","AUTO")))).value();runIds.add(run.id());
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
    private byte[] snapshot(Execution e,boolean advance)throws Exception {
        var folder=Files.createTempDirectory(directory,"snapshot-");
        var bindings=e.permissions().stream().map(p->Map.of("routeId",p.route().id().toString(),"generation",p.generation().generation(),"producer",
            Map.of("kind","DEVICE_SESSION","deviceId",p.route().sourceDeviceId().toString(),"sessionId",p.generation().producer().id().toString(),"epoch",p.generation().producer().epoch()))).toList();
        Files.writeString(folder.resolve("request.json"),json.canonical(Map.of("bindings",bindings,"advance",advance)));
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
    private Map<String,Object> body(Execution e){return new TreeMap<>(Map.of("epoch",1,"podUid",e.pod().podUid().toString()));}
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
    private RunnerPrincipal principal(Execution e){return new RunnerPrincipal(e.attempt(),1,e.pod());}
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
        var recreated=new StreamCheckpointService(checkpoints,runtimes,executions,definitions,routes,lifecycle,storage,storage,Clock.systemUTC(),transactions);
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
        for(String sql:List.of("UPDATE edgeai.stream_checkpoint SET serial=serial+1 WHERE id='"+id+"'","DELETE FROM edgeai.stream_checkpoint WHERE id='"+id+"'","TRUNCATE edgeai.stream_checkpoint"))
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
        var wrapped=new StreamCheckpointService(checkpoints,runtimes,executions,definitions,routes,lifecycle,boundary,storage,Clock.systemUTC(),transactions);
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
        public void close()throws Exception{try{for(var v:versions)admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(v.getKey()).versionId(v.getValue()).build());
            admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());}finally{admin.close();Files.deleteIfExists(key);Files.deleteIfExists(root);}}
    }
}
