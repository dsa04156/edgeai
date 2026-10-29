package io.edgeai.app.integration;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.node.NodeMetricsSource.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import org.springframework.dao.DataIntegrityViolationException;
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

/** Actual PostgreSQL decisions; node observations and Pod identities are explicit fixtures, not Prometheus or Kubernetes. */
@SpringBootTest
@Import(RetryIntegrationTest.TimeConfiguration.class)
class NodePlacementOffloadIntegrationTest {
    @Autowired RetryIntegrationTest.TestClock clock;
    @Autowired OffloadService offloads;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired ExecutionService executions;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired NodeService nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired OffloadRepository offloadStore;
    private final JsonDocuments json=new JsonDocuments();
    private static final String CORE="{\"cpu\":\"4\",\"memory\":\"4Gi\"}";
    private static final String EDGE="{\"node-role.kubernetes.io/edge\":\"\"}",WORKER="{\"node-role.kubernetes.io/worker\":\"\"}";
    private record Cluster(ExecutionNode edge,ExecutionNode busy,ExecutionNode idle,ExecutionNode quiet,ExecutionNode control,ExecutionNode mixed,ExecutionNode unobserved) {
        List<ExecutionNode> all(){return List.of(edge,busy,idle,quiet,control,mixed,unobserved);}
    }
    /** inventory is re-recorded like the periodic node inventory refresh; tests may replace entries. */
    private record Fixture(WorkflowRun run,UUID root,UUID attempt,RuntimePod pod,String namespace,Cluster cluster,List<ExecutionNode> inventory) {}
    private ExecutionNode node(String prefix,String labels,String allocatable){var id=UUID.randomUUID();return new ExecutionNode(id,prefix+"-"+id,"amd64","linux","READY","4","4Gi",labels,clock.instant(),allocatable);}
    private Cluster cluster(String gpu) {
        String alloc=gpu==null?CORE:"{\"cpu\":\"4\",\"memory\":\"4Gi\",\""+gpu+"\":\"1\"}";
        var c=new Cluster(node("edge",EDGE,alloc),node("busy",WORKER,alloc),node("idle",WORKER,alloc),node("quiet",WORKER,alloc),
            node("control","{\"node-role.kubernetes.io/worker\":\"\",\"node-role.kubernetes.io/control-plane\":\"\"}",alloc),
            node("mixed","{\"node-role.kubernetes.io/worker\":\"\",\"node-role.kubernetes.io/edge\":\"\"}",alloc),node("unobserved",WORKER,alloc));
        nodes.recordSnapshot(c.all(),clock.instant());return c;
    }
    static Map<String,Object> policy() {
        var p=new HashMap<String,Object>(Map.of("consecutiveSamples",3,"maxSampleAgeSeconds",120,"maxGapSeconds",60,"minRunningSeconds",10,"cooldownSeconds",60,"maxTransfers",1,"drainTimeoutSeconds",60,"startTimeoutSeconds",120));
        p.put("cpuPercent",null);p.put("memoryPercent",null);p.put("latencyMicros",null);
        var n=new HashMap<String,Object>(Map.of("strategy","EDGE_TO_WORKER","sourceCpuPercent",85,"targetMaxCpuPercent",60,"targetMaxMemoryPercent",70,"targetMaxGpuPercent",50));
        n.put("sourceMemoryPercent",null);n.put("sourceGpuPercent",80);p.put("nodePlacement",n);return p;
    }
    @SuppressWarnings("unchecked") private UUID version(Map<String,String> extra) throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        spec.put("timeoutSeconds",600);spec.put("recovery",Map.of("mode","RESTART"));
        if(!extra.isEmpty()){var resources=(Map<String,Object>)spec.get("resources");for(String side:List.of("requests","limits")){var m=new TreeMap<String,Object>((Map<String,Object>)resources.get(side));m.putAll(extra);resources.put(side,m);}}
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","node-offload-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","node-offload-"+UUID.randomUUID(),"displayName","Node offload fixture"))).value();
        var definitions=List.of("root","child").stream().map(key->Map.of("key",key,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())).toList();
        return workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",definitions,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value().id();
    }
    private Map<String,Object> request(UUID version,Map<String,Object> execution,Map<String,Object> offload) {
        var r=new HashMap<String,Object>(Map.of("workflowVersionId",version.toString(),"execution",execution,"parameters",Map.of()));if(offload!=null)r.put("offload",offload);return r;
    }
    private static Map<String,Object> at(ExecutionNode n){return Map.of("mode","NODE","nodeId",n.id().toString());}
    private Fixture fixture(String gpu,Map<String,Object> offload) throws Exception {return fixture(gpu,gpu==null?Map.of():Map.of(gpu,"1"),offload);}
    private Fixture fixture(String gpu,Map<String,String> resources,Map<String,Object> offload) throws Exception {
        var c=cluster(gpu);var version=version(resources);
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(request(version,at(c.edge()),offload))).value();
        UUID root=executions.detail(run.id()).tasks().stream().filter(t->t.key().equals("root")).findFirst().orElseThrow().id();
        UUID attempt=executions.taskDetail(root).attempts().getFirst().id();String namespace="node-offload-"+UUID.randomUUID();lifecycle.plan(attempt,namespace);
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),c.edge().id(),c.edge().name());
        lifecycle.submitted(attempt,pod.jobUid());lifecycle.claim(attempt,1,pod);completeCreate(attempt);
        return new Fixture(run,root,attempt,pod,namespace,c,new ArrayList<>(c.all()));
    }
    private void completeCreate(UUID attempt){jdbc.update("UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE kind='CREATE' AND runtime_id=(SELECT id FROM edgeai.runtime_instance WHERE attempt_id=?)",attempt);}
    private static Measurement m(String key,double value,Instant at){return new Measurement(key,key.startsWith("GPU")?"GPU 0":"",value,"PERCENT",at,true);}
    /** Source edge load plus fixed worker loads, all observed at {@code at}. The unobserved worker has no measurements. */
    private Snapshot snapshot(Fixture f,double edgeCpu,double edgeGpu,Instant at) {
        return snapshot(f,List.of(m("CPU_USAGE",edgeCpu,at),m("MEMORY_USAGE",40,at),m("GPU_USAGE",edgeGpu,at)),at);
    }
    /** Explicit source measurements (null: source node absent) plus the same fixed worker loads. */
    private Snapshot snapshot(Fixture f,List<Measurement> edge,Instant at) {
        var c=f.cluster();var items=new ArrayList<NodeMetrics>();
        if(edge!=null)items.add(new NodeMetrics(c.edge().name(),edge));
        items.add(new NodeMetrics(c.busy().name(),List.of(m("CPU_USAGE",75,at),m("MEMORY_USAGE",30,at),m("GPU_USAGE",5,at))));
        items.add(new NodeMetrics(c.idle().name(),List.of(m("CPU_USAGE",10,at),m("MEMORY_USAGE",20,at))));
        items.add(new NodeMetrics(c.quiet().name(),List.of(m("CPU_USAGE",30,at),m("MEMORY_USAGE",25,at),m("GPU_USAGE",10,at))));
        for(var n:List.of(c.control(),c.mixed()))items.add(new NodeMetrics(n.name(),List.of(m("CPU_USAGE",1,at),m("MEMORY_USAGE",1,at),m("GPU_USAGE",1,at))));
        return new Snapshot("AVAILABLE",clock.instant(),90,items,0);
    }
    /** Three distinct node observations 30 s apart after warmup; returns the last snapshot. */
    private Snapshot sustained(Fixture f,double cpu,double gpu) {
        clock.advance(10);Snapshot last=null;
        for(int i=0;i<3;i++){if(i>0)clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());last=snapshot(f,cpu,gpu,clock.instant());if(i<2)assertThat(offloads.evaluate(f.root(),f.namespace(),last)).isEmpty();}
        return last;
    }
    private void unfenced(Fixture f){assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().desiredState()).isEqualTo("RUNNING");assertThat(executions.taskDetail(f.root()).offloads()).isEmpty();}

    @Test void nodePolicyRequiresInitialReadyEdgeNodePlacementBeforeDispatch() throws Exception {
        var c=cluster(null);var version=version(Map.of());
        for(var execution:List.<Map<String,Object>>of(Map.of("mode","AUTO"),at(c.idle()),at(c.control())))
            assertThatThrownBy(()->executions.create(UUID.randomUUID().toString(),json.canonical(request(version,execution,policy()))))
                .isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("OFFLOAD_INITIAL_PLACEMENT_INVALID"));
        var override=request(version,at(c.edge()),policy());override.put("taskExecutions",Map.of("child",Map.of("mode","AUTO")));
        assertThatThrownBy(()->executions.create(UUID.randomUUID().toString(),json.canonical(override))).isInstanceOf(ControlPlaneException.class);
        var remote=request(version,at(c.edge()),policy());remote.put("taskExecutions",Map.of("child",Map.of("mode","REMOTE","providerKey","reference")));
        assertThatThrownBy(()->executions.create(UUID.randomUUID().toString(),json.canonical(remote))).isInstanceOf(ControlPlaneException.class);
        clock.advance(61);
        assertThatThrownBy(()->executions.create(UUID.randomUUID().toString(),json.canonical(request(version,at(c.edge()),policy())))).isInstanceOf(ControlPlaneException.class);
        nodes.recordSnapshot(c.all(),clock.instant());
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(request(version,at(c.edge()),policy()))).value();
        assertThat(json.decode(run.offloadPolicyJson())).isEqualTo(json.decode(json.canonical(policy())));
        assertThat(executions.detail(run.id()).tasks()).allSatisfy(t->assertThat(executions.taskDetail(t.id()).attempts()).allMatch(a->c.edge().id().equals(a.nodeId())));
        // Without nodePlacement the legacy contract still accepts AUTO/worker placement.
        var legacy=new HashMap<>(policy());legacy.remove("nodePlacement");legacy.put("cpuPercent",90);legacy.put("maxSampleAgeSeconds",30);legacy.put("maxGapSeconds",10);
        assertThat(executions.create(UUID.randomUUID().toString(),json.canonical(request(version,Map.of("mode","AUTO"),legacy))).created()).isTrue();
    }
    @Test void sustainedEdgeOverloadCreatesOneOperationOnLeastLoadedCompatibleWorker() throws Exception {
        var f=fixture(null,policy());var hot=sustained(f,95,0);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Optional<OffloadOperation>>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{gate.await();return offloads.evaluate(f.root(),f.namespace(),hot);}));gate.countDown();
            int created=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS).isPresent())created++;assertThat(created).isEqualTo(1);
        }
        var operation=executions.taskDetail(f.root()).offloads().getFirst();
        assertThat(operation.trigger()).isEqualTo("NODE_CPU");assertThat(operation.targetNodeId()).isEqualTo(f.cluster().idle().id());
        assertThat(operation.excludedNodeNames()).isEmpty();
        var decision=(Map<?,?>)json.decode(operation.decisionJson());
        assertThat(((Map<?,?>)decision.get("sourceNode")).get("name")).isEqualTo(f.cluster().edge().name());
        assertThat(((Map<?,?>)decision.get("targetNode")).get("name")).isEqualTo(f.cluster().idle().name());
        assertThat(json.canonical(decision.get("sourceNode"))).contains("CPU_USAGE","95");
        var reasons=new TreeMap<String,Object>();for(var c:(List<?>)decision.get("candidates"))reasons.put((String)((Map<?,?>)c).get("name"),((Map<?,?>)c).get("reason"));
        assertThat(reasons).containsEntry(f.cluster().busy().name(),"NO_HEADROOM").containsEntry(f.cluster().unobserved().name(),"UNOBSERVED")
            .containsEntry(f.cluster().idle().name(),"ELIGIBLE").doesNotContainKeys(f.cluster().control().name(),f.cluster().mixed().name(),f.cluster().edge().name());
        assertThatThrownBy(()->lifecycle.authorize(f.attempt(),1,f.pod().podUid())).isInstanceOf(ControlPlaneException.class);
        lifecycle.confirmStopped(f.attempt());offloads.advance(operation.id());
        var next=executions.taskDetail(f.root()).attempts().getFirst();
        assertThat(next.cause()).isEqualTo("OFFLOAD");assertThat(next.mode()).isEqualTo("NODE");assertThat(next.nodeId()).isEqualTo(f.cluster().idle().id());
        assertThat(lifecycle.dispatch(next.id()).nodeId()).isEqualTo(f.cluster().idle().id());
        var wrong=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),f.cluster().quiet().id(),f.cluster().quiet().name());lifecycle.submitted(next.id(),wrong.jobUid());
        assertThatThrownBy(()->lifecycle.claim(next.id(),2,wrong)).isInstanceOf(ControlPlaneException.class);
        var target=new RuntimePod(wrong.jobUid(),UUID.randomUUID(),f.cluster().idle().id(),f.cluster().idle().name());lifecycle.claim(next.id(),2,target);completeCreate(next.id());
        assertThat(offloads.find(operation.id()).state()).isEqualTo("SUCCEEDED");
        // The worker source is never an automatic node-policy source, even when fully loaded.
        clock.advance(70);for(int i=0;i<4;i++){clock.advance(30);var s=snapshot(f,95,0,clock.instant());
            var load=new ArrayList<>(s.items());load.add(new NodeMetrics(f.cluster().idle().name(),List.of(m("CPU_USAGE",99,clock.instant()),m("MEMORY_USAGE",99,clock.instant()))));
            assertThat(offloads.evaluate(f.root(),f.namespace(),new Snapshot("AVAILABLE",clock.instant(),90,load,0))).isEmpty();}
        assertThat(executions.taskDetail(f.root()).offloads()).hasSize(1);
    }
    @Test void underThresholdRepeatedStaleUnavailableOrNoWorkerNeverFence() throws Exception {
        var under=fixture(null,policy());sustained(under,84,0);unfenced(under);
        var cached=fixture(null,policy());clock.advance(10);var once=snapshot(cached,95,0,clock.instant());
        for(int i=0;i<4;i++){assertThat(offloads.evaluate(cached.root(),cached.namespace(),once)).isEmpty();clock.advance(30);}unfenced(cached);
        var unavailable=fixture(null,policy());clock.advance(10);
        for(int i=0;i<3;i++){var s=snapshot(unavailable,95,0,clock.instant());
            assertThat(offloads.evaluate(unavailable.root(),unavailable.namespace(),i==2?new Snapshot("UNAVAILABLE",clock.instant(),90,List.of(),0):s)).isEmpty();clock.advance(30);}
        unfenced(unavailable);
        var stale=fixture(null,policy());clock.advance(140);
        for(int i=0;i<3;i++){assertThat(offloads.evaluate(stale.root(),stale.namespace(),snapshot(stale,95,0,clock.instant().minusSeconds(130)))).isEmpty();clock.advance(30);}
        unfenced(stale);
        var none=fixture(null,policy());var s=sustained(none,95,0);var c=none.cluster();
        var noRoom=s.items().stream().map(n->n.nodeName().equals(c.idle().name()) || n.nodeName().equals(c.quiet().name())?new NodeMetrics(n.nodeName(),List.of(m("CPU_USAGE",90,clock.instant()),m("MEMORY_USAGE",90,clock.instant()))):n).toList();
        assertThat(offloads.evaluate(none.root(),none.namespace(),new Snapshot("AVAILABLE",clock.instant(),90,noRoom,0))).isEmpty();unfenced(none);
        assertThat(offloads.evaluate(none.root(),none.namespace(),s)).isPresent(); // Same evidence succeeds once a worker has headroom.
    }
    @Test void gpuOverloadRequiresAllocatableAndObservedIdleAcceleratorOnTarget() throws Exception {
        var f=fixture("nvidia.com/gpu",policy());var c=f.cluster();
        // idle has GPU allocatable but no GPU observation; quiet has both; busy lacks CPU headroom.
        f.inventory().replaceAll(n->n.id().equals(c.busy().id())?new ExecutionNode(n.id(),n.name(),"amd64","linux","READY","4","4Gi",WORKER,clock.instant(),"{}"):n);
        var hot=sustained(f,10,95);var operation=offloads.evaluate(f.root(),f.namespace(),hot).orElseThrow();
        assertThat(operation.trigger()).isEqualTo("NODE_GPU");assertThat(operation.targetNodeId()).isEqualTo(c.quiet().id());
        var reasons=new TreeMap<String,Object>();for(var x:(List<?>)((Map<?,?>)json.decode(operation.decisionJson())).get("candidates"))reasons.put((String)((Map<?,?>)x).get("name"),((Map<?,?>)x).get("reason"));
        assertThat(reasons).containsEntry(c.idle().name(),"GPU_UNOBSERVED").containsEntry(c.busy().name(),"RESOURCE_UNAVAILABLE");
    }
    @Test void warmupCooldownAndCancelRaceKeepSingleAttempt() throws Exception {
        var early=fixture(null,policy());
        for(int i=0;i<3;i++){assertThat(offloads.evaluate(early.root(),early.namespace(),snapshot(early,95,0,clock.instant()))).isEmpty();if(i<2)clock.advance(4);}
        unfenced(early); // All three observations precede minRunningSeconds.
        var f=fixture(null,policy());var hot=sustained(f,95,0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();offloads.evaluate(f.root(),f.namespace(),hot);return true;});
            var b=pool.submit(()->{gate.await();executions.cancelTask(f.root(),"{}");return true;});gate.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }
        lifecycle.confirmStopped(f.attempt());for(var o:executions.taskDetail(f.root()).offloads())offloads.advance(o.id());
        assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("CANCELLED");assertThat(executions.taskDetail(f.root()).attempts()).hasSize(1);
        assertThat(executions.taskDetail(f.root()).offloads()).allMatch(o->o.state().equals("CANCELLED"));
    }
    private Snapshot without(Snapshot s,ExecutionNode... nodes){var names=Arrays.stream(nodes).map(ExecutionNode::name).toList();
        return new Snapshot("AVAILABLE",s.fetchedAt(),90,s.items().stream().filter(n->!names.contains(n.nodeName())).toList(),0);}
    @Test void missingOrUnavailableSourceObservationBreaksContinuityInsteadOfReusingOldWindow() throws Exception {
        var f=fixture(null,policy());var c=f.cluster();clock.advance(10);
        for(int i=0;i<3;i++){if(i>0)clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
            assertThat(offloads.evaluate(f.root(),f.namespace(),without(snapshot(f,95,0,clock.instant()),c.idle(),c.quiet()))).isEmpty();} // Sustained, but no headroom.
        clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
        assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,null,clock.instant()))).isEmpty(); // Worker now free, source unobserved.
        clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
        assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,95,0,clock.instant()))).isEmpty(); // Continuity restarted at one sample.
        clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
        assertThat(offloads.evaluate(f.root(),f.namespace(),new Snapshot("UNAVAILABLE",clock.instant(),90,List.of(),0))).isEmpty();
        for(int i=0;i<2;i++){clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
            assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,95,0,clock.instant()))).isEmpty();} // Outage also restarts history.
        clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
        var stale=clock.instant().minusSeconds(121); // CPU series goes stale while memory stays current.
        assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,List.of(m("CPU_USAGE",95,stale),m("MEMORY_USAGE",40,clock.instant())),clock.instant()))).isEmpty();
        unfenced(f);
        clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,95,0,clock.instant()))).isEmpty();
        clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,95,0,clock.instant()))).isEmpty();
        clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,95,0,clock.instant()))).isPresent();
    }
    private static Measurement gpu(String device,double value,Instant at){return new Measurement("GPU_USAGE",device,value,"PERCENT",at,true);}
    @Test void staggeredGpuUpdatesNeverRecountAnUnchangedHotGpuSample() throws Exception {
        var f=fixture("nvidia.com/gpu",policy());clock.advance(10);var hotAt=clock.instant();
        for(int i=0;i<4;i++){if(i>0)clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
            var cool=gpu("GPU 1",10,clock.instant());var hot=gpu("GPU 0",99,hotAt);
            var edge=List.of(m("CPU_USAGE",10,clock.instant()),m("MEMORY_USAGE",10,clock.instant()),i%2==0?hot:cool,i%2==0?cool:hot);
            assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,edge,clock.instant()))).isEmpty();}
        unfenced(f);
        Optional<OffloadOperation> result=Optional.empty();
        for(int i=0;i<3;i++){clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
            var edge=List.of(m("CPU_USAGE",10,clock.instant()),m("MEMORY_USAGE",10,clock.instant()),gpu("GPU 1",10,clock.instant().minusSeconds(7)),gpu("GPU 0",99,clock.instant()));
            result=offloads.evaluate(f.root(),f.namespace(),snapshot(f,edge,clock.instant()));if(i<2)assertThat(result).isEmpty();}
        assertThat(result.orElseThrow().trigger()).isEqualTo("NODE_GPU");
    }
    @Test void databaseRejectsNodeDecisionWithoutMatchingTargetEvidence() throws Exception {
        var f=fixture(null,policy());var target=f.cluster().idle().id();var now=clock.instant();
        var policy=json.decode(json.canonical(policy()));var legacy=new HashMap<>(policy());legacy.remove("nodePlacement");
        for(var decision:List.<Map<String,Object>>of(Map.of("policy",legacy,"targetNode",Map.of("nodeId",target.toString())),Map.of("policy",policy),
                Map.of("policy",policy,"targetNode",Map.of("name","x")),Map.of("policy",policy,"targetNode",Map.of("nodeId",UUID.randomUUID().toString())))) {
            var id=UUID.randomUUID();
            var o=new OffloadOperation(id,f.root(),f.run().id(),f.attempt(),null,target,id,"sha256:"+"a".repeat(64),f.namespace(),"DRAINING",null,now.plusSeconds(60),60,null,now,now,"NODE_CPU",List.of(),json.canonical(decision),null,List.of());
            assertThatThrownBy(()->offloadStore.create(o)).isInstanceOf(DataIntegrityViolationException.class);
        }
        var id=UUID.randomUUID();var valid=Map.of("policy",policy,"targetNode",Map.of("nodeId",target.toString()));
        assertThat(offloadStore.create(new OffloadOperation(id,f.root(),f.run().id(),f.attempt(),null,target,id,"sha256:"+"a".repeat(64),f.namespace(),"DRAINING",null,now.plusSeconds(60),60,null,now,now,"NODE_CPU",List.of(),json.canonical(valid),null,List.of()))).isTrue();
    }
    private static ExecutionNode allocatable(ExecutionNode n,String value){return new ExecutionNode(n.id(),n.name(),n.architecture(),n.operatingSystem(),n.observedStatus(),n.cpu(),n.memory(),n.labelsJson(),n.observedAt(),value);}
    @Test void staticallyUndersizedWorkersAreSkippedAndNoFeasibleTargetKeepsSourceRunning() throws Exception {
        var f=fixture(null,Map.of("memory","2Gi"),policy());var c=f.cluster(); // The quieter idle worker cannot ever fit 2Gi.
        f.inventory().replaceAll(n->n.id().equals(c.idle().id())?allocatable(n,"{\"cpu\":\"4\",\"memory\":\"1Gi\"}"):n);
        var operation=offloads.evaluate(f.root(),f.namespace(),sustained(f,95,0)).orElseThrow();
        assertThat(operation.targetNodeId()).isEqualTo(c.quiet().id());
        var reasons=new TreeMap<String,Object>();for(var x:(List<?>)((Map<?,?>)json.decode(operation.decisionJson())).get("candidates"))reasons.put((String)((Map<?,?>)x).get("name"),((Map<?,?>)x).get("reason"));
        assertThat(reasons).containsEntry(c.idle().name(),"RESOURCE_UNAVAILABLE");
        var none=fixture(null,Map.of("memory","2Gi"),policy());var n=none.cluster();
        none.inventory().replaceAll(x->x.id().equals(n.idle().id()) || x.id().equals(n.quiet().id())?allocatable(x,"{\"cpu\":\"4\",\"memory\":\"1536Mi\"}"):x);
        assertThat(offloads.evaluate(none.root(),none.namespace(),sustained(none,95,0))).isEmpty();unfenced(none);
    }
    @Test void selectedWorkerEvidenceSurvivesTheBoundedCandidateList() throws Exception {
        var f=fixture(null,policy());var c=f.cluster();
        for(int i=0;i<34;i++)f.inventory().add(node("w"+String.format("%02d",i),WORKER,CORE)); // Unobserved, ranked by name before the winner.
        var winner=node("zzz",WORKER,CORE);f.inventory().add(winner);
        var hot=sustained(f,95,0);var items=new ArrayList<>(without(hot,c.idle(),c.quiet()).items());
        items.add(new NodeMetrics(winner.name(),List.of(m("CPU_USAGE",20,clock.instant()),m("MEMORY_USAGE",20,clock.instant()))));
        var operation=offloads.evaluate(f.root(),f.namespace(),new Snapshot("AVAILABLE",clock.instant(),90,items,0)).orElseThrow();
        assertThat(operation.targetNodeId()).isEqualTo(winner.id());
        var candidates=(List<?>)((Map<?,?>)json.decode(operation.decisionJson())).get("candidates");
        assertThat(candidates).hasSize(32).anySatisfy(x->{var e=(Map<?,?>)x;assertThat(e.get("name")).isEqualTo(winner.name());
            assertThat(e.get("reason")).isEqualTo("ELIGIBLE");assertThat(((Number)e.get("score")).doubleValue()).isEqualTo(20.0);assertThat(e.get("cpuPercent")).isNotNull();});
    }

    /** CPU-only source threshold 85 with a lower return threshold 40; two automatic transfers allow out and back. */
    static Map<String,Object> roundtrip() {
        var p=policy();p.put("maxTransfers",2);
        var n=new HashMap<String,Object>(Map.of("strategy","EDGE_TO_WORKER","sourceCpuPercent",85,"targetMaxCpuPercent",60,"targetMaxMemoryPercent",70,"targetMaxGpuPercent",50));
        n.put("sourceMemoryPercent",null);n.put("sourceGpuPercent",null);
        var r=new HashMap<String,Object>();r.put("cpuPercent",40);r.put("memoryPercent",null);r.put("gpuPercent",null);n.put("returnToEdge",r);p.put("nodePlacement",n);return p;
    }
    /** Moves the fixture to the idle worker and claims the new Attempt there; returns that Attempt. */
    private UUID onWorker(Fixture f) {
        var out=offloads.evaluate(f.root(),f.namespace(),sustained(f,95,0)).orElseThrow();
        assertThat(out.trigger()).isEqualTo("NODE_CPU");assertThat(out.targetNodeId()).isEqualTo(f.cluster().idle().id());
        lifecycle.confirmStopped(f.attempt());offloads.advance(out.id());
        var next=executions.taskDetail(f.root()).attempts().getFirst();
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),f.cluster().idle().id(),f.cluster().idle().name());
        lifecycle.submitted(next.id(),pod.jobUid());lifecycle.claim(next.id(),2,pod);completeCreate(next.id());
        assertThat(offloads.find(out.id()).state()).isEqualTo("SUCCEEDED");return next.id();
    }
    /** One distinct observation 30 s later: the edge at edgeCpu and the worker running the task fully loaded. */
    private Optional<OffloadOperation> step(Fixture f,List<Measurement> edge) {
        clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());var s=snapshot(f,edge,clock.instant());
        var items=new ArrayList<>(s.items().stream().filter(n->!n.nodeName().equals(f.cluster().idle().name())).toList());
        items.add(new NodeMetrics(f.cluster().idle().name(),List.of(m("CPU_USAGE",99,clock.instant()),m("MEMORY_USAGE",99,clock.instant()))));
        return offloads.evaluate(f.root(),f.namespace(),new Snapshot("AVAILABLE",clock.instant(),90,items,0));
    }
    private Optional<OffloadOperation> step(Fixture f,double edgeCpu){return step(f,List.of(m("CPU_USAGE",edgeCpu,clock.instant().plusSeconds(30)),m("MEMORY_USAGE",40,clock.instant().plusSeconds(30))));}

    @Test void quietOriginalEdgeReturnsTheTaskOnlyAfterHysteresisCooldownAndConsecutiveLowSamples() throws Exception {
        var f=fixture(null,roundtrip());var workerAttempt=onWorker(f);
        // Cooldown (60 s) after the move: low samples observed inside it do not count.
        assertThat(step(f,10)).isEmpty();
        // Between return (40) and source (85) thresholds: neither direction moves, however long it lasts.
        for(int i=0;i<5;i++)assertThat(step(f,60)).isEmpty();
        assertThat(step(f,10)).isEmpty();assertThat(step(f,10)).isEmpty(); // Two of three consecutive low samples.
        var back=step(f,10).orElseThrow();
        assertThat(back.trigger()).isEqualTo("NODE_RETURN");assertThat(back.targetNodeId()).isEqualTo(f.cluster().edge().id());
        assertThat(back.sourceAttemptId()).isEqualTo(workerAttempt);assertThat(back.excludedNodeNames()).isEmpty();
        var decision=(Map<?,?>)json.decode(back.decisionJson());
        assertThat(decision.get("strategy")).isEqualTo("WORKER_TO_ORIGINAL_EDGE");
        assertThat(((Map<?,?>)decision.get("sourceNode")).get("name")).isEqualTo(f.cluster().idle().name());
        assertThat(json.canonical(decision.get("targetNode"))).contains(f.cluster().edge().name(),"CPU_USAGE");
        // Physical termination barrier: no edge Attempt until the worker Pod is confirmed stopped.
        offloads.advance(back.id());assertThat(executions.taskDetail(f.root()).attempts()).hasSize(2);
        lifecycle.confirmStopped(workerAttempt);offloads.advance(back.id());
        var home=executions.taskDetail(f.root()).attempts().getFirst();
        assertThat(home.nodeId()).isEqualTo(f.cluster().edge().id());assertThat(home.cause()).isEqualTo("OFFLOAD");
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),f.cluster().edge().id(),f.cluster().edge().name());
        lifecycle.submitted(home.id(),pod.jobUid());lifecycle.claim(home.id(),3,pod);completeCreate(home.id());
        assertThat(offloads.find(back.id()).state()).isEqualTo("SUCCEEDED");
        // maxTransfers=2 is spent: a new overload keeps the task on its edge (no flapping loop within one Run).
        clock.advance(70);for(int i=0;i<4;i++){clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
            assertThat(offloads.evaluate(f.root(),f.namespace(),snapshot(f,95,0,clock.instant()))).isEmpty();}
        assertThat(executions.taskDetail(f.root()).offloads()).hasSize(2);
    }
    @Test void staleMissingOrUnavailableEdgeObservationsNeverReturn() throws Exception {
        var f=fixture(null,roundtrip());onWorker(f);clock.advance(60);
        for(int i=0;i<4;i++)assertThat(step(f,List.of(m("CPU_USAGE",5,clock.instant().minusSeconds(100)),m("MEMORY_USAGE",5,clock.instant())))).isEmpty(); // Stale CPU.
        for(int i=0;i<4;i++)assertThat(step(f,List.of(m("MEMORY_USAGE",5,clock.instant().plusSeconds(30))))).isEmpty(); // CPU series missing.
        for(int i=0;i<4;i++){clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());
            assertThat(offloads.evaluate(f.root(),f.namespace(),new Snapshot("UNAVAILABLE",clock.instant(),90,List.of(),0))).isEmpty();}
        assertThat(step(f,10)).isEmpty();assertThat(step(f,10)).isEmpty();assertThat(step(f,10)).isPresent(); // Continuity restarts with fresh observations only.
    }
    @Test void incompatibleOrNotReadyOriginalEdgeKeepsTheTaskOnTheWorker() throws Exception {
        var f=fixture(null,roundtrip());var c=f.cluster();onWorker(f);clock.advance(60);
        f.inventory().replaceAll(n->n.id().equals(c.edge().id())?allocatable(n,"{}"):n); // Cannot fit the SERVICE requests any more.
        for(int i=0;i<4;i++)assertThat(step(f,10)).isEmpty();
        f.inventory().replaceAll(n->n.id().equals(c.edge().id())?new ExecutionNode(n.id(),n.name(),n.architecture(),"linux","NOT_READY","4","4Gi",EDGE,clock.instant(),CORE):n);
        for(int i=0;i<4;i++)assertThat(step(f,10)).isEmpty();
        assertThat(executions.taskDetail(f.root()).offloads()).hasSize(1);
        assertThat(runtimes.byAttempt(executions.taskDetail(f.root()).attempts().getFirst().id()).orElseThrow().desiredState()).isEqualTo("RUNNING");
        f.inventory().replaceAll(n->n.id().equals(c.edge().id())?c.edge():n);
        assertThat(step(f,10)).isPresent(); // Window kept: the samples were fresh, only the target was infeasible.
    }
    @Test void userStopDuringReturnCancelsTheTransitionWithoutStartingTheEdgeAttempt() throws Exception {
        var f=fixture(null,roundtrip());var workerAttempt=onWorker(f);clock.advance(60);
        step(f,10);step(f,10);var back=step(f,10).orElseThrow();
        executions.cancelRun(f.run().id(),"{}");lifecycle.confirmStopped(workerAttempt);offloads.advance(back.id());
        assertThat(offloads.find(back.id()).state()).isEqualTo("CANCELLED");
        assertThat(executions.taskDetail(f.root()).attempts()).hasSize(2);assertThat(executions.taskDetail(f.root()).task().state()).isEqualTo("CANCELLED");
    }
    @Test void databaseRequiresTheReturnPolicyForNodeReturnDecisions() throws Exception {
        var f=fixture(null,roundtrip());var target=f.cluster().edge().id();var now=clock.instant();
        var oneWay=json.decode(json.canonical(policy()));var withReturn=json.decode(json.canonical(roundtrip()));
        var id=UUID.randomUUID();
        var invalid=new OffloadOperation(id,f.root(),f.run().id(),f.attempt(),null,target,id,"sha256:"+"a".repeat(64),f.namespace(),"DRAINING",null,now.plusSeconds(60),60,null,now,now,
            "NODE_RETURN",List.of(),json.canonical(Map.of("policy",oneWay,"targetNode",Map.of("nodeId",target.toString()))),null,List.of());
        assertThatThrownBy(()->offloadStore.create(invalid)).isInstanceOf(DataIntegrityViolationException.class);
        var ok=UUID.randomUUID();
        assertThat(offloadStore.create(new OffloadOperation(ok,f.root(),f.run().id(),f.attempt(),null,target,ok,"sha256:"+"a".repeat(64),f.namespace(),"DRAINING",null,now.plusSeconds(60),60,null,now,now,
            "NODE_RETURN",List.of(),json.canonical(Map.of("policy",withReturn,"targetNode",Map.of("nodeId",target.toString()))),null,List.of()))).isTrue();
    }

    @Test void returnWaitsForEveryGpuEvenWhenOneVanishesOrGoesStaleWhileAnotherStaysCool() throws Exception {
        var p=roundtrip();
        var n=new HashMap<String,Object>(Map.of("strategy","EDGE_TO_WORKER","sourceGpuPercent",80,"targetMaxCpuPercent",60,"targetMaxMemoryPercent",70,"targetMaxGpuPercent",50));
        n.put("sourceCpuPercent",null);n.put("sourceMemoryPercent",null);
        var r=new HashMap<String,Object>();r.put("cpuPercent",null);r.put("memoryPercent",null);r.put("gpuPercent",30);n.put("returnToEdge",r);p.put("nodePlacement",n);
        var f=fixture("nvidia.com/gpu",p);var c=f.cluster();clock.advance(10);Optional<OffloadOperation> out=Optional.empty();
        for(int i=0;i<3;i++){if(i>0)clock.advance(30);nodes.recordSnapshot(f.inventory(),clock.instant());var at=clock.instant();
            out=offloads.evaluate(f.root(),f.namespace(),snapshot(f,List.of(m("CPU_USAGE",10,at),m("MEMORY_USAGE",10,at),gpu("GPU 0",95,at),gpu("GPU 1",95,at)),at));}
        var move=out.orElseThrow();assertThat(move.trigger()).isEqualTo("NODE_GPU");assertThat(move.targetNodeId()).isEqualTo(c.quiet().id());
        lifecycle.confirmStopped(f.attempt());offloads.advance(move.id());
        var next=executions.taskDetail(f.root()).attempts().getFirst();var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),c.quiet().id(),c.quiet().name());
        lifecycle.submitted(next.id(),pod.jobUid());lifecycle.claim(next.id(),2,pod);completeCreate(next.id());clock.advance(60);
        java.util.function.Function<List<Measurement>,List<Measurement>> base=g->{var l=new ArrayList<Measurement>(List.of(m("CPU_USAGE",10,clock.instant().plusSeconds(30)),m("MEMORY_USAGE",10,clock.instant().plusSeconds(30))));l.addAll(g);return l;};
        // GPU 1 vanishes from the edge's report while GPU 0 stays cool: never a return.
        for(int i=0;i<4;i++)assertThat(step(f,base.apply(List.of(gpu("GPU 0",5,clock.instant().plusSeconds(30)))))).isEmpty();
        // GPU 1 reported again but stale.
        for(int i=0;i<4;i++)assertThat(step(f,base.apply(List.of(gpu("GPU 0",5,clock.instant().plusSeconds(30)),gpu("GPU 1",5,clock.instant().minusSeconds(200)))))).isEmpty();
        assertThat(executions.taskDetail(f.root()).offloads()).hasSize(1);
        for(int i=0;i<2;i++)assertThat(step(f,base.apply(List.of(gpu("GPU 0",5,clock.instant().plusSeconds(30)),gpu("GPU 1",5,clock.instant().plusSeconds(30)))))).isEmpty();
        var back=step(f,base.apply(List.of(gpu("GPU 0",5,clock.instant().plusSeconds(30)),gpu("GPU 1",5,clock.instant().plusSeconds(30))))).orElseThrow();
        assertThat(back.trigger()).isEqualTo("NODE_RETURN");assertThat(back.targetNodeId()).isEqualTo(c.edge().id());
    }
}
