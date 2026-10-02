package io.edgeai.app.integration;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL races and restartable state. Pod identities/storage receipts are explicit fixtures. */
@SpringBootTest
@Import(RetryIntegrationTest.TimeConfiguration.class)
class RetryIntegrationTest {
    static final class TestClock extends Clock {
        private volatile Instant time=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return time;}
        void advance(long seconds){time=time.plusSeconds(seconds);}
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary TestClock retryClock(){return new TestClock();} }
    @Autowired TestClock clock;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired ExecutionRepository repository;
    @Autowired ExecutionService executions;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    private final JsonDocuments json=new JsonDocuments();
    private record Fixture(WorkflowRun run,UUID root,UUID child,UUID attempt,String namespace) {}
    @SuppressWarnings("unchecked") private Fixture fixture(int attempts,int backoff,int elapsed,Set<String> codes) throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","retry-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","retry-"+UUID.randomUUID(),"displayName","Retry budget fixture"))).value();
        var tasks=List.of("root","child").stream().map(key->Map.of("key",key,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())).toList();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",tasks,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value();
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(Map.of("workflowVersionId",version.id().toString(),"parameters",Map.of(),"execution",Map.of("mode","AUTO"),
            "retry",Map.of("maxAttempts",attempts,"backoffSeconds",backoff,"maxElapsedSeconds",elapsed,"retryOn",codes.stream().sorted().toList())))).value();
        var values=executions.detail(run.id()).tasks();UUID root=values.stream().filter(t->t.key().equals("root")).findFirst().orElseThrow().id();
        var f=new Fixture(run,root,values.stream().filter(t->t.key().equals("child")).findFirst().orElseThrow().id(),executions.taskDetail(root).attempts().getFirst().id(),"retry-"+UUID.randomUUID());
        lifecycle.plan(f.attempt(),f.namespace());return f;
    }
    private Fixture fixture() throws Exception {return fixture(2,5,600,Set.of("WORKLOAD_FAILED","RUNTIME_LOST"));}
    private RuntimePod claim(UUID attempt,long epoch) {
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(attempt,pod.jobUid());lifecycle.claim(attempt,epoch,pod);completeCreate(attempt);return pod;
    }
    private void completeCreate(UUID attempt){jdbc.update("UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE kind='CREATE' AND runtime_id=(SELECT id FROM edgeai.runtime_instance WHERE attempt_id=?)",attempt);}
    private void fail(Fixture f){var pod=claim(f.attempt(),1);lifecycle.fail(f.attempt(),1,pod.podUid(),"WORKLOAD_FAILED");}
    private void states(Fixture f,String root,String child){assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo(root);assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo(child);}
    private ResultManifest manifest(){return new ResultManifest(List.of(new ResultManifest.Output("output",2,"a".repeat(64),"application/json","fixture-version")));}
    private List<TaskResult.Output> receipt(UUID task,UUID attempt){var o=manifest().outputs().getFirst();return List.of(new TaskResult.Output(o.port(),new VerifiedArtifact("fixture-only",o.content(task,attempt).objectKey(),o.versionId(),o.sha256(),o.bytes(),o.mediaType())));}

    @Test void retriesSameTaskAfterStopAndBackoffThenCommitsOnceAndReleasesChild() throws Exception {
        var f=fixture();var pod=claim(f.attempt(),1);var oldPermit=lifecycle.prepareCommit(f.attempt(),1,pod.podUid(),manifest());
        lifecycle.fail(f.attempt(),1,pod.podUid(),"WORKLOAD_FAILED");states(f,"RETRY_WAIT","WAITING");
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("RUNNING");
        assertThat(lifecycle.retryTask(f.root())).isFalse();clock.advance(5);
        assertThat(lifecycle.dueRetries(f.namespace())).containsExactly(f.root());
        assertThat(lifecycle.retryTask(f.root())).isFalse(); // Still a physical runtime.
        lifecycle.confirmStopped(f.attempt());assertThat(lifecycle.retryTask(f.root())).isTrue();
        var history=executions.taskDetail(f.root()).attempts();assertThat(history).hasSize(2);
        var next=history.getFirst();assertThat(next.taskId()).isEqualTo(f.root());assertThat(next.id()).isNotEqualTo(f.attempt());
        assertThat(next.number()).isEqualTo(2);assertThat(next.epoch()).isEqualTo(2);assertThat(history.getLast().state()).isEqualTo("FAILED");
        assertThatThrownBy(()->lifecycle.commitVerified(oldPermit,receipt(f.root(),f.attempt()))).isInstanceOf(ControlPlaneException.class);
        var nextPod=claim(next.id(),2);
        assertThatThrownBy(()->lifecycle.claim(next.id(),1,pod)).isInstanceOf(ControlPlaneException.class);
        var permit=lifecycle.prepareCommit(next.id(),2,nextPod.podUid(),manifest());
        var result=lifecycle.commitVerified(permit,receipt(f.root(),next.id()));
        assertThat(result.created()).isTrue();assertThat(lifecycle.commitVerified(permit,receipt(f.root(),next.id())).created()).isFalse();
        assertThat(runtimes.result(f.root()).orElseThrow().attemptId()).isEqualTo(next.id());states(f,"SUCCEEDED","READY");
        assertThat(executions.taskDetail(f.child()).attempts()).hasSize(1);assertThat(repository.retry(f.root())).isEmpty();
    }
    @Test void firstFailureDoesNotSkipChildButExhaustedBudgetDoes() throws Exception {
        var f=fixture();fail(f);lifecycle.confirmStopped(f.attempt());clock.advance(5);assertThat(lifecycle.retryTask(f.root())).isTrue();
        var next=executions.taskDetail(f.root()).attempts().getFirst();var pod=claim(next.id(),2);
        lifecycle.fail(next.id(),2,pod.podUid(),"WORKLOAD_FAILED");states(f,"FAILED","SKIPPED");
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("FAILED");
        assertThat(repository.retry(f.root())).isEmpty();assertThat(lifecycle.retryTask(f.root())).isFalse();
        assertThat(executions.taskDetail(f.child()).attempts()).isEmpty();
    }
    @Test void deadlineExpiresEvenWhenPhysicalCleanupCannotFinish() throws Exception {
        var f=fixture(3,5,10,Set.of("RUNTIME_LOST"));lifecycle.observeFailure(f.attempt(),"RUNTIME_LOST");
        states(f,"RETRY_WAIT","WAITING");clock.advance(10);assertThat(lifecycle.retryTask(f.root())).isTrue();states(f,"FAILED","SKIPPED");
        assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);
        assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().desiredState()).isEqualTo("STOPPED");
        assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().observedState()).isNotEqualTo("TERMINATED");
    }
    @Test void cancelDuringRetryWaitRemovesReservationAndWaitsForPhysicalStop() throws Exception {
        var f=fixture();fail(f);assertThat(executions.cancelRun(f.run().id(),"{}").state()).isEqualTo("CANCELLING");
        clock.advance(20);assertThat(lifecycle.retryTask(f.root())).isFalse();assertThat(repository.retry(f.root())).isEmpty();
        assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);
        lifecycle.confirmStopped(f.attempt());assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLED");
        assertThat(executions.taskDetail(f.root()).attempts().getFirst().state()).isEqualTo("FAILED");
    }
    @Test void unfinishedCreateBlocksReplacementAndLateOldStopDoesNotCompleteNewCancellation() throws Exception {
        var f=fixture();lifecycle.observeFailure(f.attempt(),"RUNTIME_LOST");lifecycle.confirmStopped(f.attempt());clock.advance(5);
        assertThat(lifecycle.retryTask(f.root())).isFalse();completeCreate(f.attempt());assertThat(lifecycle.retryTask(f.root())).isTrue();
        var next=executions.taskDetail(f.root()).attempts().getFirst();claim(next.id(),2);executions.cancelRun(f.run().id(),"{}");
        lifecycle.confirmStopped(f.attempt());assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("CANCELLING");
        lifecycle.confirmStopped(next.id());assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLED");
    }
    @Test void concurrentRecoveryCreatesExactlyOneAttemptAndRuntime() throws Exception {
        var f=fixture();fail(f);lifecycle.confirmStopped(f.attempt());assertThat(lifecycle.retryTask(f.root())).isFalse();clock.advance(5);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return lifecycle.retryTask(f.root());}));
            start.countDown();int created=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS))created++;
            assertThat(created).isEqualTo(1);
        }
        assertThat(executions.taskDetail(f.root()).attempts()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_instance WHERE task_id=?",Integer.class,f.root())).isEqualTo(2);
    }
    @Test void cancellationRacingRetryCannotLeaveANewAuthorizedProducer() throws Exception {
        var f=fixture();fail(f);lifecycle.confirmStopped(f.attempt());clock.advance(5);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var retry=pool.submit(()->{start.await();return lifecycle.retryTask(f.root());});
            var cancel=pool.submit(()->{start.await();return executions.cancelRun(f.run().id(),"{}");});
            start.countDown();retry.get(20,TimeUnit.SECONDS);cancel.get(20,TimeUnit.SECONDS);
        }
        assertThat(repository.retry(f.root())).isEmpty();
        var history=executions.taskDetail(f.root()).attempts();assertThat(history.size()).isBetween(1,2);
        for(var attempt:history) {
            assertThat(runtimes.byAttempt(attempt.id()).orElseThrow().desiredState()).isEqualTo("STOPPED");
            lifecycle.confirmStopped(attempt.id());
        }
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLED");
        assertThat(runtimes.result(f.root())).isEmpty();
    }
    @Test void invalidOutputAndUnlistedFailureNeverRetryAndTooShortWindowIsTerminal() throws Exception {
        var invalid=fixture();var pod=claim(invalid.attempt(),1);lifecycle.fail(invalid.attempt(),1,pod.podUid(),"OUTPUT_INVALID");states(invalid,"FAILED","SKIPPED");
        var unlisted=fixture();lifecycle.observeFailure(unlisted.attempt(),"JOB_FAILED");states(unlisted,"FAILED","SKIPPED");
        var shortWindow=fixture(3,5,5,Set.of("RUNTIME_LOST"));lifecycle.observeFailure(shortWindow.attempt(),"RUNTIME_LOST");states(shortWindow,"FAILED","SKIPPED");
    }
    @Test void replayNormalizesCodeOrderAndDefaultPolicyWhileChangedBudgetConflicts() throws Exception {
        var f=fixture();var request=new TreeMap<String,Object>();request.put("workflowVersionId",f.run().workflowVersionId().toString());request.put("execution",Map.of("mode","AUTO"));request.put("parameters",Map.of());
        request.put("retry",Map.of("maxAttempts",2,"backoffSeconds",5,"maxElapsedSeconds",600,"retryOn",List.of("RUNTIME_LOST","WORKLOAD_FAILED")));
        assertThat(executions.create(f.run().idempotencyKey().toString(),json.canonical(request)).created()).isFalse();
        request.put("retry",Map.of("maxAttempts",3,"backoffSeconds",5,"maxElapsedSeconds",600,"retryOn",List.of("WORKLOAD_FAILED")));
        assertThatThrownBy(()->executions.create(f.run().idempotencyKey().toString(),json.canonical(request))).isInstanceOf(ControlPlaneException.class);
        request.remove("retry");var key=UUID.randomUUID().toString();var plain=executions.create(key,json.canonical(request));
        request.put("retry",Map.of("maxAttempts",1,"backoffSeconds",1,"maxElapsedSeconds",86400,"retryOn",List.of()));
        assertThat(executions.create(key,json.canonical(request)).value().id()).isEqualTo(plain.value().id());
        assertThat(plain.value().retry()).isEqualTo(RetryPolicy.disabled());
    }
}
