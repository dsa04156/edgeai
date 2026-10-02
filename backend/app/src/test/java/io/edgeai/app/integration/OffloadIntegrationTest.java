package io.edgeai.app.integration;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL state/races; Pod identities and artifact receipts here are explicit fixtures. */
@SpringBootTest
@Import(RetryIntegrationTest.TimeConfiguration.class)
class OffloadIntegrationTest {
    @Autowired RetryIntegrationTest.TestClock clock;
    @Autowired OffloadService offloads;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired ExecutionRepository repository;
    @Autowired ExecutionService executions;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired NodeService nodes;
    @Autowired JdbcTemplate jdbc;
    private final JsonDocuments json=new JsonDocuments();
    private record Fixture(WorkflowRun run,UUID root,UUID child,UUID attempt,UUID sourceNode,UUID targetNode,RuntimePod pod,String namespace) {}
    private ExecutionNode node(UUID id,String name,String status){return new ExecutionNode(id,name,"amd64","linux",status,"4","4Gi","{}",clock.instant());}
    @SuppressWarnings("unchecked") private Fixture fixture(boolean restartable,boolean claim) throws Exception {
        UUID source=UUID.randomUUID(),target=UUID.randomUUID();nodes.recordSnapshot(List.of(node(source,"source-"+source,"READY"),node(target,"target-"+target,"READY")),clock.instant());
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        spec.put("timeoutSeconds",600);if(restartable)spec.put("recovery",Map.of("mode","RESTART"));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","offload-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","offload-"+UUID.randomUUID(),"displayName","Running offload fixture"))).value();
        var definitions=List.of("root","child").stream().map(key->Map.of("key",key,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())).toList();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",definitions,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value();
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","NODE","nodeId",source.toString()),"parameters",Map.of(),
            "retry",Map.of("maxAttempts",2,"backoffSeconds",5,"maxElapsedSeconds",600,"retryOn",List.of("WORKLOAD_FAILED"))))).value();
        var tasks=executions.detail(run.id()).tasks();UUID root=tasks.stream().filter(t->t.key().equals("root")).findFirst().orElseThrow().id();
        UUID attempt=executions.taskDetail(root).attempts().getFirst().id();String namespace="offload-"+UUID.randomUUID();lifecycle.plan(attempt,namespace);
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),source,"source-"+source);
        if(claim){lifecycle.submitted(attempt,pod.jobUid());lifecycle.claim(attempt,1,pod);completeCreate(attempt);}
        return new Fixture(run,root,tasks.stream().filter(t->t.key().equals("child")).findFirst().orElseThrow().id(),attempt,source,target,pod,namespace);
    }
    private Fixture fixture() throws Exception {return fixture(true,true);}
    private String body(Fixture f,UUID target,int drain,int start){return json.canonical(Map.of("sourceAttemptId",f.attempt().toString(),"targetNodeId",target.toString(),"drainTimeoutSeconds",drain,"startTimeoutSeconds",start));}
    private OffloadOperation request(Fixture f){return offloads.request(f.root(),UUID.randomUUID().toString(),body(f,f.targetNode(),60,120)).value();}
    private void completeCreate(UUID attempt){jdbc.update("UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE kind='CREATE' AND runtime_id=(SELECT id FROM edgeai.runtime_instance WHERE attempt_id=?)",attempt);}
    private TaskAttempt start(Fixture f,OffloadOperation operation){lifecycle.confirmStopped(f.attempt());offloads.advance(operation.id());return executions.taskDetail(f.root()).attempts().getFirst();}
    private RuntimePod claimTarget(TaskAttempt attempt,UUID node){var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),node,"target-"+node);lifecycle.submitted(attempt.id(),pod.jobUid());lifecycle.claim(attempt.id(),attempt.epoch(),pod);completeCreate(attempt.id());return pod;}
    private ResultManifest manifest(){return new ResultManifest(List.of(new ResultManifest.Output("output",2,"a".repeat(64),"application/json","fixture-version")));}
    private List<TaskResult.Output> receipt(UUID task,UUID attempt){var o=manifest().outputs().getFirst();return List.of(new TaskResult.Output("output",new VerifiedArtifact("fixture-only",o.content(task,attempt).objectKey(),o.versionId(),o.sha256(),o.bytes(),o.mediaType())));}

    @Test void runningTransferFencesOldProducerWaitsForStopAndClaimsOnlyNewTarget() throws Exception {
        var f=fixture();var oldPermit=lifecycle.prepareCommit(f.attempt(),1,f.pod().podUid(),manifest());var operation=request(f);
        assertThat(operation.state()).isEqualTo("DRAINING");assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("OFFLOADING");
        assertThat(executions.taskDetail(f.root()).attempts().getFirst().state()).isEqualTo("OFFLOADED");
        assertThatThrownBy(()->lifecycle.authorize(f.attempt(),1,f.pod().podUid())).isInstanceOf(ControlPlaneException.class);
        assertThatThrownBy(()->lifecycle.commitVerified(oldPermit,receipt(f.root(),f.attempt()))).isInstanceOf(ControlPlaneException.class);
        offloads.advance(operation.id());assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);
        var next=start(f,operation);assertThat(next.number()).isEqualTo(2);assertThat(next.epoch()).isEqualTo(2);assertThat(next.cause()).isEqualTo("OFFLOAD");
        assertThat(next.nodeId()).isEqualTo(f.targetNode());assertThat(next.taskId()).isEqualTo(f.root());assertThat(lifecycle.dispatch(next.id()).nodeId()).isEqualTo(f.targetNode());
        assertThat(executions.detail(f.run().id()).run().nodeId()).isEqualTo(f.sourceNode());
        assertThat(offloads.find(operation.id()).state()).isEqualTo("STARTING");
        var wrong=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),f.sourceNode(),f.pod().nodeName());lifecycle.submitted(next.id(),wrong.jobUid());
        assertThatThrownBy(()->lifecycle.claim(next.id(),2,wrong)).isInstanceOf(ControlPlaneException.class);
        var pod=new RuntimePod(wrong.jobUid(),UUID.randomUUID(),f.targetNode(),"target-"+f.targetNode());lifecycle.claim(next.id(),2,pod);
        assertThat(offloads.find(operation.id()).state()).isEqualTo("SUCCEEDED");
        var permit=lifecycle.prepareCommit(next.id(),2,pod.podUid(),manifest());lifecycle.commitVerified(permit,receipt(f.root(),next.id()));
        assertThat(runtimes.result(f.root()).orElseThrow().attemptId()).isEqualTo(next.id());
        assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("READY");assertThat(executions.taskDetail(f.child()).attempts()).hasSize(1);
        assertThat(executions.taskDetail(f.root()).offloads()).hasSize(1);
    }
    @Test void concurrentIdempotentCommandsCreateOneOperationAndDifferentInputConflicts() throws Exception {
        var f=fixture();String key=UUID.randomUUID().toString(),body=body(f,f.targetNode(),60,120);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Creation<OffloadOperation>>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return offloads.request(f.root(),key,body);}));start.countDown();
            var results=new ArrayList<Creation<OffloadOperation>>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(Creation::created)).hasSize(1);assertThat(results.stream().map(r->r.value().id()).distinct()).hasSize(1);
        }
        assertThatThrownBy(()->offloads.request(f.root(),key,body(f,f.targetNode(),61,120))).isInstanceOf(ControlPlaneException.class);
        assertThatThrownBy(()->request(f)).isInstanceOf(ControlPlaneException.class);
        assertThat(executions.taskDetail(f.root()).offloads()).hasSize(1);
    }
    @Test void pendingNonRestartableSameNodeAndStaleTargetAreRejectedBeforeFencing() throws Exception {
        var pending=fixture(true,false);assertThatThrownBy(()->request(pending)).isInstanceOf(ControlPlaneException.class);
        var stateful=fixture(false,true);assertThatThrownBy(()->request(stateful)).isInstanceOf(ControlPlaneException.class);
        var same=fixture();assertThatThrownBy(()->offloads.request(same.root(),UUID.randomUUID().toString(),body(same,same.sourceNode(),60,120))).isInstanceOf(ControlPlaneException.class);
        var stale=fixture();clock.advance(61);assertThatThrownBy(()->request(stale)).isInstanceOf(ControlPlaneException.class);
        for(var f:List.of(pending,stateful,same,stale)) {
            assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().desiredState()).isEqualTo("RUNNING");assertThat(executions.taskDetail(f.root()).offloads()).isEmpty();
        }
    }
    @Test void drainTimeoutNeverCreatesNewAttemptAndRetainsCleanup() throws Exception {
        var f=fixture();var o=offloads.request(f.root(),UUID.randomUUID().toString(),body(f,f.targetNode(),5,120)).value();clock.advance(5);offloads.advance(o.id());
        assertThat(offloads.find(o.id()).failureReason()).isEqualTo("SOURCE_DRAIN_TIMEOUT");
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("FAILED");assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("SKIPPED");
        assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().desiredState()).isEqualTo("STOPPED");
    }
    @Test void targetDeadlineFencesEvenBeforeWorkerObservesTimeout() throws Exception {
        var f=fixture();var o=offloads.request(f.root(),UUID.randomUUID().toString(),body(f,f.targetNode(),60,5)).value();var next=start(f,o);
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),f.targetNode(),"target-"+f.targetNode());lifecycle.submitted(next.id(),pod.jobUid());
        clock.advance(5);assertThatThrownBy(()->lifecycle.claim(next.id(),2,pod)).isInstanceOf(ControlPlaneException.class);
        offloads.advance(o.id());assertThat(offloads.find(o.id()).failureReason()).isEqualTo("TARGET_START_TIMEOUT");
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("FAILED");assertThat(runtimes.result(f.root())).isEmpty();
    }
    @Test void cancellingDuringDrainOrStartCannotResurrectTransfer() throws Exception {
        for(boolean started:List.of(false,true)) {
            var f=fixture();var o=request(f);if(started)start(f,o);
            executions.cancelRun(f.run().id(),"{}");offloads.advance(o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("CANCELLING");
            for(var attempt:executions.taskDetail(f.root()).attempts()){completeCreate(attempt.id());lifecycle.confirmStopped(attempt.id());}
            offloads.advance(o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("CANCELLED");
            clock.advance(180);offloads.advance(o.id());assertThat(executions.taskDetail(f.root()).attempts()).hasSize(started?2:1);
            assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLED");
        }
    }
    @Test void cancellationAfterSourceAlreadyDrainedDoesNotWaitForAnotherStopCallback() throws Exception {
        var f=fixture();var o=request(f);lifecycle.confirmStopped(f.attempt());
        assertThat(executions.cancelRun(f.run().id(),"{}").state()).isEqualTo("CANCELLED");
        offloads.advance(o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("CANCELLED");
        assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);
    }
    @Test void retriesAfterOffloadKeepTargetAndDoNotChargeOffloadAgainstRetryBudget() throws Exception {
        var f=fixture();var o=request(f);var next=start(f,o);var pod=claimTarget(next,f.targetNode());
        lifecycle.fail(next.id(),2,pod.podUid(),"WORKLOAD_FAILED");assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("RETRY_WAIT");
        lifecycle.confirmStopped(next.id());clock.advance(5);assertThat(lifecycle.retryTask(f.root())).isTrue();
        var retry=executions.taskDetail(f.root()).attempts().getFirst();assertThat(retry.number()).isEqualTo(3);assertThat(retry.cause()).isEqualTo("RETRY");assertThat(retry.nodeId()).isEqualTo(f.targetNode());
        var retryPod=claimTarget(retry,f.targetNode());lifecycle.fail(retry.id(),3,retryPod.podUid(),"WORKLOAD_FAILED");
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("FAILED");
        assertThat(offloads.find(o.id()).state()).isEqualTo("SUCCEEDED"); // Transfer success does not assert workload success.
    }
    @Test void incompleteCreateAndConcurrentRecoveryDoNotDuplicateTargetAttempt() throws Exception {
        var f=fixture();var o=request(f);lifecycle.confirmStopped(f.attempt());
        jdbc.update("UPDATE edgeai.runtime_command SET completed=false WHERE runtime_id=? AND kind='CREATE'",runtimes.byAttempt(f.attempt()).orElseThrow().id());
        offloads.advance(o.id());assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);completeCreate(f.attempt());
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<?>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->offloads.advance(o.id())));
            for(var future:futures)future.get(20,TimeUnit.SECONDS);
        }
        assertThat(executions.taskDetail(f.root()).attempts()).hasSize(2);assertThat(offloads.find(o.id()).state()).isEqualTo("STARTING");
    }
    @Test void eightTransfersRemainIndependentOfRetryBudgetAndNinthDoesNotFenceProducer() throws Exception {
        var f=fixture();UUID current=f.attempt();
        for(int i=0;i<8;i++) {
            UUID target=i%2==0?f.targetNode():f.sourceNode();
            String body=json.canonical(Map.of("sourceAttemptId",current.toString(),"targetNodeId",target.toString(),"drainTimeoutSeconds",60,"startTimeoutSeconds",120));
            var operation=offloads.request(f.root(),UUID.randomUUID().toString(),body).value();
            lifecycle.confirmStopped(current);offloads.advance(operation.id());
            var next=executions.taskDetail(f.root()).attempts().getFirst();
            var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),target,nodes.find(target).name());
            lifecycle.submitted(next.id(),pod.jobUid());lifecycle.claim(next.id(),next.epoch(),pod);completeCreate(next.id());current=next.id();
        }
        String body=json.canonical(Map.of("sourceAttemptId",current.toString(),"targetNodeId",f.targetNode().toString(),"drainTimeoutSeconds",60,"startTimeoutSeconds",120));
        assertThatThrownBy(()->offloads.request(f.root(),UUID.randomUUID().toString(),body)).isInstanceOf(ControlPlaneException.class)
            .hasMessageContaining("8회");
        var detail=executions.taskDetail(f.root());assertThat(detail.offloads()).hasSize(8).allMatch(o->o.state().equals("SUCCEEDED"));
        assertThat(detail.attempts()).hasSize(9);assertThat(detail.task().state()).isEqualTo("RUNNING");
        assertThat(runtimes.byAttempt(current).orElseThrow().desiredState()).isEqualTo("RUNNING");
    }
}
