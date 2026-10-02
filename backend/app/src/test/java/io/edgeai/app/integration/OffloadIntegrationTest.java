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
    @Autowired RuntimeTelemetryService telemetry;
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
    private Fixture fixture(boolean restartable,boolean claim) throws Exception {return fixture(restartable,claim,null);}
    @SuppressWarnings("unchecked") private Fixture fixture(boolean restartable,boolean claim,Map<String,Object> automatic) throws Exception {
        UUID source=UUID.randomUUID(),target=UUID.randomUUID();nodes.recordSnapshot(List.of(node(source,"source-"+source,"READY"),node(target,"target-"+target,"READY")),clock.instant());
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        spec.put("timeoutSeconds",600);if(restartable)spec.put("recovery",Map.of("mode","RESTART"));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","offload-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","offload-"+UUID.randomUUID(),"displayName","Running offload fixture"))).value();
        var definitions=List.of("root","child").stream().map(key->Map.of("key",key,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())).toList();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",definitions,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value();
        var request=new HashMap<String,Object>(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","NODE","nodeId",source.toString()),"parameters",Map.of(),
            "retry",Map.of("maxAttempts",2,"backoffSeconds",5,"maxElapsedSeconds",600,"retryOn",List.of("WORKLOAD_FAILED"))));
        if(automatic!=null)request.put("offload",automatic);
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(request)).value();
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
        var f=fixture();var oldPermit=lifecycle.prepareCommit(f.attempt(),1,f.pod().podUid(),manifest());
        var principal=new io.edgeai.app.config.RunnerPrincipal(f.attempt(),1,f.pod());
        var body=new LinkedHashMap<String,Object>(Map.of("epoch",1,"podUid",f.pod().podUid().toString(),"sequence",1,"observedAt",clock.instant().toString(),"intervalMillis",1000));
        for(String key:List.of("cpuUsageMicros","cpuLimitMillicores","memoryBytes","memoryLimitBytes","latencyMicros","latencyObservedAt"))body.put(key,null);
        body.put("memoryBytes",1000);telemetry.record(principal,json.canonical(body));
        assertThat(executions.taskDetail(f.root()).telemetry()).isNotNull();var operation=request(f);
        assertThat(operation.state()).isEqualTo("DRAINING");assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("OFFLOADING");
        assertThat(executions.taskDetail(f.root()).attempts().getFirst().state()).isEqualTo("OFFLOADED");
        assertThatThrownBy(()->lifecycle.authorize(f.attempt(),1,f.pod().podUid())).isInstanceOf(ControlPlaneException.class);
        assertThatThrownBy(()->lifecycle.commitVerified(oldPermit,receipt(f.root(),f.attempt()))).isInstanceOf(ControlPlaneException.class);
        assertThatThrownBy(()->telemetry.record(principal,json.canonical(body))).isInstanceOf(ControlPlaneException.class);
        offloads.advance(operation.id());assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);
        var next=start(f,operation);assertThat(next.number()).isEqualTo(2);assertThat(next.epoch()).isEqualTo(2);assertThat(next.cause()).isEqualTo("OFFLOAD");
        assertThat(executions.taskDetail(f.root()).telemetry()).isNull();
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
    private Map<String,Object> automatic(int limit) {
        var p=new LinkedHashMap<String,Object>(Map.of("memoryPercent",90,"consecutiveSamples",3,"maxSampleAgeSeconds",30,"maxGapSeconds",10,"minRunningSeconds",10,"cooldownSeconds",20,"maxTransfers",limit,"drainTimeoutSeconds",60,"startTimeoutSeconds",120));
        p.put("cpuPercent",null);p.put("latencyMicros",null);return p;
    }
    private void measurement(UUID attempt,long epoch,RuntimePod pod,long sequence,Long limit) {
        var m=new LinkedHashMap<String,Object>(Map.of("epoch",epoch,"podUid",pod.podUid().toString(),"sequence",sequence,"observedAt",clock.instant().toString(),"intervalMillis",5000,"memoryBytes",950));
        for(String key:List.of("cpuUsageMicros","cpuLimitMillicores","latencyMicros","latencyObservedAt"))m.put(key,null);
        m.put("memoryLimitBytes",limit);telemetry.record(new io.edgeai.app.config.RunnerPrincipal(attempt,epoch,pod),json.canonical(m));
    }
    private void hot(Fixture f) {clock.advance(10);for(int i=1;i<=3;i++){measurement(f.attempt(),1,f.pod(),i,1000L);if(i<3)clock.advance(5);}}
    @Test void automaticPolicyIsImmutableAndRequiresExplicitRestartableServices() throws Exception {
        assertThatThrownBy(()->fixture(false,false,automatic(1))).isInstanceOf(ControlPlaneException.class);
        var f=fixture(true,true,automatic(1));
        var body=new LinkedHashMap<String,Object>(Map.of("workflowVersionId",f.run().workflowVersionId().toString(),"execution",Map.of("mode","NODE","nodeId",f.sourceNode().toString()),"parameters",Map.of(),
            "retry",Map.of("maxAttempts",2,"backoffSeconds",5,"maxElapsedSeconds",600,"retryOn",List.of("WORKLOAD_FAILED")),"offload",automatic(1)));
        assertThat(executions.create(f.run().idempotencyKey().toString(),json.canonical(body)).created()).isFalse();
        body.put("offload",automatic(2));assertThatThrownBy(()->executions.create(f.run().idempotencyKey().toString(),json.canonical(body))).isInstanceOf(ControlPlaneException.class);
        assertThat(json.decode(repository.run(f.run().id(),false).orElseThrow().offloadPolicyJson())).isEqualTo(json.decode(json.canonical(automatic(1))));
    }
    @Test void concurrentAutomaticDecisionPersistsEvidenceAndSchedulerExclusionAndHonorsBudget() throws Exception {
        var f=fixture(true,true,automatic(1));hot(f);assertThat(offloads.automaticCandidates(f.namespace())).contains(f.root());
        assertThat(offloads.evaluate(f.root(),"wrong-namespace")).isEmpty();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<Optional<OffloadOperation>>>();var gate=new CountDownLatch(1);
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{gate.await();return offloads.evaluate(f.root(),f.namespace());}));gate.countDown();
            int created=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS).isPresent())created++;assertThat(created).isEqualTo(1);
        }
        var operation=executions.taskDetail(f.root()).offloads().getFirst();assertThat(operation.trigger()).isEqualTo("MEMORY");assertThat(operation.targetNodeId()).isNull();
        assertThat(operation.excludedNodeNames()).containsExactly(f.pod().nodeName());
        assertThatThrownBy(()->lifecycle.authorize(f.attempt(),1,f.pod().podUid())).isInstanceOf(ControlPlaneException.class);
        jdbc.update("DELETE FROM edgeai.runtime_telemetry WHERE attempt_id=?",f.attempt());assertThat(offloads.find(operation.id()).decisionJson()).contains("memoryPercent","950","sequence");
        var next=start(f,operation);assertThat(next.mode()).isEqualTo("AUTO");assertThat(next.excludedNodeNames()).containsExactly(f.pod().nodeName());
        var wrong=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),f.sourceNode(),f.pod().nodeName());lifecycle.submitted(next.id(),wrong.jobUid());
        assertThatThrownBy(()->lifecycle.claim(next.id(),2,wrong)).isInstanceOf(ControlPlaneException.class);
        var target=new RuntimePod(wrong.jobUid(),UUID.randomUUID(),f.targetNode(),"target-"+f.targetNode());lifecycle.claim(next.id(),2,target);completeCreate(next.id());
        assertThat(offloads.find(operation.id()).state()).isEqualTo("SUCCEEDED");
        clock.advance(20);for(int i=1;i<=3;i++){measurement(next.id(),2,target,i,1000L);clock.advance(5);}
        assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty();assertThat(executions.taskDetail(f.root()).offloads()).hasSize(1);
        lifecycle.fail(next.id(),2,target.podUid(),"WORKLOAD_FAILED");lifecycle.confirmStopped(next.id());clock.advance(5);lifecycle.retryTask(f.root());
        var retry=executions.taskDetail(f.root()).attempts().getFirst();assertThat(retry.cause()).isEqualTo("RETRY");assertThat(retry.mode()).isEqualTo("AUTO");assertThat(retry.excludedNodeNames()).containsExactly(f.pod().nodeName());
        assertThat(lifecycle.dispatch(retry.id()).excludedNodeNames()).containsExactly(f.pod().nodeName());
    }
    @Test void unknownLimitsMissingSamplesStaleEvidenceAndNoAlternativeNeverFence() throws Exception {
        var f=fixture(true,true,automatic(2));clock.advance(10);measurement(f.attempt(),1,f.pod(),1,null);clock.advance(5);measurement(f.attempt(),1,f.pod(),2,null);clock.advance(5);measurement(f.attempt(),1,f.pod(),3,null);
        assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty();
        for(int i:List.of(5,7,9)){clock.advance(5);measurement(f.attempt(),1,f.pod(),i,1000L);}assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty();
        for(int i:List.of(10,11,12)){clock.advance(5);measurement(f.attempt(),1,f.pod(),i,1000L);}clock.advance(31);
        assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty();
        nodes.recordSnapshot(List.of(node(f.sourceNode(),f.pod().nodeName(),"READY")),clock.instant());
        for(int i:List.of(13,14,15)){clock.advance(5);measurement(f.attempt(),1,f.pod(),i,1000L);}
        assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty();assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().desiredState()).isEqualTo("RUNNING");
        nodes.recordSnapshot(List.of(node(f.sourceNode(),f.pod().nodeName(),"READY"),node(f.targetNode(),"target-"+f.targetNode(),"READY")),clock.instant());
        assertThat(offloads.evaluate(f.root(),f.namespace())).isPresent();
        var disabled=fixture();hot(disabled);assertThat(offloads.evaluate(disabled.root(),disabled.namespace())).isEmpty();
    }
    @Test void automaticDecisionAndCancelShareTheRunLock() throws Exception {
        var f=fixture(true,true,automatic(1));hot(f);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();offloads.evaluate(f.root(),f.namespace());return true;});
            var b=pool.submit(()->{gate.await();executions.cancelTask(f.root(),"{}");return true;});gate.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }
        lifecycle.confirmStopped(f.attempt());for(var o:executions.taskDetail(f.root()).offloads())offloads.advance(o.id());
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("CANCELLED");assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);
        assertThat(executions.taskDetail(f.root()).offloads()).allMatch(o->o.state().equals("CANCELLED"));assertThat(runtimes.result(f.root())).isEmpty();
    }

    @Test void cooldownRequiresNewEvidenceAndAutomaticPlacementNeverReturnsToVisitedNodes() throws Exception {
        var f=fixture(true,true,automatic(2));hot(f);var first=offloads.evaluate(f.root(),f.namespace()).orElseThrow();
        var next=start(f,first);var target=claimTarget(next,f.targetNode());
        clock.advance(10);for(int i=1;i<=3;i++){measurement(next.id(),2,target,i,1000L);if(i<3)clock.advance(5);}
        assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty();
        clock.advance(5);measurement(next.id(),2,target,4,1000L);assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty();
        clock.advance(5);measurement(next.id(),2,target,5,1000L);
        nodes.recordSnapshot(List.of(node(f.sourceNode(),f.pod().nodeName(),"READY"),node(f.targetNode(),target.nodeName(),"READY")),clock.instant());
        assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty(); // Both already visited, despite fresh pressure.
        UUID third=UUID.randomUUID();nodes.recordSnapshot(List.of(node(f.sourceNode(),f.pod().nodeName(),"READY"),node(f.targetNode(),target.nodeName(),"READY"),node(third,"third-"+third,"READY")),clock.instant());
        var second=offloads.evaluate(f.root(),f.namespace()).orElseThrow();assertThat(second.excludedNodeNames()).containsExactlyInAnyOrder(f.pod().nodeName(),target.nodeName());
        assertThat(second.decisionJson()).contains("eligibleSince");assertThat(executions.taskDetail(f.root()).offloads()).hasSize(2);
    }
    @Test void committedResultCannotBeReplacedByLateAutomaticDecision() throws Exception {
        var f=fixture(true,true,automatic(1));hot(f);
        var permit=lifecycle.prepareCommit(f.attempt(),1,f.pod().podUid(),manifest());lifecycle.commitVerified(permit,receipt(f.root(),f.attempt()));
        assertThat(offloads.evaluate(f.root(),f.namespace())).isEmpty();assertThat(executions.taskDetail(f.root()).offloads()).isEmpty();
        assertThat(runtimes.result(f.root()).orElseThrow().attemptId()).isEqualTo(f.attempt());
    }

}
