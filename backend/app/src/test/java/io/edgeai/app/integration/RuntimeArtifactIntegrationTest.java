package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.RuntimePod;
import io.edgeai.domain.storage.*;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL + real MinIO. Pod/Job identities are fixtures; this is not scheduler/Runner E2E. */
@SpringBootTest
class RuntimeArtifactIntegrationTest {
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired ExecutionService executions;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    private MinioClient admin;
    private S3ArtifactStore storage;
    private String bucket;
    private boolean created;
    private final List<Map.Entry<String,String>> versions=new ArrayList<>();
    private final JsonDocuments json=new JsonDocuments();
    private record Execution(UUID task,UUID child,UUID attempt,RuntimePod pod) {}
    @BeforeEach void startStorage() {
        bucket="edgeai-result-test-"+UUID.randomUUID();String endpoint=required("EDGEAI_STORAGE_URL");
        admin=MinioClient.builder().endpoint(endpoint).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();
        checked(()->{admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());return null;});created=true;
        checked(()->{admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
            .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());return null;});
        storage=new S3ArtifactStore(endpoint,endpoint,required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD"),bucket,Clock.systemUTC());
    }
    @AfterEach void cleanup() {
        try {
            if(created) {
                for(var v:versions)checked(()->{admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(v.getKey()).versionId(v.getValue()).build());return null;});
                checked(()->{admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());return null;});
            }
        } finally {
            try { if(storage!=null)storage.close(); }
            finally { if(admin!=null)checked(()->{admin.close();return null;}); }
        }
    }
    @SuppressWarnings("unchecked") private Execution execution() throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","real-result-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var w=workflows.create(json.canonical(Map.of("key","real-result-"+UUID.randomUUID(),"displayName","Actual S3 result test"))).value();
        var tasks=List.of("root","child").stream().map(k->Map.of("key",k,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())).toList();
        var v=workflows.publish(w.id(),json.canonical(Map.of("version","1.0.0","tasks",tasks,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value();
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(Map.of("workflowVersionId",v.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of()))).value();
        var runTasks=executions.detail(run.id()).tasks();UUID task=runTasks.stream().filter(t->t.key().equals("root")).findFirst().orElseThrow().id();
        UUID child=runTasks.stream().filter(t->t.key().equals("child")).findFirst().orElseThrow().id();UUID attempt=executions.taskDetail(task).attempts().getFirst().id();
        lifecycle.plan(attempt,"test-"+UUID.randomUUID());var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(attempt,pod.jobUid());lifecycle.claim(attempt,1,pod);return new Execution(task,child,attempt,pod);
    }
    private ArtifactContent content(Execution e,byte[] bytes) {
        String sha=checked(()->HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return new ArtifactContent(e.task(),e.attempt(),"output",sha,bytes.length,"application/json");
    }
    private String upload(ArtifactContent content,byte[] bytes) {
        var grant=storage.upload(content);var request=HttpRequest.newBuilder(grant.url()).timeout(Duration.ofSeconds(15));grant.headers().forEach(request::header);
        return checked(()->{
            try(var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
                var response=client.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding());
                if(response.statusCode()!=200)throw new IllegalStateException("S3 fixture upload rejected");
                String version=response.headers().firstValue("x-amz-version-id").orElseThrow();versions.add(Map.entry(content.objectKey(),version));return version;
            }
        });
    }
    private ResultManifest manifest(ArtifactContent c,String version) {
        return new ResultManifest(List.of(new ResultManifest.Output(c.port(),c.bytes(),c.sha256(),c.mediaType(),version)));
    }
    @Test void realS3BytesBecomeAnImmutableDbResultAndAChildInput() throws Exception {
        var e=execution();byte[] bytes="{\"score\":3}".getBytes(java.nio.charset.StandardCharsets.UTF_8);var content=content(e,bytes);
        var manifest=manifest(content,upload(content,bytes));var commits=new ArtifactCommitService(lifecycle,storage);
        var result=commits.commit(e.attempt(),1,e.pod().podUid(),manifest);assertThat(result.created()).isTrue();
        assertThat(runtimes.result(e.task()).orElseThrow().outputs().getFirst().artifact().sha256()).isEqualTo(content.sha256());
        assertThat(commits.commit(e.attempt(),1,e.pod().podUid(),manifest).value().id()).isEqualTo(result.value().id());
        assertThat(executions.taskDetail(e.child()).task().state()).isEqualTo("READY");
        UUID child=executions.taskDetail(e.child()).attempts().getFirst().id();lifecycle.plan(child,"test-"+UUID.randomUUID());
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");lifecycle.submitted(child,pod.jobUid());
        var input=lifecycle.claim(child,1,pod).inputs().getFirst().artifact();
        var grant=storage.download(input);
        byte[] downloaded=checked(()->{try(var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response=client.send(HttpRequest.newBuilder(grant.url()).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200)throw new IllegalStateException("S3 fixture download rejected");return response.body();
        }});
        assertThat(downloaded).isEqualTo(bytes);
        var childExecution=new Execution(e.child(),e.child(),child,pod);
        byte[] childBytes="{\"score\":6}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var childContent=content(childExecution,childBytes);
        commits.commit(child,1,pod.podUid(),manifest(childContent,upload(childContent,childBytes)));
        var run=executions.detail(executions.taskDetail(e.task()).task().runId()).run();
        assertThat(run.state()).isEqualTo("SUCCEEDED");
        assertThat(runtimes.byAttempt(child).orElseThrow().desiredState()).isEqualTo("STOPPED");
    }
    @Test void forgedMetadataAndCancellationAfterActualReadCannotCommit() throws Exception {
        var e=execution();byte[] bytes="{\"score\":3}".getBytes(java.nio.charset.StandardCharsets.UTF_8);var content=content(e,bytes);
        byte[] corrupted=bytes.clone();corrupted[0]^=1;
        String bad=checked(()->admin.putObject(PutObjectArgs.builder().bucket(bucket).object(content.objectKey()).data(corrupted,corrupted.length)
            .contentType(content.mediaType()).userMetadata(Map.of("sha256",content.sha256())).build()).versionId());
        versions.add(Map.entry(content.objectKey(),bad));
        var commits=new ArtifactCommitService(lifecycle,storage);
        assertThatThrownBy(()->commits.commit(e.attempt(),1,e.pod().podUid(),manifest(content,bad))).isInstanceOf(ArtifactVerificationException.class);
        assertThat(runtimes.result(e.task())).isEmpty();assertThat(executions.taskDetail(e.child()).task().state()).isEqualTo("WAITING");
        var correct=manifest(content,upload(content,bytes));var read=new CountDownLatch(1);var release=new CountDownLatch(1);
        ArtifactStore boundary=new ArtifactStore() {
            public ArtifactGrant upload(ArtifactContent c) { return storage.upload(c); }
            public ArtifactGrant download(VerifiedArtifact a) { return storage.download(a); }
            public VerifiedArtifact verify(ArtifactContent c,String v) {
                var verified=storage.verify(c,v);read.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Fixture deadline"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException("Fixture interrupted"); }
                return verified;
            }
        };
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->new ArtifactCommitService(lifecycle,boundary).commit(e.attempt(),1,e.pod().podUid(),correct));
            try { assertThat(read.await(5,TimeUnit.SECONDS)).isTrue();executions.cancelTask(e.task(),"{}"); }
            finally { release.countDown(); }
            assertThatThrownBy(()->pending.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ControlPlaneException.class);
        }
        assertThat(runtimes.result(e.task())).isEmpty();assertThat(executions.taskDetail(e.child()).task().state()).isEqualTo("SKIPPED");
    }
    private static String required(String key) { String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Required: "+key);return value; }
    private interface Checked<T> { T run() throws Exception; }
    private static <T> T checked(Checked<T> action) {
        try { return action.run(); }catch(Exception error) { throw new IllegalStateException("S3 fixture failure: "+error.getClass().getSimpleName()); }
    }
}
