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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL transactions/races. Kubernetes identities and storage receipts here are explicit fixtures. */
@SpringBootTest
class RuntimeIntegrationTest {
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired ExecutionService executions;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final JsonDocuments json=new JsonDocuments();
    private record Fixture(WorkflowRun run,UUID root,UUID child,UUID independent,UUID attempt,String namespace) {}
    private Fixture fixture(UUID nodeId) throws Exception { return fixture(nodeId,spec->{}); }
    @SuppressWarnings("unchecked") private Fixture fixture(UUID nodeId,Consumer<Map<String,Object>> change) throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        change.accept(spec);
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","runtime-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","runtime-"+UUID.randomUUID(),"displayName","Runtime state fixture"))).value();
        var tasks=List.of("root","child","independent").stream().map(key->Map.of("key",key,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of("threshold",1,"serial",9007199254740993L))).toList();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",tasks,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value();
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(Map.of("workflowVersionId",version.id().toString(),"parameters",Map.of("threshold",2),
            "execution",nodeId==null?Map.of("mode","AUTO"):Map.of("mode","NODE","nodeId",nodeId.toString())))).value();
        var values=executions.detail(run.id()).tasks();UUID root=id(values,"root");
        return new Fixture(run,root,id(values,"child"),id(values,"independent"),executions.taskDetail(root).attempts().getFirst().id(),"test-"+UUID.randomUUID());
    }
    private UUID id(List<Task> tasks,String key) { return tasks.stream().filter(t->t.key().equals(key)).findFirst().orElseThrow().id(); }
    @Test void streamingServiceCannotAccidentallyDispatchItsFinalizerAsBatch() throws Exception {
        var stream=(Map<?,?>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));
        var f=fixture(null,spec->{spec.put("stream",stream.get("stream"));spec.put("recovery",Map.of("mode","CHECKPOINT"));});
        assertThatThrownBy(()->lifecycle.plan(f.attempt(),f.namespace()))
            .isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("STREAM_NOT_IMPLEMENTED"));
        assertThat(runtimes.byAttempt(f.attempt())).isEmpty();
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("READY");
    }
    private RuntimePod running(Fixture f) {
        lifecycle.plan(f.attempt(),f.namespace());var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(f.attempt(),pod.jobUid());lifecycle.claim(f.attempt(),1,pod);return pod;
    }
    private ResultManifest manifest() { return new ResultManifest(List.of(new ResultManifest.Output("output",2,"a".repeat(64),"application/json",UUID.randomUUID().toString()))); }
    private VerifiedArtifact receipt(ArtifactContent value,String version) { return new VerifiedArtifact("fixture-only",value.objectKey(),version,value.sha256(),value.bytes(),value.mediaType()); }
    private ArtifactStore receiptStore() {
        return new ArtifactStore() {
            public ArtifactGrant upload(ArtifactContent c) { throw new UnsupportedOperationException(); }
            public VerifiedArtifact verify(ArtifactContent c,String v) { return receipt(c,v); }
            public ArtifactGrant download(VerifiedArtifact a) { throw new UnsupportedOperationException(); }
        };
    }
    private void fenced(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("PRODUCER_FENCED"));
    }
    @Test void concurrentPlanningCreatesOneRuntimeAndOneDurableCommand() throws Exception {
        var f=fixture(null);var plans=parallel(i->lifecycle.plan(f.attempt(),f.namespace()));
        assertThat(plans.stream().map(RuntimeInstance::id).distinct().count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_command WHERE runtime_id=?",Integer.class,plans.getFirst().id())).isEqualTo(1);
        assertThat(executions.taskDetail(f.root()).attempts().getFirst().state()).isEqualTo("DISPATCHING");
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("RUNNING");
        assertThat(executions.taskDetail(f.child()).attempts()).isEmpty();
    }
    @Test void invalidExecutionSpecAndIncompatibleDagCannotEnterDispatching() throws Exception {
        for(var mutation:List.<Consumer<Map<String,Object>>>of(
                spec->{spec.clear();spec.put("type","non-executable-registry-document");},
                spec->spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",true))),
                spec->spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1,"required",false))))) {
            var f=fixture(null,mutation);
            assertThatThrownBy(()->lifecycle.plan(f.attempt(),f.namespace())).isInstanceOf(IllegalArgumentException.class);
            assertThat(runtimes.byAttempt(f.attempt())).isEmpty();
            assertThat(executions.taskDetail(f.root()).attempts().getFirst().state()).isEqualTo("QUEUED");
        }
    }
    @Test void concurrentPodClaimsHaveOneProducerAndRepeatingItPreservesTheIdentity() throws Exception {
        var f=fixture(null);lifecycle.plan(f.attempt(),f.namespace());UUID job=UUID.randomUUID();lifecycle.submitted(f.attempt(),job);
        var claims=parallel(i->{
            var pod=new RuntimePod(job,UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
            try { return lifecycle.claim(f.attempt(),1,pod).runtime().producerPodUid(); }
            catch(ControlPlaneException e) { assertThat(e.code()).isEqualTo("PRODUCER_FENCED");return null; }
        });
        assertThat(claims.stream().filter(Objects::nonNull).count()).isEqualTo(1);
        var r=runtimes.byAttempt(f.attempt()).orElseThrow();
        var replay=lifecycle.claim(f.attempt(),1,new RuntimePod(job,r.producerPodUid(),r.nodeUid(),r.nodeName()));
        assertThat(replay.parametersJson()).contains("9007199254740993","\"threshold\":2");
        assertThat(replay.runtime().producerPodUid()).isEqualTo(r.producerPodUid());
        fenced(()->lifecycle.authorize(f.attempt(),2,r.producerPodUid()));
        fenced(()->lifecycle.submitted(f.attempt(),UUID.randomUUID()));
    }
    @Test void cancellationFencesClaimsAndLateCreateResponseReopensDeleteCommand() throws Exception {
        var f=fixture(null);var r=lifecycle.plan(f.attempt(),f.namespace());
        executions.cancelRun(f.run().id(),"{}");
        assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().desiredState()).isEqualTo("STOPPED");
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLING");
        UUID owner=UUID.randomUUID();
        var deleteId=jdbc.queryForObject("SELECT id FROM edgeai.runtime_command WHERE runtime_id=? AND kind='DELETE'",UUID.class,r.id());
        jdbc.update("UPDATE edgeai.runtime_command SET lease_owner=?,lease_until=now()+interval '1 minute' WHERE id=?",owner,deleteId);
        // DELETE may observe no Job before an already-sent CREATE reaches the Kubernetes API.
        lifecycle.confirmStopped(f.attempt());
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(f.attempt(),pod.jobUid());
        assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().observedState()).isEqualTo("SUBMITTED");
        assertThat(runtimes.finishCommand(deleteId,owner,Instant.now())).isFalse();
        assertThat(jdbc.queryForObject("SELECT completed FROM edgeai.runtime_command WHERE id=?",Boolean.class,deleteId)).isFalse();
        fenced(()->lifecycle.claim(f.attempt(),1,pod));
        lifecycle.confirmStopped(f.attempt());
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLED");
        assertThat(executions.taskDetail(f.root()).attempts().getFirst().state()).isEqualTo("CANCELLED");
    }
    @Test void concurrentVerifiedCommitsAreIdempotentAndReleaseChildOnlyOnce() throws Exception {
        var f=fixture(null);var pod=running(f);var manifest=manifest();var commits=new ArtifactCommitService(lifecycle,receiptStore());
        var results=parallel(i->commits.commit(f.attempt(),1,pod.podUid(),manifest));
        assertThat(results.stream().filter(Creation::created).count()).isEqualTo(1);
        assertThat(results.stream().map(r->r.value().id()).distinct().count()).isEqualTo(1);
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("SUCCEEDED");
        assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("READY");
        assertThat(executions.taskDetail(f.child()).attempts()).hasSize(1);
        UUID child=executions.taskDetail(f.child()).attempts().getFirst().id();
        lifecycle.plan(child,f.namespace());var childPod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(child,childPod.jobUid());var assignment=lifecycle.claim(child,1,childPod);
        assertThat(assignment.inputs()).hasSize(1);
        assertThat(assignment.inputs().getFirst().artifact().versionId()).isEqualTo(manifest.outputs().getFirst().versionId());
        fenced(()->commits.commit(f.attempt(),1,UUID.randomUUID(),manifest));
        fenced(()->commits.commit(f.attempt(),1,pod.podUid(),manifest()));
        var result=results.getFirst().value();
        assertThatThrownBy(()->executions.cancelTask(f.root(),"{}")).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("CANNOT_CANCEL"));
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.task_result SET manifest_digest=manifest_digest WHERE id=?",result.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.result_artifact SET bytes=0 WHERE result_id=?",result.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO edgeai.result_artifact(id,result_id,port,bucket,object_key,object_version,sha256,bytes,media_type)
            VALUES (?,?,'late','fixture','key','version',?,1,'application/json')
            """,UUID.randomUUID(),result.id(),"b".repeat(64))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        lifecycle.confirmStopped(f.attempt());
        assertThat(commits.commit(f.attempt(),1,pod.podUid(),manifest).value().id()).isEqualTo(result.id());
    }
    @Test void cancellationDuringStorageReadWinsWithoutWaitingForExternalIo() throws Exception {
        var f=fixture(null);var pod=running(f);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        ArtifactStore blocked=new ArtifactStore() {
            public ArtifactGrant upload(ArtifactContent c) { throw new UnsupportedOperationException(); }
            public ArtifactGrant download(VerifiedArtifact a) { throw new UnsupportedOperationException(); }
            public VerifiedArtifact verify(ArtifactContent c,String v) {
                entered.countDown();try { if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Fixture timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Fixture interrupted"); }
                return receipt(c,v);
            }
        };
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->new ArtifactCommitService(lifecycle,blocked).commit(f.attempt(),1,pod.podUid(),manifest()));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                executions.cancelTask(f.root(),"{}");
                assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("SKIPPED");
            } finally { release.countDown(); }
            assertThatThrownBy(()->future.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ControlPlaneException.class);
        }
        assertThat(runtimes.result(f.root())).isEmpty();
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("CANCELLING");
        lifecycle.confirmStopped(f.attempt());assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("CANCELLED");
    }
    @Test void storageRejectionAndWrongManifestCannotCreateResultOrReleaseChildren() throws Exception {
        var f=fixture(null);var pod=running(f);var calls=new AtomicInteger();
        ArtifactStore rejecting=new ArtifactStore() {
            public ArtifactGrant upload(ArtifactContent c) { throw new UnsupportedOperationException(); }
            public ArtifactGrant download(VerifiedArtifact a) { throw new UnsupportedOperationException(); }
            public VerifiedArtifact verify(ArtifactContent c,String v) { calls.incrementAndGet();throw new ArtifactVerificationException("Fixture rejects bytes"); }
        };
        var commit=new ArtifactCommitService(lifecycle,rejecting);
        assertThatThrownBy(()->commit.commit(f.attempt(),1,pod.podUid(),manifest())).isInstanceOf(ArtifactVerificationException.class);
        var wrong=new ResultManifest(List.of(new ResultManifest.Output("output",1048577,"a".repeat(64),"application/json","version")));
        assertThatThrownBy(()->commit.commit(f.attempt(),1,pod.podUid(),wrong)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(1);assertThat(runtimes.result(f.root())).isEmpty();
        assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("WAITING");
        assertThat(executions.taskDetail(f.child()).attempts()).isEmpty();
    }
    @Test void databaseWriteFailureRollsBackResultHeaderAndAllStateTransitions() throws Exception {
        var f=fixture(null);var pod=running(f);var manifest=manifest();
        var permit=lifecycle.prepareCommit(f.attempt(),1,pod.podUid(),manifest);var output=manifest.outputs().getFirst();
        var expected=output.content(f.root(),f.attempt());
        // Force a real DB constraint failure after the unsealed result header has been inserted.
        var invalid=new VerifiedArtifact("x".repeat(64),expected.objectKey(),output.versionId(),output.sha256(),output.bytes(),output.mediaType());
        assertThatThrownBy(()->lifecycle.commitVerified(permit,List.of(new TaskResult.Output("output",invalid))))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.task_result WHERE task_id=?",Integer.class,f.root())).isZero();
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("RUNNING");
        assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("WAITING");
        assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().desiredState()).isEqualTo("RUNNING");
    }
    @Test void failureSkipsDescendantsPreservesIndependentBranchAndFencesLateResults() throws Exception {
        var f=fixture(null);var pod=running(f);
        lifecycle.fail(f.attempt(),1,pod.podUid(),"WORKLOAD_FAILED");lifecycle.fail(f.attempt(),1,pod.podUid(),"WORKLOAD_FAILED");
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("FAILED");
        assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("SKIPPED");
        assertThat(executions.taskDetail(f.independent()).task().state()).isEqualTo("READY");
        fenced(()->lifecycle.prepareCommit(f.attempt(),1,pod.podUid(),manifest()));
        lifecycle.confirmStopped(f.attempt());executions.cancelTask(f.independent(),"{}");
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("FAILED");
    }
    @Test void nodePolicyBindsUidAndNameAndExpiredRuntimeRejectsProducer() throws Exception {
        UUID node=UUID.randomUUID();String name="runtime-node-"+UUID.randomUUID();
        jdbc.update("INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES (?,?,'amd64','linux','READY','1','1Gi','{}',now())",node,name);
        var f=fixture(node);lifecycle.plan(f.attempt(),f.namespace());UUID job=UUID.randomUUID();lifecycle.submitted(f.attempt(),job);
        fenced(()->lifecycle.claim(f.attempt(),1,new RuntimePod(job,UUID.randomUUID(),UUID.randomUUID(),name)));
        fenced(()->lifecycle.claim(f.attempt(),1,new RuntimePod(job,UUID.randomUUID(),node,"wrong-name")));
        var pod=new RuntimePod(job,UUID.randomUUID(),node,name);lifecycle.claim(f.attempt(),1,pod);
        jdbc.update("UPDATE edgeai.runtime_instance SET expires_at=now()-interval '1 second' WHERE attempt_id=?",f.attempt());
        fenced(()->lifecycle.authorize(f.attempt(),1,pod.podUid()));
    }
    @Test void durableCommandLeaseHasOneOwnerAndRecoversAfterExpiration() throws Exception {
        var f=fixture(null);lifecycle.plan(f.attempt(),f.namespace());Instant now=Instant.now().plusSeconds(1);
        var tx=new TransactionTemplate(transactions);
        var commands=parallel(i->tx.execute(s->runtimes.leaseCommand(f.namespace(),UUID.randomUUID(),now,Duration.ofSeconds(1))));
        assertThat(commands.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        var first=commands.stream().filter(Optional::isPresent).findFirst().orElseThrow().orElseThrow();
        var next=tx.execute(s->runtimes.leaseCommand(f.namespace(),UUID.randomUUID(),now.plusSeconds(2),Duration.ofSeconds(1))).orElseThrow();
        assertThat(next.id()).isEqualTo(first.id());assertThat(next.attempts()).isEqualTo(2);
        assertThat(runtimes.finishCommand(first.id(),first.leaseOwner(),now.plusSeconds(2))).isFalse();
        assertThat(runtimes.finishCommand(next.id(),next.leaseOwner(),now.plusSeconds(2))).isTrue();
        assertThat(runtimes.leaseCommand(f.namespace(),UUID.randomUUID(),now.plusSeconds(4),Duration.ofSeconds(1))).isEmpty();
    }
    @Test void unverifiedSuccessStateDoesNotReleaseDownstreamTask() throws Exception {
        var f=fixture(null);jdbc.update("UPDATE edgeai.task SET state='SUCCEEDED' WHERE id=?",f.root());
        new TransactionTemplate(transactions).execute(s->{runtimes.releaseReadyChildren(f.run().id(),Instant.now());return null;});
        assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("WAITING");
    }
    private <T> List<T> parallel(IntFunction<T> call) throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<8;i++){int index=i;futures.add(executor.submit(()->{start.await();return call.apply(index);}));}
            start.countDown();var result=new ArrayList<T>();for(var f:futures)result.add(f.get(20,TimeUnit.SECONDS));return result;
        }
    }
}
