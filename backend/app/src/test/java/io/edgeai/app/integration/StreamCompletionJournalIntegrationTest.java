package io.edgeai.app.integration;

import io.edgeai.app.config.*;
import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.stream.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL, HTTP and versioned S3 publication. Pods, broker receipts and checkpoint metadata are explicit fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false",
    "edgeai.stream.enabled=true","edgeai.stream.bindings-enabled=true","edgeai.stream.broker-digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StreamCompletionJournalIntegrationTest {
    private static final String BUCKET="edgeai-completion-"+UUID.randomUUID();
    private static final String MEDIA="application/vnd.edgeai.stream-completion+json";
    @Autowired StreamCompletionPublicationRepository publications;
    @Autowired StreamCompletionPublisher publisher;
    @Autowired StreamBindingService bindings;
    @Autowired RuntimeSettings settings;
    private MinioClient admin;
    private static String required(String name){return Objects.requireNonNull(System.getenv(name),name+" required");}
    @BeforeEach void setupStorage()throws Exception{
        admin=MinioClient.builder().endpoint(required("EDGEAI_STORAGE_URL")).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());
        jdbc.update("UPDATE edgeai.stream_completion_publication SET completed=true,lease_owner=NULL,lease_until=NULL WHERE namespace=?",BUCKET);
    }
    @AfterEach void cleanupStorage()throws Exception{
        try{for(var v:versions(""))admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(v.getKey()).versionId(v.getValue()).build());
            assertThat(versions("")).isEmpty();admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
        }finally{admin.close();}
    }
    private List<Map.Entry<String,String>> versions(String key)throws Exception{
        var values=new ArrayList<Map.Entry<String,String>>();
        for(var item:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(key).recursive(true).includeVersions(true).build())){
            var v=item.get();if(key.isEmpty() || key.equals(v.objectName()))values.add(Map.entry(v.objectName(),v.versionId()));
        }return values;
    }
    private void replace(StreamCompletionAuthority a,String body,String media)throws Exception{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        try(var input=new java.io.ByteArrayInputStream(bytes)){admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(a.objectKey()).stream(input,(long)bytes.length,-1L).contentType(media).build());}
    }
    private Map<?,?> journal(StreamCompletionAuthority a)throws Exception{
        var version=admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(a.objectKey()).build()).versionId();
        try(var in=admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(a.objectKey()).versionId(version).build())){
            return (Map<?,?>)json.decode(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private StreamCompletionAuthority authority(Fixture f){return publications.forAttempt(f.principals().get("source").attemptId()).orElseThrow();}
    private int publicationCount(Fixture f){return jdbc.queryForObject("SELECT count(*) FROM edgeai.stream_completion_publication WHERE run_id=?",Integer.class,f.run());}
    private boolean published(StreamCompletionAuthority a){return jdbc.queryForObject("SELECT completed FROM edgeai.stream_completion_publication WHERE id=?",Boolean.class,a.id());}
    private StreamCompletionPublicationWorker publicationWorker(StreamCompletionPublisher p,Instant now){return new StreamCompletionPublicationWorker(publications,p,settings,Clock.fixed(now,ZoneOffset.UTC));}
    private Fixture sealed()throws Exception{
        var f=fixture();assigned(f);complete(f,"source",checkpoint(f,"source",3,false));complete(f,"sink",checkpoint(f,"sink",3,false));
        assertThat(publicationCount(f)).isZero();deviceComplete(f,0,3);assertThat(publicationCount(f)).isEqualTo(1);return f;
    }
    private void authenticate(Fixture f){
        when(podGateway.authenticatePod(any(),any())).thenAnswer(call->{
            RuntimeInstance r=call.getArgument(0);if(!"completion-pod-fixture".equals(call.getArgument(1)))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return f.principals().values().stream().filter(p->p.attemptId().equals(r.attemptId())).findFirst().orElseThrow().pod();
        });
    }
    @Test void httpFinalizationRetainsWholeGroupOnceAndOriginalCheckpointReferences()throws Exception{
        var f=fixture();authenticate(f);assigned(f);
        var c1=checkpoint(f,"source",3,false);var c2=checkpoint(f,"sink",3,false);
        complete(f,"source",c1);complete(f,"sink",c2);assertThat(publicationCount(f)).isZero();
        var device=f.devices().values().iterator().next();var g=f.generations().get(f.routes().getFirst().id());
        String request=json.canonical(Map.of("epoch",device.epoch(),"generationId",g.id().toString(),"sequence",3));
        var credentials=Map.of("Authorization","Bearer "+deviceTokens.issue(deviceStore.activeSession(device.deviceId()).orElseThrow()));
        try(var client=HttpClient.newHttpClient()){
            assertThat(post(client,devicePath(device),request,credentials,200).get("state")).isEqualTo("FINALIZE");
            var a=authority(f);var first=versions(a.objectKey());assertThat(first).hasSize(1);
            var doc=journal(a);assertThat(doc).isEqualTo(json.decode(a.documentJson()));
            assertThat((List<?>)doc.get("taskCompletions")).hasSize(2);assertThat((List<?>)doc.get("deviceCompletions")).hasSize(1);
            assertThat((List<?>)doc.get("checkpoints")).hasSize(2);assertThat((List<?>)doc.get("producers")).hasSize(2);
            assertThat(a.documentJson()).contains(c1.artifact().versionId(),c2.artifact().versionId());
            for(var p:f.principals().values()){
                assertThat(a.documentJson()).doesNotContain(runtimes.byAttempt(p.attemptId()).orElseThrow().claimNonce().toString(),"password","parameters","X-Amz");
                assertThat(post(client,runnerPath(p,"execution"),body(p),headers(p),200).get("state")).isEqualTo("FINALIZE");
            }
            post(client,devicePath(device),request,credentials,200);assertThat(versions(a.objectKey())).isEqualTo(first);
            assertThat(publicationWorker(publisher,Instant.now()).publishOne()).isTrue();assertThat(published(a)).isTrue();
            assertThat(versions(a.objectKey())).isEqualTo(first);
        }
    }
    @Test void lostStorageReplyKeepsOriginalGrantAndPublicRetryUsesSameVersion()throws Exception{
        var f=sealed();authenticate(f);var a=authority(f);var p=f.principals().get("source");
        doAnswer(call->{call.callRealMethod();throw new ArtifactStoreUnavailableException();}).doCallRealMethod().when(storage).retainCompletion(any());
        try(var client=HttpClient.newHttpClient()){
            assertThat(post(client,runnerPath(p,"execution"),body(p),headers(p),503).get("code")).isEqualTo("RUNTIME_UNAVAILABLE");
            var first=versions(a.objectKey());assertThat(first).hasSize(1);assertThat(published(a)).isFalse();
            assertThat(post(client,runnerPath(p,"execution"),body(p),headers(p),200).get("state")).isEqualTo("FINALIZE");
            assertThat(authority(f)).isEqualTo(a);assertThat(versions(a.objectKey())).isEqualTo(first);
        }
    }
    @Test void actualStorageOutageAndProducerLossRecoverAfterWorkerLeaseExpires()throws Exception{
        var f=sealed();var a=authority(f);int port;
        try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        String endpoint="http://127.0.0.1:"+port;
        try(var offline=new S3ArtifactStore(endpoint,endpoint,required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD"),BUCKET,Clock.systemUTC())){
            assertThat(publicationWorker(new StreamCompletionPublisher(publications,store,offline),Instant.now()).publishOne()).isTrue();
        }
        assertThat(published(a)).isFalse();assertThat(versions(a.objectKey())).isEmpty();
        for(var p:f.principals().values())jdbc.update("UPDATE edgeai.runtime_instance SET observed_state='TERMINATED',desired_state='STOPPED' WHERE attempt_id=?",p.attemptId());
        var now=Instant.now().plusSeconds(10);var old=publications.lease(BUCKET,UUID.randomUUID(),now,Duration.ofSeconds(1)).orElseThrow();
        assertThat(publications.lease(BUCKET,UUID.randomUUID(),now,Duration.ofSeconds(1))).isEmpty();
        assertThat(publicationWorker(new StreamCompletionPublisher(publications,store,storage),now.plusSeconds(2)).publishOne()).isTrue();
        assertThat(publications.finish(old.id(),old.owner(),now.plusSeconds(2))).isFalse();assertThat(published(a)).isTrue();
        assertThat(journal(a)).isEqualTo(json.decode(a.documentJson()));assertThat(versions(a.objectKey())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_instance WHERE run_id=?",Integer.class,f.run())).isEqualTo(2);
        for(var t:f.tasks().values())assertThat(executions.attempts(t.id())).hasSize(1);
    }
    @Test void grantAndPublicationRollbackTogetherAndNeverPublishUncommittedFacts()throws Exception{
        var f=fixture();assigned(f);complete(f,"source",checkpoint(f,"source",3,false));complete(f,"sink",checkpoint(f,"sink",3,false));
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            deviceComplete(f,0,3);jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");assertThat(publicationCount(f)).isEqualTo(1);
            assertThatThrownBy(()->publisher.publish(authority(f).id())).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();
        });
        assertThat(publicationCount(f)).isZero();for(var p:f.principals().values())assertThat(store.granted(p.attemptId())).isEmpty();
        assertThat(versions("")).isEmpty();verify(storage,never()).retainCompletion(any());
    }
    @Test void conflictingOrMalformedJournalIsNeverOverwrittenOrAcknowledged()throws Exception{
        var f=sealed();var a=authority(f);String doc=a.documentJson();
        for(String invalid:List.of(doc.replaceFirst("\\{","{\"id\":\""+a.id()+"\","),doc+" {}",doc.replace("\"routeDigest\":", "\"unknown\":true,\"routeDigest\":"),doc.replace("sha256:","sha255:"))){
            replace(a,invalid,MEDIA);var before=versions(a.objectKey());
            assertThatThrownBy(()->publisher.publish(a.id())).isInstanceOf(ArtifactVerificationException.class);
            assertThat(versions(a.objectKey())).isEqualTo(before);assertThat(published(a)).isFalse();
        }
        replace(a,doc,"application/json");assertThatThrownBy(()->publisher.publish(a.id())).isInstanceOf(ArtifactVerificationException.class);
        assertThat(authority(f)).isEqualTo(a);
    }
    @Test void concurrentPublishersCreateExactlyOneVersion()throws Exception{
        var a=authority(sealed());
        try(var pool=Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<Future<?>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->publisher.publish(a.id())));
            for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }assertThat(versions(a.objectKey())).hasSize(1);assertThat(journal(a)).isEqualTo(json.decode(a.documentJson()));
    }
    @Test void cancellationDuringPublicationCannotIssueFinalizationResponse()throws Exception{
        var f=sealed();authenticate(f);var a=authority(f);var p=f.principals().get("source");
        doAnswer(call->{call.callRealMethod();runs.cancelRun(f.run(),"{}");return null;}).when(storage).retainCompletion(any());
        try(var client=HttpClient.newHttpClient()){post(client,runnerPath(p,"execution"),body(p),headers(p),409);}
        assertThat(journal(a)).isEqualTo(json.decode(a.documentJson()));assertThat(executions.run(f.run(),false).orElseThrow().state()).isEqualTo("CANCELLING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.task_result WHERE task_id IN (SELECT id FROM edgeai.task WHERE run_id=?)",Integer.class,f.run())).isZero();
    }
    @Test void authorityIsImmutableAndNamespaceAndLeaseOwnerAreEnforced()throws Exception{
        var a=authority(sealed());
        for(String sql:List.of("UPDATE edgeai.stream_completion_publication SET granted_at=granted_at+interval '1 microsecond' WHERE id=?",
                "UPDATE edgeai.stream_completion_publication SET document=document||'{\"unexpected\":true}'::jsonb WHERE id=?",
                "DELETE FROM edgeai.stream_completion_publication WHERE id=?"))
            assertThatThrownBy(()->jdbc.update(sql,a.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.execute("TRUNCATE edgeai.stream_completion_publication")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(publications.lease("other-namespace",UUID.randomUUID(),Instant.now(),Duration.ofSeconds(10))).isEmpty();
        var lease=publications.lease(BUCKET,UUID.randomUUID(),Instant.now(),Duration.ofSeconds(10)).orElseThrow();
        assertThat(publications.finish(a.id(),UUID.randomUUID(),Instant.now())).isFalse();
        assertThat(publications.defer(a.id(),UUID.randomUUID(),Instant.now(),Instant.now())).isFalse();
        publisher.publish(a.id());assertThat(publications.finish(a.id(),lease.owner(),Instant.now())).isTrue();
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.stream_completion_publication SET completed=false WHERE id=?",a.id())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void migrationBackfillsSameAuthorityWithoutChangingOriginalGrants()throws Exception{
        var f=sealed();var a=authority(f);String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(c) ORDER BY attempt_id)::text FROM edgeai.stream_task_completion c",String.class);
        String migration=new org.springframework.core.io.ClassPathResource("db/migration/V37__stream_completion_publication.sql").getContentAsString(StandardCharsets.UTF_8);
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            jdbc.execute("DROP TRIGGER stream_task_completion_publication ON edgeai.stream_task_completion; DROP TRIGGER stream_device_completion_publication ON edgeai.stream_device_completion; DROP FUNCTION edgeai.enqueue_stream_completion(); DROP TABLE edgeai.stream_completion_publication; DROP FUNCTION edgeai.capture_stream_completion(uuid); DROP FUNCTION edgeai.protect_stream_completion_publication();");
            jdbc.execute(migration);assertThat(authority(f)).isEqualTo(a);
            assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(c) ORDER BY attempt_id)::text FROM edgeai.stream_task_completion c",String.class)).isEqualTo(before);s.setRollbackOnly();
        });assertThat(authority(f)).isEqualTo(a);
    }
    @Test void sharedDeviceRequiresWholeComponentButIndependentDevicePublishesSeparately()throws Exception{
        for(boolean shared:List.of(false,true)){
            var f=fixture(true,shared,false);assigned(f);complete(f,"source",checkpoint(f,"source",3,false));complete(f,"sink",checkpoint(f,"sink",3,false));deviceComplete(f,0,3);
            assertThat(publicationCount(f)).isEqualTo(shared?0:1);
            complete(f,"other",checkpoint(f,"other",3,false));deviceComplete(f,2,3);
            assertThat(publicationCount(f)).isEqualTo(shared?1:2);
            var a=authority(f);publisher.publish(a.id());assertThat((List<?>)journal(a).get("attemptIds")).hasSize(shared?3:2);
        }
    }
    @Test void largeJournalConcurrentPublicationUsesConditionalSingleObjectWrite()throws Exception{
        var original=authority(sealed());var document=new TreeMap<String,Object>();
        ((Map<?,?>)json.decode(original.documentJson())).forEach((k,v)->document.put((String)k,v));
        // Storage-only size/race probe: no claim that this synthetic checkpoint is a valid computation.
        document.put("checkpoints",List.of(Map.of("summary", "x".repeat(6*1024*1024))));
        var large=new StreamCompletionAuthority(original.id(),original.runId(),original.namespace(),original.grantedAt(),json.canonical(document));
        try(var pool=Executors.newFixedThreadPool(3)){
            var futures=new ArrayList<Future<?>>();for(int i=0;i<3;i++)futures.add(pool.submit(()->storage.retainCompletion(large)));
            for(var future:futures)future.get(40,TimeUnit.SECONDS);
        }assertThat(versions(large.objectKey())).hasSize(1);assertThat(journal(large)).isEqualTo(document);
    }
    @Test void unavailableJournalBlocksFinalCheckpointAndOutputUploadAndCommitEndpoints()throws Exception{
        var f=sealed();authenticate(f);var p=f.principals().get("source");var cp=checkpoints.latest(f.tasks().get("source").id()).orElseThrow();
        doThrow(new ArtifactStoreUnavailableException()).when(storage).retainCompletion(any());
        var output=Map.of("port","result","bytes",2,"sha256","a".repeat(64),"mediaType","application/json");
        var committed=new TreeMap<String,Object>(output);committed.put("versionId","fixture-version");
        String base="/internal/v1/attempts/"+p.attemptId();
        try(var client=HttpClient.newHttpClient()){
            post(client,base+"/streams/checkpoints/finalized",body(p,"checkpointId",cp.id().toString()),headers(p),503);
            post(client,base+"/uploads",body(p,"outputs",List.of(output)),headers(p),503);
            post(client,base+"/commit",body(p,"outputs",List.of(committed)),headers(p),503);
        }
        verify(storage,never()).download(any());verify(storage,never()).upload(any());verify(storage,never()).verify(any(),any());
        assertThat(versions("")).isEmpty();assertThat(runtimes.result(f.tasks().get("source").id())).isEmpty();
    }
    @Test void suspendedVersioningRefusesPublicationUntilStorageIsRestored()throws Exception{
        var a=authority(sealed());
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.SUSPENDED,null,null,null)).build());
        assertThatThrownBy(()->publisher.publish(a.id())).isInstanceOf(ArtifactVerificationException.class);assertThat(versions("")).isEmpty();
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());
        publisher.publish(a.id());assertThat(versions(a.objectKey())).hasSize(1);
    }
    private static final Path KEY=keyFile();
    private static Path keyFile() {
        try {var p=Files.createTempFile("edgeai-completion-",".key",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.writeString(p,HexFormat.of().formatHex(bytes));return p;
        } catch(Exception e){throw new IllegalStateException("Cannot create test signing key",e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("edgeai.runtime.namespace",()->BUCKET);
        p.add("edgeai.storage.endpoint",()->required("EDGEAI_STORAGE_URL"));p.add("edgeai.storage.runner-endpoint",()->required("EDGEAI_STORAGE_URL"));
        p.add("edgeai.storage.access-key",()->required("EDGEAI_MINIO_USER"));p.add("edgeai.storage.secret-key",()->required("EDGEAI_MINIO_PASSWORD"));p.add("edgeai.storage.bucket",()->BUCKET);
        p.add("edgeai.runtime.key-file",KEY::toString);p.add("edgeai.stream.device-key-file",KEY::toString);
    }
    @AfterAll static void cleanupKey()throws Exception {Files.delete(KEY);}
    @MockitoBean RuntimeGateway podGateway;@MockitoSpyBean S3ArtifactStore storage;
    @MockitoBean MosquittoStreamBroker broker;@MockitoBean StreamAuthorityWorker worker;@MockitoBean StreamConnectionSettings connection;
    @Autowired RunnerTokenService runnerTokens;@Autowired DeviceStreamTokenService deviceTokens;@Autowired DeviceRepository deviceStore;
    @org.springframework.boot.test.web.server.LocalServerPort int apiPort;
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired DeviceService devices;
    @Autowired ExecutionRepository executions;@Autowired WorkflowRepository definitions;@Autowired RuntimeRepository runtimes;
    @Autowired DataRouteService routes;@Autowired DataRouteRepository routeStore;@Autowired StreamCheckpointRepository checkpoints;
    @Autowired StreamExecutionRepository store;@Autowired StreamExecutionService service;@Autowired RuntimeLifecycleService lifecycle;
    @Autowired ExecutionService runs;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;
    private final JsonDocuments json=new JsonDocuments();
    private record Fixture(UUID run,Map<String,Task> tasks,Map<String,RunnerPrincipal> principals,List<DataRoute> routes,
        Map<UUID,RouteGeneration> generations,Map<UUID,DeviceStreamPrincipal> devices,Map<String,UUID> profiles) {}
    private <T>T tx(java.util.function.Supplier<T> body){return new TransactionTemplate(transactions).execute(s->body.get());}
    private UUID profile(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,json.canonical(Map.of("key","completion-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    @SuppressWarnings("unchecked") private UUID serviceProfile(boolean output)throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));
        var stream=(Map<String,Object>)spec.get("stream");var port=Map.of("mediaType","application/json","maxPayloadBytes",4096);
        stream.put("inputs",Map.of("input",port));stream.put("outputs",output?Map.of("output",port):Map.of());
        return profile(ProfileIdentity.Kind.SERVICE,spec);
    }
    private DeviceStreamPrincipal device() {
        var p=profile(ProfileIdentity.Kind.DEVICE,Map.of("protocol","mqtt"));
        var d=devices.create(json.canonical(Map.of("key","completion-"+UUID.randomUUID(),"displayName","Completion source","profileVersionId",p.toString(),"sourceMode","SYNTHETIC"))).value();
        var session=devices.openSession(d.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
        return new DeviceStreamPrincipal(d.id(),session.id(),session.epoch());
    }
    private Fixture fixture()throws Exception{return fixture(false,false,false);}
    private Fixture fixture(boolean other,boolean shared,boolean batchInside)throws Exception {
        var profiles=new HashMap<String,UUID>();profiles.put("source",serviceProfile(true));profiles.put("sink",serviceProfile(false));
        if(other)profiles.put("other",serviceProfile(false));
        var w=workflows.create(json.canonical(Map.of("key","completion-"+UUID.randomUUID(),"displayName","Completion fixture"))).value();
        var nodes=profiles.entrySet().stream().map(e->Map.of("key",e.getKey(),"serviceProfileVersionId",e.getValue().toString(),"parameters",Map.of())).toList();
        var edges=new ArrayList<Object>();edges.add(Map.of("fromTask","source","toTask","sink","fromPort","output","toPort","input","mode","STREAM"));
        if(batchInside)edges.add(Map.of("fromTask","source","toTask","sink","fromPort","result","toPort","file-input","mode","BATCH"));
        var version=workflows.publish(w.id(),json.canonical(Map.of("version","1.0.0","tasks",nodes,"dependencies",edges))).value();
        var now=Instant.now();var run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),json.digest("completion-fixture",version.id().toString()),"AUTO",null,"{}",RetryPolicy.disabled(),null,"PENDING",now,now);
        tx(()->{executions.create(run);executions.initialize(run,definitions.definitions(version.id()),profiles.keySet());return null;});
        var tasks=new HashMap<String,Task>();var principals=new HashMap<String,RunnerPrincipal>();
        for(var task:executions.tasks(run.id())) {
            tasks.put(task.key(),task);var a=executions.attempts(task.id()).getFirst();
            var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
            var runtime=new RuntimeInstance(UUID.randomUUID(),a.id(),task.id(),run.id(),1,BUCKET,"edgeai-"+a.id(),UUID.randomUUID(),
                "RUNNING","PENDING",null,null,null,null,null,null,now,now);
            tx(()->{executions.run(run.id(),true);runtimes.create(runtime);runtimes.submitted(runtime.id(),pod.jobUid(),now.plusSeconds(600),now);runtimes.claimed(runtime.id(),pod,now);return null;});
            principals.put(task.key(),new RunnerPrincipal(a.id(),1,pod));
        }
        var device=device();var devices=new HashMap<UUID,DeviceStreamPrincipal>();devices.put(device.deviceId(),device);
        var routeList=new ArrayList<DataRoute>();
        routeList.add(routes.fromDevice(run.id(),device.deviceId(),"samples",tasks.get("source").id(),"input",4096));
        routeList.add(routes.fromTask(run.id(),tasks.get("source").id(),"output",tasks.get("sink").id(),"input",4096));
        if(other) {
            var next=shared?device:device();devices.put(next.deviceId(),next);
            routeList.add(routes.fromDevice(run.id(),next.deviceId(),"samples",tasks.get("other").id(),"input",4096));
        }
        var generations=new HashMap<UUID,RouteGeneration>();
        for(var route:routeList) {
            var consumer=principals.get(tasks.values().stream().filter(t->t.id().equals(route.consumerTaskId())).findFirst().orElseThrow().key());
            RouteGeneration.Actor producer;
            if(route.deviceSource()){var d=devices.get(route.sourceDeviceId());producer=new RouteGeneration.Actor(d.sessionId(),d.epoch());}
            else {var p=principals.get("source");producer=new RouteGeneration.Actor(p.attemptId(),p.epoch());}
            var g=routes.prepare(route.id(),UUID.randomUUID(),producer,new RouteGeneration.Actor(consumer.attemptId(),consumer.epoch()),"sha256:"+"b".repeat(64),120);
            generations.put(route.id(),routes.activate(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest())));
        }
        return new Fixture(run.id(),tasks,principals,List.copyOf(routeList),generations,devices,profiles);
    }
    private String body(RunnerPrincipal p,Object... extra) {
        var value=new TreeMap<String,Object>(Map.of("epoch",p.epoch(),"podUid",p.podUid().toString()));
        for(int i=0;i<extra.length;i+=2)value.put((String)extra[i],extra[i+1]);return json.canonical(value);
    }
    private String state(Object value){return (String)((Map<?,?>)value).get("state");}
    private void assigned(Fixture f) {var p=f.principals().get("source");assertThat(state(service.execution(p,body(p)))).isEqualTo("READY");}
    private StreamCheckpoint checkpoint(Fixture f,String key,long sequence,boolean pending) {
        var task=f.tasks().get(key);var p=f.principals().get(key);var runtime=runtimes.byAttempt(p.attemptId()).orElseThrow();
        var relevant=f.routes().stream().filter(r->task.id().equals(r.sourceTaskId()) || task.id().equals(r.consumerTaskId())).toList();
        var cursors=new ArrayList<Object>();var inputs=new ArrayList<Object>();var outputs=new ArrayList<Object>();
        for(var route:relevant) {
            var g=f.generations().get(route.id());boolean input=task.id().equals(route.consumerTaskId());
            Object producer=route.deviceSource()?Map.of("kind","DEVICE_SESSION","deviceId",route.sourceDeviceId().toString(),"sessionId",g.producer().id().toString(),"epoch",g.producer().epoch())
                :Map.of("kind","TASK_ATTEMPT","attemptId",g.producer().id().toString(),"epoch",g.producer().epoch());
            (input?inputs:outputs).add(Map.of("routeId",route.id().toString(),"generation",g.generation(),"producer",producer));
            cursors.add(Map.of("routeId",route.id().toString(),"received",sequence,"committed",pending && !input?sequence-1:sequence,"ended",true));
        }
        var previous=checkpoints.latest(task.id()).orElse(null);long serial=previous==null?10:previous.request().serial()+1;
        var request=new StreamCheckpoint.Request(previous==null?null:previous.id(),serial,"d".repeat(64),100,"e".repeat(64),relevant.stream().map(r->f.generations().get(r.id()).id()).toList());
        var summary=json.canonical(Map.of("manifest",Map.of("version",1,"inputs",inputs,"outputs",outputs,"limits",Map.of("max_frames",128,"max_buffer_bytes",16777216,"max_state_bytes",262144)),
            "revision",3,"routes",cursors,"stateSha256","f".repeat(64),"stateBytes",2));
        var content=request.content(task.id(),p.attemptId());
        var value=new StreamCheckpoint(UUID.randomUUID(),f.run(),task.id(),p.attemptId(),runtime.id(),1,p.podUid(),f.profiles().get(key),request,3,summary,
            new VerifiedArtifact("fixture-only",content.objectKey(),UUID.randomUUID().toString(),content.sha256(),content.bytes(),content.mediaType()),Instant.now(),null);
        tx(()->{executions.run(f.run(),true);checkpoints.insert(value);return null;});return value;
    }
    private Object complete(Fixture f,String key,StreamCheckpoint checkpoint) {
        var p=f.principals().get(key);return service.complete(p,body(p,"checkpointId",checkpoint.id().toString()));
    }
    private Object deviceComplete(Fixture f,int index,long sequence) {
        var r=f.routes().get(index);var d=f.devices().get(r.sourceDeviceId());var g=f.generations().get(r.id());
        return service.deviceComplete(d,json.canonical(Map.of("epoch",d.epoch(),"generationId",g.id().toString(),"sequence",sequence)));
    }
    private void conflict(String code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo(code));
    }
    private ResultManifest result(){return new ResultManifest(List.of(new ResultManifest.Output("result",2,"a".repeat(64),"application/json","fixture-version")));}
    private void commit(Fixture f,String key) {
        var p=f.principals().get(key);var permit=lifecycle.prepareCommit(p.attemptId(),1,p.podUid(),result());var o=result().outputs().getFirst();
        var a=o.content(f.tasks().get(key).id(),p.attemptId());
        lifecycle.commitVerified(permit,List.of(new TaskResult.Output("result",new VerifiedArtifact("fixture-only",a.objectKey(),o.versionId(),a.sha256(),a.bytes(),a.mediaType()))));
    }

    private String runnerPath(RunnerPrincipal p,String action){return "/internal/v1/attempts/"+p.attemptId()+"/streams/"+action;}
    private String devicePath(DeviceStreamPrincipal p){return "/internal/v1/devices/"+p.deviceId()+"/sessions/"+p.sessionId()+"/streams/complete";}
    private Map<String,String> headers(RunnerPrincipal p){return Map.of("Authorization","Bearer "+runnerTokens.issue(runtimes.byAttempt(p.attemptId()).orElseThrow()),"X-EdgeAI-Pod-Token","completion-pod-fixture");}
    private Map<?,?> post(HttpClient client,String path,String body,Map<String,String> headers,int status)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+apiPort+path)).timeout(Duration.ofSeconds(5))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));headers.forEach(request::header);
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP status for %s",path).isEqualTo(status);
        if(status==200)assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return response.body().isBlank()?Map.of():(Map<?,?>)json.decode(response.body());
    }
}
