package io.edgeai.app.integration;

import io.edgeai.app.config.RuntimeSettings;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual PostgreSQL and worker/lifecycle; Kubernetes responses are explicit fixtures. */
@SpringBootTest
class RuntimeWorkerIntegrationTest {
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired ExecutionService executions;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    private final JsonDocuments json=new JsonDocuments();
    private final RuntimeGateway gateway=mock(RuntimeGateway.class);
    private Path key;
    private RunnerTokenService tokens;
    private record Fixture(UUID run,UUID task,UUID child,UUID attempt,String namespace) {}
    @BeforeEach void key() throws Exception {
        key=Files.createTempFile("edgeai-worker-test-",".key");byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        Files.writeString(key,HexFormat.of().formatHex(bytes));tokens=new RunnerTokenService(key.toString());
    }
    @AfterEach void cleanup() throws Exception {Files.deleteIfExists(key);}
    @SuppressWarnings("unchecked") private Fixture fixture() throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","worker-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var w=workflows.create(json.canonical(Map.of("key","worker-"+UUID.randomUUID(),"displayName","Worker recovery fixture"))).value();
        var tasks=List.of("root","child").stream().map(k->Map.of("key",k,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())).toList();
        var v=workflows.publish(w.id(),json.canonical(Map.of("version","1.0.0","tasks",tasks,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value();
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(Map.of("workflowVersionId",v.id().toString(),"parameters",Map.of(),"execution",Map.of("mode","AUTO")))).value();
        var values=executions.detail(run.id()).tasks();
        UUID task=values.stream().filter(t->t.key().equals("root")).findFirst().orElseThrow().id(),child=values.stream().filter(t->t.key().equals("child")).findFirst().orElseThrow().id();
        var f=new Fixture(run.id(),task,child,executions.taskDetail(task).attempts().getFirst().id(),"worker-test-"+UUID.randomUUID());
        lifecycle.plan(f.attempt(),f.namespace());return f;
    }
    private RuntimeInstance runtime(Fixture f){return runtimes.byAttempt(f.attempt()).orElseThrow();}
    private RuntimeWorker worker(Fixture f,Instant now){return new RuntimeWorker(runtimes,lifecycle,gateway,tokens,
        new RuntimeSettings(f.namespace(),"edgeai-runner",URI.create("http://fixture-api:18080"),120),Clock.fixed(now,ZoneOffset.UTC));}
    private RuntimeGateway.Snapshot snapshot(Fixture f,UUID uid,String state){var r=runtime(f);return new RuntimeGateway.Snapshot(Map.of(f.attempt(),
        new RuntimeGateway.JobObservation(f.attempt(),f.task(),f.run(),r.epoch(),r.jobName(),uid,state)),"101");}
    private void readyCommands(Fixture f){jdbc.update("UPDATE edgeai.runtime_command SET available_at=now()-interval '1 second' WHERE runtime_id=?",runtime(f).id());}
    @Test void ambiguousCreateSurvivesNewWorkerWithoutAnotherPhysicalJob() throws Exception {
        var f=fixture();var uid=UUID.randomUUID();var physical=new HashMap<UUID,UUID>();var calls=new AtomicInteger();
        when(gateway.ensureJob(any(),any(),any())).thenAnswer(call->{
            RuntimeInstance r=call.getArgument(0);physical.putIfAbsent(r.attemptId(),uid);
            if(calls.getAndIncrement()==0)throw new RuntimeGatewayException(RuntimeGatewayException.Reason.UNAVAILABLE);
            return physical.get(r.attemptId());
        });
        assertThat(worker(f,Instant.now()).dispatchOne()).isTrue();assertThat(runtime(f).jobUid()).isNull();
        readyCommands(f);tokens=new RunnerTokenService(key.toString());
        assertThat(worker(f,Instant.now()).dispatchOne()).isTrue();
        assertThat(runtime(f).jobUid()).isEqualTo(uid);assertThat(runtime(f).observedState()).isEqualTo("SUBMITTED");
        assertThat(physical).hasSize(1);assertThat(calls.get()).isEqualTo(2);assertThat(worker(f,Instant.now()).dispatchOne()).isFalse();
    }
    @Test void cancelledRunWaitsForPhysicalStopAndLateJobReopensDeletion() throws Exception {
        var f=fixture();executions.cancelRun(f.run(),"{}");when(gateway.stop(any())).thenReturn(false);
        var w=worker(f,Instant.now().plusSeconds(1));w.commands();
        assertThat(executions.detail(f.run()).run().state()).isEqualTo("CANCELLING");
        assertThat(runtime(f).observedState()).isNotEqualTo("TERMINATED");verify(gateway,never()).ensureJob(any(),any(),any());
        when(gateway.stop(any())).thenReturn(true);readyCommands(f);worker(f,Instant.now().plusSeconds(1)).commands();
        assertThat(runtime(f).observedState()).isEqualTo("TERMINATED");assertThat(executions.detail(f.run()).run().state()).isEqualTo("CANCELLED");
        UUID late=UUID.randomUUID();when(gateway.listJobs()).thenReturn(snapshot(f,late,"ACTIVE"));
        worker(f,Instant.now()).reconcile();assertThat(runtime(f).observedState()).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT completed FROM edgeai.runtime_command WHERE runtime_id=? AND kind='DELETE'",Boolean.class,runtime(f).id())).isFalse();
        worker(f,Instant.now().plusSeconds(1)).commands();assertThat(runtime(f).observedState()).isEqualTo("TERMINATED");
    }
    @Test void observedFailureMissingJobOrCompleteWithoutResultCannotBecomeSuccess() throws Exception {
        for(String state:List.of("FAILED","COMPLETE","ABSENT")){
            var f=fixture();UUID uid=UUID.randomUUID();lifecycle.submitted(f.attempt(),uid);
            when(gateway.listJobs()).thenReturn(state.equals("ABSENT")?new RuntimeGateway.Snapshot(Map.of(),"101"):snapshot(f,uid,state));
            worker(f,Instant.now()).reconcile();
            assertThat(executions.taskDetail(f.task()).task().state()).isEqualTo("FAILED");
            assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("SKIPPED");assertThat(runtimes.result(f.task())).isEmpty();
            assertThat(runtime(f).failureReason()).isEqualTo(switch(state){case "FAILED"->"JOB_FAILED";case "COMPLETE"->"RESULT_MISSING";default->"RUNTIME_LOST";});
        }
    }
    @Test void dispatchAndExecutionDeadlinesPersistFailureAndStopCommands() throws Exception {
        var pending=fixture();when(gateway.listJobs()).thenReturn(new RuntimeGateway.Snapshot(Map.of(),"101"));
        worker(pending,runtime(pending).createdAt().plusSeconds(121)).reconcile();
        assertThat(runtime(pending).failureReason()).isEqualTo("DISPATCH_TIMEOUT");
        var submitted=fixture();UUID uid=UUID.randomUUID();lifecycle.submitted(submitted.attempt(),uid);
        when(gateway.listJobs()).thenReturn(snapshot(submitted,uid,"ACTIVE"));
        worker(submitted,runtime(submitted).expiresAt()).reconcile();assertThat(runtime(submitted).failureReason()).isEqualTo("RUNTIME_TIMEOUT");
        assertThat(runtime(submitted).desiredState()).isEqualTo("STOPPED");
    }
    @Test void jobCreatedAfterListSnapshotIsNotMisclassifiedAsLostAndWatchExpirationRelists() throws Exception {
        var f=fixture();UUID uid=UUID.randomUUID();
        when(gateway.listJobs()).thenAnswer(call->{lifecycle.submitted(f.attempt(),uid);return new RuntimeGateway.Snapshot(Map.of(),"101");});
        when(gateway.watchJobs("101")).thenReturn(null);
        worker(f,Instant.now()).reconcile();assertThat(runtime(f).failureReason()).isNull();
        when(gateway.listJobs()).thenReturn(snapshot(f,uid,"ACTIVE"));
        worker(f,Instant.now()).reconcile();verify(gateway,times(2)).listJobs();verify(gateway,times(2)).watchJobs("101");
        assertThat(runtime(f).desiredState()).isEqualTo("RUNNING");
    }
    @Test void verifiedResultRemainsSuccessfulWhenJobCompletesOrLaterDisappears() throws Exception {
        var f=fixture();var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(f.attempt(),pod.jobUid());lifecycle.claim(f.attempt(),1,pod);
        var output=new ResultManifest.Output("output",2,"a".repeat(64),"application/json","fixture-version");
        var permit=lifecycle.prepareCommit(f.attempt(),1,pod.podUid(),new ResultManifest(List.of(output)));
        lifecycle.commitVerified(permit,List.of(new TaskResult.Output("output",new VerifiedArtifact("fixture-only",output.content(f.task(),f.attempt()).objectKey(),output.versionId(),output.sha256(),output.bytes(),output.mediaType()))));
        when(gateway.listJobs()).thenReturn(snapshot(f,pod.jobUid(),"COMPLETE"),new RuntimeGateway.Snapshot(Map.of(),"102"));
        worker(f,Instant.now()).reconcile();worker(f,Instant.now()).reconcile();
        assertThat(executions.taskDetail(f.task()).task().state()).isEqualTo("SUCCEEDED");assertThat(runtime(f).failureReason()).isNull();
        assertThat(runtimes.result(f.task())).isPresent();assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("READY");
    }
}
