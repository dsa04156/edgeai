package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual PG/MVC/S3 and durable worker. Pod identity/termination are explicit Kubernetes fixtures. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RuntimeResultJournalIntegrationTest {
    private static final String BUCKET="edgeai-result-journal-"+UUID.randomUUID();
    private static final Path KEY=key();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("edgeai.runtime.namespace",()->BUCKET);p.add("edgeai.runtime.key-file",KEY::toString);
        p.add("edgeai.storage.endpoint",()->required("EDGEAI_STORAGE_URL"));p.add("edgeai.storage.runner-endpoint",()->required("EDGEAI_STORAGE_URL"));
        p.add("edgeai.storage.access-key",()->required("EDGEAI_MINIO_USER"));p.add("edgeai.storage.secret-key",()->required("EDGEAI_MINIO_PASSWORD"));p.add("edgeai.storage.bucket",()->BUCKET);
    }
    @Autowired MockMvc mvc;@Autowired ProfileService profiles;@Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;@Autowired RuntimeRepository runtimes;@Autowired RunnerTokenService tokens;
    @Autowired RuntimeLifecycleService lifecycle;@Autowired RuntimeResultPublisher publisher;
    @Autowired RuntimeResultPublicationRepository publications;@Autowired RuntimeSettings settings;
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;
    @MockitoBean RuntimeGateway gateway;@MockitoSpyBean S3ArtifactStore storage;
    private final JsonDocuments json=new JsonDocuments();
    private final Map<UUID,RuntimePod> pods=new ConcurrentHashMap<>();
    private MinioClient admin;
    private record Execution(UUID task,UUID attempt,RuntimePod pod,ResultManifest manifest){}
    @BeforeEach void setup()throws Exception{
        admin=MinioClient.builder().endpoint(required("EDGEAI_STORAGE_URL")).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());
        when(gateway.authenticatePod(any(),any())).thenAnswer(call->{
            RuntimeInstance runtime=call.getArgument(0);
            if(!"result-proof-fixture".equals(call.getArgument(1)) || !pods.containsKey(runtime.attemptId()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return pods.get(runtime.attemptId());
        });
        // Prior test queues belong to the same owned namespace and must not be leased by another test.
        jdbc.update("UPDATE edgeai.runtime_result_publication SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id IN (SELECT id FROM edgeai.runtime_instance WHERE namespace=?)",BUCKET);
        jdbc.update("UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id IN (SELECT id FROM edgeai.runtime_instance WHERE namespace=?)",BUCKET);
    }
    @AfterEach void cleanup()throws Exception{
        try{
            for(var v:versions(""))admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(v.getKey()).versionId(v.getValue()).build());
            assertThat(versions("")).isEmpty();admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
        }finally{admin.close();}
    }
    @AfterAll static void cleanupKey()throws Exception{Files.deleteIfExists(KEY);}
    @Test void apiSuccessRetainsCommittedIdentityAndExactOutputVersionsBeforeResponse()throws Exception{
        var e=execution();assertThat(commit(e,201)).contains("SUCCEEDED");var result=runtimes.result(e.task()).orElseThrow();var first=versions(resultKey(e));
        var doc=journal(e);assertThat(doc).containsEntry("resultId",result.id().toString()).containsEntry("committedAt",result.createdAt().toString())
            .containsEntry("manifestDigest",result.manifestDigest()).containsEntry("podUid",e.pod().podUid().toString());
        assertThat(json.canonical(doc)).doesNotContain(runtime(e).claimNonce().toString(),"private-work-parameter","X-Amz");
        var output=(Map<?,?>)((List<?>)doc.get("outputs")).getFirst();var a=result.outputs().getFirst().artifact();
        assertThat(output.get("versionId")).isEqualTo(a.versionId());assertThat(output.get("objectKey")).isEqualTo(a.objectKey());
        assertThat(output.get("sha256")).isEqualTo(a.sha256());assertThat(first).hasSize(1);
        commit(e,200);assertThat(versions(resultKey(e))).isEqualTo(first);assertThat(journal(e)).isEqualTo(doc);
        assertThat(worker(publisher,Instant.now()).publishOne()).isTrue();assertThat(completed(e)).isTrue();
        assertThat(versions(resultKey(e))).isEqualTo(first);assertThat(executions.taskDetail(e.task()).attempts()).hasSize(1);
    }
    @Test void lostS3ReplyPreservesCommittedResultAndReusesOriginalJournal()throws Exception{
        var e=execution();doAnswer(call->{call.callRealMethod();throw new ArtifactStoreUnavailableException();}).doCallRealMethod().when(storage).retainResult(any());
        assertThat(commit(e,503)).contains("RUNTIME_UNAVAILABLE");var result=runtimes.result(e.task()).orElseThrow();var before=versions(resultKey(e));
        assertThat(completed(e)).isFalse();commit(e,200);assertThat(runtimes.result(e.task()).orElseThrow()).isEqualTo(result);
        assertThat(versions(resultKey(e))).isEqualTo(before);assertThat(journal(e).get("committedAt")).isEqualTo(result.createdAt().toString());
    }
    @Test void actualTransportFailureAndProducerLossAreRecoveredByNewWorkerAfterLeaseExpiry()throws Exception{
        var e=execution();int closedPort;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){closedPort=socket.getLocalPort();}
        String origin="http://127.0.0.1:"+closedPort;
        try(var unavailable=new S3ArtifactStore(origin,origin,required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD"),BUCKET,Clock.systemUTC())){
            doAnswer(call->{unavailable.retainResult(call.getArgument(0));return null;}).when(storage).retainResult(any());
            commit(e,503);assertThat(versions(resultKey(e))).isEmpty();assertThat(completed(e)).isFalse();
            assertThat(worker(new RuntimeResultPublisher(runtimes,unavailable),Instant.now()).publishOne()).isTrue();
        }
        var result=runtimes.result(e.task()).orElseThrow();pods.remove(e.attempt());when(gateway.stop(any())).thenReturn(true);
        new RuntimeWorker(runtimes,lifecycle,gateway,tokens,settings,Clock.systemUTC()).commands();commit(e,401);
        assertThat(completed(e)).isFalse();
        assertThat(runtime(e).observedState()).isEqualTo("TERMINATED");assertThat(executions.taskDetail(e.task()).task().state()).isEqualTo("SUCCEEDED");
        Instant now=Instant.now().plusSeconds(10);var old=publications.lease(BUCKET,UUID.randomUUID(),now,Duration.ofSeconds(1)).orElseThrow();
        assertThat(publications.lease(BUCKET,UUID.randomUUID(),now,Duration.ofSeconds(1))).isEmpty();
        doCallRealMethod().when(storage).retainResult(any());
        var restarted=new RuntimeResultPublisher(runtimes,storage);
        assertThat(worker(restarted,now.plusSeconds(2)).publishOne()).isTrue();assertThat(completed(e)).isTrue();
        assertThat(publications.finish(old.resultId(),old.leaseOwner(),now.plusSeconds(2))).isFalse();
        assertThat(journal(e).get("resultId")).isEqualTo(result.id().toString());assertThat(versions(resultKey(e))).hasSize(1);
        assertThat(runtimes.result(e.task()).orElseThrow()).isEqualTo(result);
    }
    @Test void cancellationDuringArtifactVerificationCreatesNeitherResultNorPublication()throws Exception{
        var e=execution();doAnswer(call->{var value=call.callRealMethod();executions.cancelTask(e.task(),"{}");return value;}).when(storage).verify(any(),any());
        commit(e,409);assertThat(runtimes.result(e.task())).isEmpty();assertThat(publicationCount(e)).isZero();assertThat(versions(resultKey(e))).isEmpty();
        verify(storage,never()).retainResult(any());
    }
    @Test void databaseRollbackRemovesResultAndItsOutboxAndCannotPublishInsideTransaction()throws Exception{
        var e=execution();var output=e.manifest().outputs().getFirst();var artifact=storage.verify(output.content(e.task(),e.attempt()),output.versionId());
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            var permit=lifecycle.prepareCommit(e.attempt(),1,e.pod().podUid(),e.manifest());
            lifecycle.commitVerified(permit,List.of(new TaskResult.Output(output.port(),artifact)));assertThat(publicationCount(e)).isEqualTo(1);
            assertThatThrownBy(()->publisher.publish(runtime(e).id())).isInstanceOf(IllegalStateException.class);status.setRollbackOnly();
        });
        assertThat(runtimes.result(e.task())).isEmpty();assertThat(publicationCount(e)).isZero();assertThat(versions(resultKey(e))).isEmpty();
        verify(storage,never()).retainResult(any());assertThat(executions.taskDetail(e.task()).task().state()).isEqualTo("RUNNING");
    }
    @Test void conflictingJournalIsNotOverwrittenOrAcknowledgedAndDoesNotFailAcceptedWork()throws Exception{
        var e=execution();commit(e,201);var result=runtimes.result(e.task()).orElseThrow();var altered=new TreeMap<>(journal(e));altered.put("manifestDigest","sha256:"+"b".repeat(64));
        replace(e,json.canonical(altered),"application/vnd.edgeai.runtime-result+json");var before=versions(resultKey(e));
        assertThat(commit(e,400)).contains("ARTIFACT_INVALID");assertThat(worker(publisher,Instant.now()).publishOne()).isTrue();
        assertThat(completed(e)).isFalse();assertThat(versions(resultKey(e))).isEqualTo(before);assertThat(runtimes.result(e.task()).orElseThrow()).isEqualTo(result);
        assertThat(executions.taskDetail(e.task()).task().state()).isEqualTo("SUCCEEDED");
    }
    @Test void concurrentPublishersRetainExactlyOneVersion()throws Exception{
        var e=execution();doThrow(new ArtifactStoreUnavailableException()).when(storage).retainResult(any());commit(e,503);
        doCallRealMethod().when(storage).retainResult(any());
        try(var threads=Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<Future<TaskResult>>();for(int i=0;i<4;i++)futures.add(threads.submit(()->publisher.publish(runtime(e).id())));
            for(var future:futures)assertThat(future.get(20,TimeUnit.SECONDS).id()).isEqualTo(runtimes.result(e.task()).orElseThrow().id());
        }
        assertThat(versions(resultKey(e))).hasSize(1);
    }
    @Test void migrationBackfillsExistingResultsWithoutChangingTheirIdentityOrPhysicalCommands()throws Exception{
        var e=execution();commit(e,201);var original=runtimes.result(e.task()).orElseThrow();
        String commands=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id)::text FROM edgeai.runtime_command c",String.class);
        String migration=new org.springframework.core.io.ClassPathResource("db/migration/V35__kubernetes_result_publication.sql").getContentAsString(StandardCharsets.UTF_8);
        // DDL is transactional on this owned PostgreSQL test database. Restore the whole queue afterward.
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.execute("DROP TRIGGER enqueue_runtime_result_publication ON edgeai.task_result; DROP FUNCTION edgeai.enqueue_runtime_result_publication(); DROP TABLE edgeai.runtime_result_publication;");
            jdbc.execute(migration);
            assertThat(publicationCount(e)).isEqualTo(1);assertThat(completed(e)).isFalse();
            assertThat(runtimes.result(e.task()).orElseThrow()).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id)::text FROM edgeai.runtime_command c",String.class)).isEqualTo(commands);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_result_publication p JOIN edgeai.task_result r ON r.id=p.result_id WHERE NOT r.committed OR r.remote_allocation_id IS NOT NULL OR r.vd_runtime_id IS NOT NULL",Integer.class)).isZero();
            status.setRollbackOnly();
        });
        assertThat(runtimes.result(e.task()).orElseThrow()).isEqualTo(original);assertThat(versions(resultKey(e))).hasSize(1);
    }
    @Test void duplicateFieldsUnknownFieldsAndWrongMediaTypeAreRejectedWithoutReplacement()throws Exception{
        var e=execution();commit(e,201);String original=json.canonical(journal(e));
        for(String body:List.of(original.replaceFirst("\\{","{\"epoch\":1,"),original.substring(0,original.length()-1)+",\"unexpected\":true}")){
            replace(e,body,"application/vnd.edgeai.runtime-result+json");var before=versions(resultKey(e));commit(e,400);assertThat(versions(resultKey(e))).isEqualTo(before);
        }
        replace(e,original,"application/json");var before=versions(resultKey(e));commit(e,400);assertThat(versions(resultKey(e))).isEqualTo(before);
    }
    @SuppressWarnings("unchecked") private Execution execution()throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));spec.put("inputs",Map.of());
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","result-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","result-"+UUID.randomUUID(),"displayName","Result journal"))).value();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",List.of(Map.of("key","root","serviceProfileVersionId",profile.id().toString(),"parameters",Map.of("private-work-parameter",42))),"dependencies",List.of()))).value();
        String response=mvc.perform(post("/api/v1/workflow-runs").with(user("fixture")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(json.canonical(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID run=UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id"));var task=executions.detail(run).tasks().getFirst();
        UUID attempt=executions.taskDetail(task.id()).attempts().getFirst().id();var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"result-fixture-node");pods.put(attempt,pod);
        var initial=new Execution(task.id(),attempt,pod,null);request(initial,"claim",Map.of(),200);
        byte[] bytes="{\"score\":3}".getBytes(StandardCharsets.UTF_8);String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var content=new ArtifactContent(task.id(),attempt,"output",sha,bytes.length,"application/json");
        String objectVersion=admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(content.objectKey()).data(bytes,bytes.length).contentType(content.mediaType()).build()).versionId();
        return new Execution(task.id(),attempt,pod,new ResultManifest(List.of(new ResultManifest.Output(content.port(),content.bytes(),sha,content.mediaType(),objectVersion))));
    }
    private String commit(Execution e,int status)throws Exception{
        return request(e,"commit",Map.of("outputs",e.manifest().outputs().stream().map(o->Map.of("port",o.port(),"bytes",o.bytes(),"sha256",o.sha256(),"mediaType",o.mediaType(),"versionId",o.versionId())).toList()),status);
    }
    private String request(Execution e,String path,Map<String,Object> extra,int status)throws Exception{
        var body=new TreeMap<String,Object>(extra);body.put("epoch",1);body.put("podUid",e.pod().podUid().toString());
        return mvc.perform(post("/internal/v1/attempts/"+e.attempt()+"/"+path).header("Authorization","Bearer "+tokens.issue(runtime(e)))
            .header("X-EdgeAI-Pod-Token","result-proof-fixture").contentType("application/json").content(json.canonical(body)))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    private RuntimeInstance runtime(Execution e){return runtimes.byAttempt(e.attempt()).orElseThrow();}
    private String resultKey(Execution e){return "authority/runtime-result/"+runtime(e).id()+".json";}
    private RuntimeResultPublicationWorker worker(RuntimeResultPublisher p,Instant now){return new RuntimeResultPublicationWorker(publications,p,settings,Clock.fixed(now,ZoneOffset.UTC));}
    private int publicationCount(Execution e){return jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_result_publication WHERE runtime_id=?",Integer.class,runtime(e).id());}
    private boolean completed(Execution e){return jdbc.queryForObject("SELECT completed FROM edgeai.runtime_result_publication WHERE runtime_id=?",Boolean.class,runtime(e).id());}
    @SuppressWarnings("unchecked") private Map<String,Object> journal(Execution e)throws Exception{
        var stat=admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(resultKey(e)).build());assertThat(stat.versionId()).isNotBlank();
        try(var input=admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(resultKey(e)).versionId(stat.versionId()).build())){return (Map<String,Object>)json.decode(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
    }
    private void replace(Execution e,String body,String mediaType)throws Exception{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(resultKey(e)).data(bytes,bytes.length).contentType(mediaType).build());
    }
    private List<Map.Entry<String,String>> versions(String prefix)throws Exception{
        var values=new ArrayList<Map.Entry<String,String>>();for(var result:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prefix).recursive(true).includeVersions(true).build())){
            var value=result.get();assertThat(value.versionId()).isNotBlank();values.add(Map.entry(value.objectName(),value.versionId()));}return values;
    }
    private static Path key(){try{var key=Files.createTempFile("edgeai-result-journal-",".key");byte[] value=new byte[32];new java.security.SecureRandom().nextBytes(value);Files.writeString(key,HexFormat.of().formatHex(value));return key;}catch(Exception error){throw new IllegalStateException("Cannot create journal test key");}}
    private static String required(String name){return Objects.requireNonNull(System.getenv(name),"Actual journal test requires "+name);}
}
