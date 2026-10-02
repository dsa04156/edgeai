package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.remote.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

/** Actual public HTTP/security, PostgreSQL, remote Python/SQLite and MinIO. Worker cycles are driven by this test. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.remote.enabled=true"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RemoteWorkerIntegrationTest {
    static final JsonDocuments JSON=new JsonDocuments();
    static final String SCOPE="remote-worker-"+UUID.randomUUID(),BUCKET="edgeai-worker-"+UUID.randomUUID(),PASSWORD=UUID.randomUUID().toString();
    static Path root,token,key,ready;static Process process;static String origin;static MinioClient admin;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry){
        try {
            root=Files.createTempDirectory("edgeai-remote-worker-");token=root.resolve("token");key=root.resolve("key");ready=root.resolve("ready");
            Files.writeString(token,UUID.randomUUID().toString().replace("-",""));Files.writeString(key,UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));
            for(var file:List.of(token,key))Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            startProvider(0);
            admin=MinioClient.builder().endpoint(env("EDGEAI_STORAGE_URL")).credentials(env("EDGEAI_MINIO_USER"),env("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();
            storage(()->{admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());return null;});
        }catch(Exception e){stopProvider();throw new IllegalStateException("Cannot start real Remote worker fixture");}
        registry.add("edgeai.runtime.key-file",key::toString);registry.add("edgeai.runtime.namespace",()->SCOPE);
        registry.add("edgeai.remote.url",()->origin);registry.add("edgeai.remote.token-file",token::toString);registry.add("edgeai.remote.timeout-seconds",()->3);
        registry.add("edgeai.storage.endpoint",()->env("EDGEAI_STORAGE_URL"));registry.add("edgeai.storage.runner-endpoint",()->"http://127.0.0.1:1"); // Must not use the Runner network URL.
        registry.add("edgeai.storage.bucket",()->BUCKET);registry.add("spring.security.user.name",()->"remote-worker-test");registry.add("spring.security.user.password",()->PASSWORD);
    }
    static String env(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalStateException("Missing storage fixture setting");return value;}
    static void startProvider(int port) throws Exception {
        Files.deleteIfExists(ready);process=new ProcessBuilder("python3","../../simulator/remote_server.py","--state-dir",root.resolve("provider").toString(),"--token-file",token.toString(),"--ready-file",ready.toString(),"--port",Integer.toString(port))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(process.isAlive() && !Files.exists(ready) && System.nanoTime()<end)Thread.sleep(20);
        if(!Files.exists(ready))throw new IllegalStateException("Provider did not become ready");
        origin="http://127.0.0.1:"+((Map<?,?>)JSON.decode(Files.readString(ready))).get("port");
    }
    static void stopProvider(){if(process!=null && process.isAlive())try{process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @AfterAll static void cleanup() throws Exception {
        stopProvider();
        try{if(admin!=null)storage(()->{for(var item:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).includeVersions(true).build())){var value=item.get();admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(value.objectName()).versionId(value.versionId()).build());}admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());admin.close();return null;});}
        finally{if(root!=null)try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
    static <T>T storage(Callable<T> call){try{return call.call();}catch(Exception e){throw new IllegalStateException("Actual S3 operation failed");}}
    @LocalServerPort int port;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired ExecutionService executions;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired OffloadService offloads;
    @Autowired RemoteProvider provider;
    @Autowired RemoteRepository remotes;
    @Autowired S3ArtifactStore artifacts;
    @Autowired ArtifactCommitService commits;
    @Autowired RuntimeSettings settings;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    HttpClient client;String csrf;
    @BeforeEach void login() throws Exception {
        client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        csrf=(String)call("GET","/api/v1/csrf",null,null,200).get("token");
    }
    @AfterEach void logout(){client.close();}
    Map<?,?> call(String method,String path,String body,String idempotency,int expected) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
            .header("Authorization","Basic "+Base64.getEncoder().encodeToString(("remote-worker-test:"+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if(body!=null)request.header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf);
        if(idempotency!=null)request.header("Idempotency-Key",idempotency);
        var response=client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Public HTTP status for %s",path).isEqualTo(expected);
        return (Map<?,?>)JSON.decode(response.body());
    }
    record Fixture(UUID run,UUID task,UUID child,UUID attempt,String request,String key){}
    Fixture create(boolean remote,boolean child,int delay,boolean retry) throws Exception {
        var spec=new HashMap<Object,Object>((Map<?,?>)JSON.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));spec.put("recovery",Map.of("mode","RESTART"));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,JSON.canonical(Map.of("key","worker-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(JSON.canonical(Map.of("key","worker-"+UUID.randomUUID(),"displayName","Real Remote worker test"))).value();
        var tasks=(child?List.of("root","child"):List.of("root")).stream().map(k->Map.of("key",k,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of("features",k.equals("root")?List.of(2,1):List.of(99,99),"weights",List.of(2,3),"bias",1,"simulationDelayMillis",delay))).toList();
        var version=workflows.publish(workflow.id(),JSON.canonical(Map.of("version","1.0.0","tasks",tasks,"dependencies",child?List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH")):List.of()))).value();
        var body=new TreeMap<String,Object>(Map.of("workflowVersionId",version.id().toString(),"execution",remote?Map.of("mode","REMOTE","providerKey","reference"):Map.of("mode","AUTO"),"parameters",Map.of()));
        if(retry)body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",600,"retryOn",List.of("RUNTIME_LOST")));
        String text=JSON.canonical(body),key=UUID.randomUUID().toString();var response=call("POST","/api/v1/workflow-runs",text,key,201);UUID run=UUID.fromString((String)response.get("id"));
        var rows=executions.detail(run).tasks();UUID task=rows.stream().filter(t->t.key().equals("root")).findFirst().orElseThrow().id(),next=rows.stream().filter(t->t.key().equals("child")).map(t->t.id()).findFirst().orElse(null);
        return new Fixture(run,task,next,executions.taskDetail(task).attempts().getFirst().id(),text,key);
    }
    RemoteWorker worker(){return new RemoteWorker(runtimes,lifecycle,commits,artifacts,provider,settings,clock);}
    void until(BooleanSupplier done) throws Exception {long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(!done.getAsBoolean() && System.nanoTime()<end){worker().commands();worker().reconcile();for(var task:lifecycle.dueRetries(SCOPE))lifecycle.retryTask(task);for(var op:offloads.active(SCOPE))offloads.advance(op);Thread.sleep(30);}assertThat(done.getAsBoolean()).isTrue();}
    RemoteStatus providerStatus(UUID attempt){var d=lifecycle.remoteDispatch(attempt);return provider.gateway(d.allocation().target()).inspect(d.work().identity()).orElseThrow();}
    void finishKubernetesCreate(UUID runtime){
        // No Kubernetes gateway is invoked in this fixture. Drain only this class's synthetic K8s commands.
        boolean found=false;for(int i=0;i<100;i++){var next=runtimes.leaseCommand(SCOPE,UUID.randomUUID(),clock.instant(),Duration.ofSeconds(30));if(next.isEmpty())break;var leased=next.get();if(leased.runtimeId().equals(runtime) && leased.kind().equals("CREATE"))found=true;assertThat(runtimes.finishCommand(leased.id(),leased.leaseOwner(),clock.instant())).isTrue();}assertThat(found).isTrue();
    }

    @Test void publicRequestAndFreshWorkerInstancesExecuteRealBatchWithPinnedInput() throws Exception {
        var f=create(true,true,0,false);var binding=executions.detail(f.run()).run().remoteTarget();
        assertThat(call("POST","/api/v1/workflow-runs",f.request(),f.key(),200).get("id")).isEqualTo(f.run().toString());
        until(()->executions.detail(f.run()).run().state().equals("SUCCEEDED"));
        for(UUID task:List.of(f.task(),f.child())) {
            var result=runtimes.result(task).orElseThrow();assertThat(result.producerPodUid()).isNull();assertThat(result.remoteAllocationId()).isNotNull();
            var a=executions.taskDetail(task).attempts().getFirst();assertThat(a.remoteTarget()).isEqualTo(binding);
            Path file=root.resolve(UUID.randomUUID()+".json");artifacts.downloadFile(result.outputs().getFirst().artifact(),file);assertThat(Files.readString(file)).contains("8.0");
            var view=call("GET","/api/v1/tasks/"+task+"/results",null,null,200);assertThat(JSON.canonical(view)).contains("SYNTHETIC",result.remoteAllocationId().toString());
        }
        assertThat(lifecycle.remoteDispatch(executions.taskDetail(f.child()).attempts().getFirst().id()).inputs().getFirst().artifact().versionId()).isEqualTo(runtimes.result(f.task()).orElseThrow().outputs().getFirst().artifact().versionId());
        until(()->runtimes.activeRemote(SCOPE,1000).isEmpty());
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.workflow_run SET remote_provider_key='changed' WHERE id=?",f.run())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void springSchedulerRunsRemoteBatchWithoutDirectWorkerCalls() throws Exception {
        var f=create(true,true,0,false);
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
            context.registerBean("taskScheduler",org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class,()->{var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();scheduler.setPoolSize(2);return scheduler;});
            context.registerBean(RemoteWorker.class,this::worker);context.refresh();
            long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(!executions.detail(f.run()).run().state().equals("SUCCEEDED") && System.nanoTime()<end)Thread.sleep(50);
            assertThat(executions.detail(f.run()).run().state()).isEqualTo("SUCCEEDED");
            assertThat(runtimes.result(f.child()).orElseThrow().remoteAllocationId()).isNotNull();
        }
        until(()->runtimes.activeRemote(SCOPE,1000).isEmpty());
    }
    @Test void directStorageFilesValidatePinnedBytesAndPreserveExistingDestinations() throws Exception {
        var f=create(true,false,0,false);until(()->executions.detail(f.run()).run().state().equals("SUCCEEDED"));
        var artifact=runtimes.result(f.task()).orElseThrow().outputs().getFirst().artifact();
        Path destination=root.resolve(UUID.randomUUID()+".json");Files.writeString(destination,"preserve-existing-file");
        assertThatThrownBy(()->artifacts.downloadFile(artifact,destination)).isInstanceOf(ArtifactStoreUnavailableException.class);assertThat(Files.readString(destination)).isEqualTo("preserve-existing-file");
        var bad=new VerifiedArtifact(artifact.bucket(),artifact.objectKey(),artifact.versionId(),"a".repeat(64),artifact.bytes(),artifact.mediaType());Path absent=root.resolve(UUID.randomUUID()+".json");
        assertThatThrownBy(()->artifacts.downloadFile(bad,absent)).isInstanceOf(ArtifactVerificationException.class);assertThat(Files.exists(absent)).isFalse();
        Path input=root.resolve(UUID.randomUUID()+".json");artifacts.downloadFile(artifact,input);Path link=root.resolve(UUID.randomUUID()+".json");Files.createSymbolicLink(link,input);
        var content=new ArtifactContent(f.task(),f.attempt(),"output",artifact.sha256(),artifact.bytes(),artifact.mediaType());
        assertThatThrownBy(()->artifacts.uploadFile(content,link)).isInstanceOf(ArtifactVerificationException.class);
        byte[] changed=Files.readAllBytes(input);changed[0]^=1;Files.write(input,changed);assertThatThrownBy(()->artifacts.uploadFile(content,input)).isInstanceOf(ArtifactVerificationException.class);
    }
    @Test void competingWorkersComputeOnceAndCommitOnlyOneVerifiedResult() throws Exception {
        var f=create(true,false,0,false);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var entered=new CountDownLatch(1);var commands=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++)commands.add(executor.submit(()->{entered.await();worker().commands();return null;}));entered.countDown();for(var pending:commands)pending.get(10,TimeUnit.SECONDS);
            long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(providerStatus(f.attempt()).state()!=RemoteStatus.State.SUCCEEDED && System.nanoTime()<end)Thread.sleep(20);
            var readers=new CountDownLatch(1);var results=new ArrayList<Future<?>>();for(int i=0;i<8;i++)results.add(executor.submit(()->{readers.await();worker().reconcile();return null;}));readers.countDown();for(var pending:results)pending.get(10,TimeUnit.SECONDS);
        }
        assertThat(executions.detail(f.run()).run().state()).isEqualTo("SUCCEEDED");assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.task_result WHERE task_id=?",Integer.class,f.task())).isEqualTo(1);
        UUID allocation=runtimes.byAttempt(f.attempt()).orElseThrow().remoteAllocationId();
        var query=new ProcessBuilder("python3","-c","import sqlite3,sys; db=sqlite3.connect(sys.argv[1]); print(db.execute('SELECT executions FROM allocations WHERE id=?',(sys.argv[2],)).fetchone()[0])",root.resolve("provider/allocations.sqlite").toString(),allocation.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{assertThat(query.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(query.exitValue()).isZero();assertThat(new String(query.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip()).isEqualTo("1");}finally{query.destroyForcibly();}
    }
    @Test void publicCancelBeforeDispatchCreatesTombstoneWithoutStartingWork() throws Exception {
        var f=create(true,true,0,false);call("POST","/api/v1/workflow-runs/"+f.run()+"/cancel","{}",null,200);
        until(()->executions.detail(f.run()).run().state().equals("CANCELLED"));
        assertThat(providerStatus(f.attempt()).requestDigest()).isNull();assertThat(providerStatus(f.attempt()).state()).isEqualTo(RemoteStatus.State.CANCELLED);
        assertThat(runtimes.result(f.task())).isEmpty();assertThat(executions.taskDetail(f.child()).attempts()).isEmpty();
    }
    @Test void providerProcessRestartProducesNewAttemptAndKeepsOriginalBinding() throws Exception {
        var f=create(true,false,400,true);worker().commands();assertThat(providerStatus(f.attempt()).state()).isEqualTo(RemoteStatus.State.RUNNING);
        int oldPort=URI.create(origin).getPort();process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();startProvider(oldPort);
        until(()->executions.detail(f.run()).run().state().equals("SUCCEEDED"));
        var attempts=executions.taskDetail(f.task()).attempts();assertThat(attempts).hasSize(2);assertThat(attempts.getFirst().epoch()).isEqualTo(2);assertThat(attempts.getFirst().remoteTarget()).isEqualTo(attempts.getLast().remoteTarget());
        assertThat(runtimes.result(f.task()).orElseThrow().attemptId()).isEqualTo(attempts.getFirst().id());
    }
    @Test void cancellationAfterActualUploadPreventsResultAndChildRelease() throws Exception {
        var f=create(true,true,0,false);worker().commands();long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(providerStatus(f.attempt()).state()!=RemoteStatus.State.SUCCEEDED && System.nanoTime()<end)Thread.sleep(20);
        var uploaded=new CountDownLatch(1);var release=new CountDownLatch(1);
        ArtifactFiles boundary=new ArtifactFiles(){public void downloadFile(VerifiedArtifact a,Path p){artifacts.downloadFile(a,p);}public String uploadFile(ArtifactContent c,Path p){String version=artifacts.uploadFile(c,p);uploaded.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return version;}};
        var delayed=new RemoteWorker(runtimes,lifecycle,commits,boundary,provider,settings,clock);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(delayed::reconcile);try{assertThat(uploaded.await(5,TimeUnit.SECONDS)).isTrue();call("POST","/api/v1/tasks/"+f.task()+"/cancel","{}",null,200);}finally{release.countDown();}
            pending.get(10,TimeUnit.SECONDS);
        }
        until(()->executions.detail(f.run()).run().state().equals("CANCELLED"));assertThat(runtimes.result(f.task())).isEmpty();assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("SKIPPED");
    }
    @Test void changedProviderConfigurationCannotSendPinnedWorkElsewhere() throws Exception {
        var f=create(true,false,0,false);var d=lifecycle.remoteDispatch(f.attempt());
        try(var changed=new RemoteProvider(true,"reference","http://127.0.0.1:1",token.toString(),"","SYNTHETIC",1)){
            new RemoteWorker(runtimes,lifecycle,commits,artifacts,changed,settings,clock).commands();
            assertThat(provider.gateway(d.allocation().target()).inspect(d.work().identity())).isEmpty();
        }
        assertThat(call("POST","/api/v1/workflow-runs",f.request(),f.key(),200).get("id")).isEqualTo(f.run().toString());
        until(()->executions.detail(f.run()).run().state().equals("SUCCEEDED"));
    }
    @Test void corruptedActualProviderFileCannotBecomeResult() throws Exception {
        var f=create(true,false,0,false);worker().commands();long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(providerStatus(f.attempt()).state()!=RemoteStatus.State.SUCCEEDED && System.nanoTime()<end)Thread.sleep(20);
        var allocation=runtimes.byAttempt(f.attempt()).orElseThrow().remoteAllocationId();var file=root.resolve("provider").resolve(allocation.toString()).resolve("outputs/output");byte[] bytes=Files.readAllBytes(file);bytes[0]^=1;Files.write(file,bytes);
        until(()->executions.detail(f.run()).run().state().equals("FAILED"));assertThat(runtimes.result(f.task())).isEmpty();assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().failureReason()).isEqualTo("OUTPUT_INVALID");
    }
    @Test void publicTransferFromKubernetesFixtureRunsOnActualRemote() throws Exception {
        var f=create(false,false,0,false);var r=runtimes.byAttempt(f.attempt()).orElseThrow();var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(f.attempt(),pod.jobUid());lifecycle.claim(f.attempt(),1,pod);finishKubernetesCreate(r.id());
        var body=JSON.canonical(Map.of("sourceAttemptId",f.attempt().toString(),"targetProviderKey","reference","drainTimeoutSeconds",30,"startTimeoutSeconds",30));
        var op=call("POST","/api/v1/tasks/"+f.task()+"/offload",body,UUID.randomUUID().toString(),202);UUID id=UUID.fromString((String)op.get("id"));
        assertThat(offloads.find(id).remoteTarget()).isEqualTo(provider.select("reference"));offloads.advance(id);assertThat(offloads.find(id).state()).isEqualTo("DRAINING");
        lifecycle.confirmStopped(f.attempt());until(()->executions.detail(f.run()).run().state().equals("SUCCEEDED"));assertThat(offloads.find(id).state()).isEqualTo("SUCCEEDED");
        assertThat(runtimes.result(f.task()).orElseThrow().producerPodUid()).isNull();assertThat(executions.taskDetail(f.task()).attempts().getFirst().cause()).isEqualTo("OFFLOAD");
    }
    @Test void publicTransferFromActualRemoteWaitsForCancellationThenCreatesNodeAttempt() throws Exception {
        var f=create(true,false,5000,false);worker().commands();assertThat(providerStatus(f.attempt()).state()).isEqualTo(RemoteStatus.State.RUNNING);
        UUID node=UUID.randomUUID();jdbc.update("INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES (?,?,'amd64','linux','READY','4','8Gi','{}',?)",node,"remote-target-"+node,java.sql.Timestamp.from(clock.instant()));
        String body=JSON.canonical(Map.of("sourceAttemptId",f.attempt().toString(),"targetNodeId",node.toString(),"drainTimeoutSeconds",30,"startTimeoutSeconds",30));
        UUID id=UUID.fromString((String)call("POST","/api/v1/tasks/"+f.task()+"/offload",body,UUID.randomUUID().toString(),202).get("id"));
        until(()->offloads.find(id).state().equals("STARTING"));assertThat(providerStatus(f.attempt()).state()).isEqualTo(RemoteStatus.State.CANCELLED);
        var target=executions.taskDetail(f.task()).attempts().getFirst();assertThat(target.mode()).isEqualTo("NODE");assertThat(target.remoteTarget()).isNull();assertThat(target.nodeId()).isEqualTo(node);
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),node,"remote-target-"+node);lifecycle.submitted(target.id(),pod.jobUid());lifecycle.claim(target.id(),target.epoch(),pod);assertThat(offloads.find(id).state()).isEqualTo("SUCCEEDED");
        call("POST","/api/v1/workflow-runs/"+f.run()+"/cancel","{}",null,200);lifecycle.confirmStopped(target.id());
    }
}
