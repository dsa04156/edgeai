package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.ArtifactStoreUnavailableException;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual PG, HTTP security/controller chain and versioned S3. Kubernetes identity is an explicit fixture. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RuntimeStartJournalIntegrationTest {
    private static final String BUCKET="edgeai-claim-"+UUID.randomUUID();
    private static final Path KEY=key();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("edgeai.runtime.namespace",()->BUCKET);p.add("edgeai.runtime.key-file",KEY::toString);
        p.add("edgeai.storage.endpoint",()->required("EDGEAI_STORAGE_URL"));p.add("edgeai.storage.runner-endpoint",()->required("EDGEAI_STORAGE_URL"));
        p.add("edgeai.storage.access-key",()->required("EDGEAI_MINIO_USER"));p.add("edgeai.storage.secret-key",()->required("EDGEAI_MINIO_PASSWORD"));
        p.add("edgeai.storage.bucket",()->BUCKET);
    }
    @Autowired MockMvc mvc;@Autowired ProfileService profiles;@Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;@Autowired RuntimeRepository runtimes;@Autowired RunnerTokenService tokens;
    @MockitoBean RuntimeGateway gateway;
    @MockitoSpyBean S3ArtifactStore storage;
    private final JsonDocuments json=new JsonDocuments();
    private final Map<UUID,RuntimePod> pods=new HashMap<>();
    private MinioClient admin;
    private boolean created;
    private record Execution(UUID run,UUID task,UUID attempt,RuntimePod pod){}
    @BeforeEach void setup()throws Exception{
        admin=MinioClient.builder().endpoint(required("EDGEAI_STORAGE_URL")).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();
        createBucket();
        when(gateway.authenticatePod(any(),any())).thenAnswer(call->{
            RuntimeInstance runtime=call.getArgument(0);
            if(!"start-proof-fixture".equals(call.getArgument(1)) || !pods.containsKey(runtime.attemptId()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return pods.get(runtime.attemptId());
        });
    }
    @AfterEach void cleanup()throws Exception{
        try{if(created){
            for(var value:versions())admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(value.getKey()).versionId(value.getValue()).build());
            assertThat(versions()).isEmpty();admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
        }}finally{if(admin!=null)admin.close();}
    }
    @AfterAll static void cleanupKey()throws Exception{Files.deleteIfExists(KEY);}
    @Test void actualApiClaimsPersistFirstAdmissionAndReuseItsVersion()throws Exception{
        var e=execution();assertThat(claim(e,200)).contains(e.attempt().toString());var first=versions();var document=journal(e);
        assertThat(first).hasSize(1);assertThat(claim(e,200)).contains("command");assertThat(versions()).isEqualTo(first);assertThat(journal(e)).isEqualTo(document);
        var runtime=runtimes.byAttempt(e.attempt()).orElseThrow();
        assertThat(document).containsEntry("attemptId",e.attempt().toString()).containsEntry("podUid",e.pod().podUid().toString())
            .containsEntry("jobUid",e.pod().jobUid().toString()).containsEntry("expiresAt",runtime.expiresAt().toString());
        assertThat(document.get("workDigest")).asString().matches("sha256:[0-9a-f]{64}");
        assertThat(json.canonical(document)).doesNotContain(runtime.claimNonce().toString(),"private-work-parameter","X-Amz");
        assertThat(executions.taskDetail(e.task()).attempts()).hasSize(1);assertThat(runtimes.result(e.task())).isEmpty();
    }
    @Test void lostPublicationReplyRetriesTheActualFirstS3Record()throws Exception{
        var e=execution();doAnswer(call->{call.callRealMethod();throw new ArtifactStoreUnavailableException();}).doCallRealMethod().when(storage).retainStart(any());
        assertThat(claim(e,503)).contains("RUNTIME_UNAVAILABLE").doesNotContain("command");var first=versions();var document=journal(e);
        assertThat(first).hasSize(1);claim(e,200);assertThat(versions()).isEqualTo(first);assertThat(journal(e)).isEqualTo(document);
        assertThat(executions.taskDetail(e.task()).attempts()).hasSize(1);
    }
    @Test void unavailableRealBucketDoesNotReturnWorkAndCanRetryAfterStorageReturns()throws Exception{
        var e=execution();admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());created=false;
        assertThat(claim(e,503)).contains("RUNTIME_UNAVAILABLE").doesNotContain("command");
        createBucket();assertThat(versions()).isEmpty();claim(e,200);assertThat(versions()).hasSize(1);
        assertThat(executions.taskDetail(e.task()).attempts()).hasSize(1);
    }
    @Test void cancellationAfterActualPublicationPreventsTheStartResponse()throws Exception{
        var e=execution();doAnswer(call->{call.callRealMethod();executions.cancelTask(e.task(),"{}");return null;}).when(storage).retainStart(any());
        claim(e,409);assertThat(versions()).hasSize(1);assertThat(executions.taskDetail(e.task()).task().state()).isEqualTo("CANCELLING");
        assertThat(runtimes.result(e.task())).isEmpty();
    }
    @Test void conflictingStoredWorkCannotBeOverwrittenByAnApiRetry()throws Exception{
        var e=execution();claim(e,200);var altered=new TreeMap<>(journal(e));altered.put("workDigest","sha256:"+"b".repeat(64));
        byte[] body=json.canonical(altered).getBytes(StandardCharsets.UTF_8);
        admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key(e)).data(body,body.length).contentType("application/vnd.edgeai.runtime-start+json").build());
        var before=versions();assertThat(claim(e,400)).contains("ARTIFACT_INVALID").doesNotContain("command");assertThat(versions()).isEqualTo(before);
        assertThat(runtimes.result(e.task())).isEmpty();
    }
    @SuppressWarnings("unchecked") private Execution execution()throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of());
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Actual start journal"))).value();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",List.of(Map.of("key","root","serviceProfileVersionId",profile.id().toString(),"parameters",Map.of("private-work-parameter",9007199254740993L))),"dependencies",List.of()))).value();
        String response=mvc.perform(post("/api/v1/workflow-runs").with(user("fixture")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(json.canonical(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID run=UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id"));var task=executions.detail(run).tasks().getFirst();
        UUID attempt=executions.taskDetail(task.id()).attempts().getFirst().id();var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"journal-fixture-node");
        pods.put(attempt,pod);return new Execution(run,task.id(),attempt,pod);
    }
    private String claim(Execution e,int status)throws Exception{
        return mvc.perform(post("/internal/v1/attempts/"+e.attempt()+"/claim").header("Authorization","Bearer "+tokens.issue(runtimes.byAttempt(e.attempt()).orElseThrow()))
            .header("X-EdgeAI-Pod-Token","start-proof-fixture").contentType("application/json").content(json.canonical(Map.of("epoch",1,"podUid",e.pod().podUid().toString()))))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    private String key(Execution e){return "authority/runtime-start/"+runtimes.byAttempt(e.attempt()).orElseThrow().id()+".json";}
    @SuppressWarnings("unchecked") private Map<String,Object> journal(Execution e)throws Exception{
        var stat=admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key(e)).build());assertThat(stat.versionId()).isNotBlank();
        try(var input=admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key(e)).versionId(stat.versionId()).build())){
            return (Map<String,Object>)json.decode(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
    }
    private List<Map.Entry<String,String>> versions()throws Exception{
        var values=new ArrayList<Map.Entry<String,String>>();for(var result:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).includeVersions(true).build())){
            var value=result.get();assertThat(value.versionId()).isNotBlank();values.add(Map.entry(value.objectName(),value.versionId()));}return values;
    }
    private void createBucket()throws Exception{
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());created=true;
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());
    }
    private static Path key(){try{var key=Files.createTempFile("edgeai-start-journal-",".key");byte[] value=new byte[32];new java.security.SecureRandom().nextBytes(value);
        Files.writeString(key,HexFormat.of().formatHex(value));return key;}catch(Exception error){throw new IllegalStateException("Cannot create journal test key");}}
    private static String required(String name){return Objects.requireNonNull(System.getenv(name),"Actual journal test requires "+name);}
}
