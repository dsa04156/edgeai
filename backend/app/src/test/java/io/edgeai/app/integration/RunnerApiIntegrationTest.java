package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real DB + actual Spring security/controller chains. Kubernetes proof and S3 receipts are explicit fixtures. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class RunnerApiIntegrationTest {
    private static final Path KEY=key();
    private static final String NAMESPACE="api-test-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("edgeai.runtime.key-file",KEY::toString);registry.add("edgeai.runtime.namespace",()->NAMESPACE);}
    private static Path key(){try{var path=Files.createTempFile("edgeai-runner-key-test-",".key");byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);Files.writeString(path,HexFormat.of().formatHex(key));return path;}catch(Exception e){throw new IllegalStateException("Cannot create test key");}}
    @AfterAll static void removeKey() throws Exception {Files.deleteIfExists(KEY);}
    @Autowired MockMvc mvc;
    @Autowired ProfileService profiles;
    @Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;
    @Autowired RuntimeRepository runtimes;
    @Autowired RunnerTokenService tokens;
    @MockitoBean RuntimeGateway gateway;
    @MockitoBean S3ArtifactStore storage;
    private final JsonDocuments json=new JsonDocuments();
    private final Map<UUID,RuntimePod> pods=new ConcurrentHashMap<>();
    private record Execution(UUID run,UUID task,UUID child,UUID attempt,RuntimePod pod) {}
    @BeforeEach void boundaries(){
        when(gateway.authenticatePod(any(),any())).thenAnswer(call->{
            RuntimeInstance runtime=call.getArgument(0);String token=call.getArgument(1);
            if(!"pod-proof-fixture".equals(token)||!pods.containsKey(runtime.attemptId()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return pods.get(runtime.attemptId());
        });
        when(storage.upload(any())).thenAnswer(call->new ArtifactGrant(URI.create("http://storage.fixture/upload"),Map.of("Content-Type","application/json"),Instant.now().plusSeconds(600)));
        when(storage.verify(any(),any())).thenAnswer(call->{ArtifactContent c=call.getArgument(0);return new VerifiedArtifact("fixture-only",c.objectKey(),call.getArgument(1),c.sha256(),c.bytes(),c.mediaType());});
    }
    @SuppressWarnings("unchecked") private Execution execution() throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","runner-api-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var w=workflows.create(json.canonical(Map.of("key","runner-api-"+UUID.randomUUID(),"displayName","Runner HTTP fixture"))).value();
        var tasks=List.of("root","child").stream().map(k->Map.of("key",k,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of("serial",9007199254740993L))).toList();
        var v=workflows.publish(w.id(),json.canonical(Map.of("version","1.0.0","tasks",tasks,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value();
        String body=json.canonical(Map.of("workflowVersionId",v.id().toString(),"parameters",Map.of(),"execution",Map.of("mode","AUTO")));
        String response=mvc.perform(post("/api/v1/workflow-runs").with(user("fixture")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID run=UUID.fromString((String)((Map<?,?>)json.decode(response)).get("id"));var values=executions.detail(run).tasks();
        UUID task=values.stream().filter(t->t.key().equals("root")).findFirst().orElseThrow().id(),child=values.stream().filter(t->t.key().equals("child")).findFirst().orElseThrow().id();
        UUID attempt=executions.taskDetail(task).attempts().getFirst().id();
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");pods.put(attempt,pod);
        assertThat(runtimes.byAttempt(attempt)).isPresent();return new Execution(run,task,child,attempt,pod);
    }
    private Map<String,Object> identity(Execution e){return Map.of("epoch",1,"podUid",e.pod().podUid().toString());}
    private String request(Execution e,String operation,Object value,int expected) throws Exception {
        String credential=tokens.issue(runtimes.byAttempt(e.attempt()).orElseThrow());
        return mvc.perform(post("/internal/v1/attempts/"+e.attempt()+"/"+operation).header("Authorization","Bearer "+credential).header("X-EdgeAI-Pod-Token","pod-proof-fixture")
            .contentType("application/json").content(json.canonical(value))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private Map<String,Object> output(boolean version){var output=new LinkedHashMap<String,Object>(Map.of("port","output","bytes",2,"sha256","a".repeat(64),"mediaType","application/json"));if(version)output.put("versionId","fixture-version");return output;}
    private Map<String,Object> with(Execution e,String key,Object value){var body=new LinkedHashMap<>(identity(e));body.put(key,value);return body;}
    @Test void actualSecurityChainClaimsUploadsAndCommitsThenAutomaticallyPlansChild() throws Exception {
        var e=execution();String resultPath="/api/v1/tasks/"+e.task()+"/results";
        mvc.perform(get(resultPath)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tasks/"+UUID.randomUUID()+"/results").with(user("fixture"))).andExpect(status().isNotFound());
        mvc.perform(get(resultPath).with(user("fixture"))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        String assignment=request(e,"claim",identity(e),200);assertThat(assignment).contains("9007199254740993");
        assertThat(request(e,"claim",identity(e),200)).contains(e.attempt().toString());
        assertThat(request(e,"uploads",with(e,"outputs",List.of(output(false))),200)).contains("http://storage.fixture/upload");
        String first=request(e,"commit",with(e,"outputs",List.of(output(true))),201);
        assertThat(request(e,"commit",with(e,"outputs",List.of(output(true))),200)).isEqualTo(first);
        assertThat(executions.taskDetail(e.task()).task().state()).isEqualTo("SUCCEEDED");
        String metadata=mvc.perform(get(resultPath).with(user("fixture"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.items[0].attemptId").value(e.attempt().toString()))
            .andExpect(jsonPath("$.items[0].artifacts[0].objectVersion").value("fixture-version"))
            .andExpect(jsonPath("$.items[0].artifacts[0].sha256").value("a".repeat(64)))
            .andReturn().getResponse().getContentAsString();
        assertThat(metadata).doesNotContain("http://","Authorization","token","headers");
        var child=executions.taskDetail(e.child());assertThat(child.task().state()).isEqualTo("RUNNING");assertThat(child.attempts()).hasSize(1);
        assertThat(child.attempts().getFirst().state()).isEqualTo("DISPATCHING");
        assertThat(runtimes.byAttempt(child.attempts().getFirst().id())).isPresent();
        assertThat(new RunnerTokenService(KEY.toString()).issue(runtimes.byAttempt(e.attempt()).orElseThrow())).isEqualTo(tokens.issue(runtimes.byAttempt(e.attempt()).orElseThrow()));
    }
    @Test void basicCrossAttemptWrongPodProofAndBodyIdentityCannotAuthenticateProducer() throws Exception {
        var e=execution();String path="/internal/v1/attempts/"+e.attempt()+"/claim",body=json.canonical(identity(e));
        mvc.perform(post(path).with(user("fixture")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization","Basic Zml4dHVyZTpmaXh0dXJl").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        var other=execution();String otherToken=tokens.issue(runtimes.byAttempt(other.attempt()).orElseThrow());
        mvc.perform(post(path).header("Authorization","Bearer "+otherToken).header("X-EdgeAI-Pod-Token","pod-proof-fixture").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        String token=tokens.issue(runtimes.byAttempt(e.attempt()).orElseThrow());
        mvc.perform(post(path).header("Authorization","Bearer "+token).header("X-EdgeAI-Pod-Token","wrong").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        request(e,"claim",Map.of("epoch",1,"podUid",UUID.randomUUID().toString()),409);
        request(e,"claim",Map.of("epoch",2,"podUid",e.pod().podUid().toString()),409);
        request(e,"claim",identity(e),200);
        // The stateless Runner chain does not relax CSRF for user-facing writes.
        mvc.perform(post("/api/v1/workflows").with(user("fixture")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void cancelledProducerAndInvalidOutputNeverGetStorageAuthority() throws Exception {
        var e=execution();request(e,"claim",identity(e),200);
        var extra=output(false);extra.put("bucket","untrusted");request(e,"uploads",with(e,"outputs",List.of(extra)),400);
        var large=output(false);large.put("bytes",1048577);request(e,"uploads",with(e,"outputs",List.of(large)),400);
        verify(storage,never()).upload(any());
        executions.cancelTask(e.task(),"{}");request(e,"uploads",with(e,"outputs",List.of(output(false))),409);
        request(e,"commit",with(e,"outputs",List.of(output(true))),409);verify(storage,never()).verify(any(),any());
        assertThat(runtimes.result(e.task())).isEmpty();
    }
    @Test void boundedBodyAndSanitizedArtifactErrorsPreserveTheActiveAttempt() throws Exception {
        var e=execution();request(e,"claim",identity(e),200);String path="/internal/v1/attempts/"+e.attempt()+"/commit";
        String token=tokens.issue(runtimes.byAttempt(e.attempt()).orElseThrow());
        mvc.perform(post(path).header("Authorization","Bearer "+token).header("X-EdgeAI-Pod-Token","pod-proof-fixture").contentType("application/json").content("x".repeat(262145))).andExpect(status().isPayloadTooLarge());
        doThrow(new ArtifactVerificationException("private diagnostic must not reach HTTP")).when(storage).verify(any(),any());
        assertThat(request(e,"commit",with(e,"outputs",List.of(output(true))),400)).contains("ARTIFACT_INVALID").doesNotContain("private diagnostic");
        assertThat(executions.taskDetail(e.task()).task().state()).isEqualTo("RUNNING");
        request(e,"fail",with(e,"reason","WORKLOAD_FAILED"),200);
        request(e,"fail",with(e,"reason","WORKLOAD_FAILED"),200);
        assertThat(executions.taskDetail(e.child()).task().state()).isEqualTo("SKIPPED");
    }
}
