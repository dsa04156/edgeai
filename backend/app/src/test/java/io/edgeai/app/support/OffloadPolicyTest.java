package io.edgeai.app.support;
import io.edgeai.domain.execution.NodeSampleWindow;
import io.edgeai.domain.execution.OffloadPolicy;
import io.edgeai.domain.runtime.RuntimeTelemetry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OffloadPolicyTest {
    private final UUID attempt=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-02T10:00:00Z");
    private OffloadPolicy policy(){return new OffloadPolicy(80,90,10000L,3,30,10,10,60,2,60,120);}
    private RuntimeTelemetry sample(long sequence,int age,Long cpu,Long memory,Long latency) {
        var at=now.minusSeconds(age);
        return new RuntimeTelemetry(attempt,sequence,at,at,5000,cpu,cpu==null?null:500L,memory,memory==null?null:1000L,latency,latency==null?null:at);
    }
    private List<RuntimeTelemetry> samples(Long cpu,Long memory,Long latency){return List.of(sample(3,0,cpu,memory,latency),sample(2,5,cpu,memory,latency),sample(1,10,cpu,memory,latency));}
    @Test void requiresSameMetricAndUsesExactUnitsWithDeterministicReason() {
        assertThat(policy().trigger(samples(2000000L,1L,null),now,now.minusSeconds(20))).contains("CPU");
        assertThat(policy().trigger(samples(1999999L,900L,null),now,now.minusSeconds(20))).contains("MEMORY");
        assertThat(policy().trigger(samples(2000000L,900L,10000L),now,now.minusSeconds(20))).contains("LATENCY");
        assertThat(policy().trigger(samples(1999999L,899L,9999L),now,now.minusSeconds(20))).isEmpty();
        assertThat(policy().trigger(List.of(sample(3,0,2000000L,1L,null),sample(2,5,1L,900L,null),sample(1,10,2000000L,1L,null)),now,now.minusSeconds(20))).isEmpty();
    }
    @Test void refusesMissingSequenceGapsStaleFutureWarmupAndDifferentProducer() {
        var good=samples(null,900L,null);
        for(var bad:List.of(good.subList(0,2),List.of(sample(4,0,null,900L,null),good.get(1),good.get(2)),
                List.of(good.get(0),sample(2,11,null,900L,null),sample(1,16,null,900L,null)),
                List.of(sample(3,-6,null,900L,null),good.get(1),good.get(2))))
            assertThat(policy().trigger(bad,now,now.minusSeconds(60))).isEmpty();
        assertThat(policy().trigger(good,now.plusSeconds(31),now.minusSeconds(60))).isEmpty();
        assertThat(policy().trigger(good,now,now.minusSeconds(9))).isEmpty();
        var skewed=List.of(3,2,1).stream().map(i->new RuntimeTelemetry(attempt,i,now.plusSeconds(i),now,1000,null,null,900L,1000L,null,null)).toList();
        assertThat(policy().trigger(skewed,now,now.plusSeconds(1))).isEmpty(); // Client clock skew cannot skip server warmup.
        var s=good.getFirst();var other=new RuntimeTelemetry(UUID.randomUUID(),s.sequence(),s.observedAt(),s.receivedAt(),5000,null,null,900L,1000L,null,null);
        assertThat(policy().trigger(List.of(other,good.get(1),good.get(2)),now,now.minusSeconds(20))).isEmpty();
    }
    @Test void missingLimitsAndReusedLatencyAreNotEvidenceAndArithmeticCannotOverflow() {
        var noLimits=samples(9007199254740991L,9007199254740991L,null).stream().map(s->new RuntimeTelemetry(s.attemptId(),s.sequence(),s.observedAt(),s.receivedAt(),5000,s.cpuUsageMicros(),null,s.memoryBytes(),null,null,null)).toList();
        assertThat(policy().trigger(noLimits,now,now.minusSeconds(20))).isEmpty();
        assertThat(policy().trigger(samples(9007199254740991L,1L,null),now,now.minusSeconds(20))).contains("CPU");
        var reused=samples(null,1L,20000L).stream().map(s->new RuntimeTelemetry(s.attemptId(),s.sequence(),s.observedAt(),s.receivedAt(),5000,null,null,1L,1000L,20000L,now.minusSeconds(10))).toList();
        assertThat(policy().trigger(reused,now,now.minusSeconds(20))).isEmpty();
    }
    @Test void rejectsUnsafeBoundsAndUnknownFields() {
        var valid=new HashMap<String,Object>(Map.of("cpuPercent",90,"memoryPercent",90,"consecutiveSamples",3,"maxSampleAgeSeconds",30,"maxGapSeconds",10,"minRunningSeconds",10,"cooldownSeconds",60,"maxTransfers",1,"drainTimeoutSeconds",60,"startTimeoutSeconds",120));valid.put("latencyMicros",null);
        for(var change:List.of(Map.of("cpuPercent",101),Map.of("cpuPercent",1.5),Map.of("consecutiveSamples",1),Map.of("maxTransfers",9),Map.of("maxGapSeconds",0),Map.of("minRunningSeconds",0),Map.of("cooldownSeconds",0),Map.of("latencyMicros",600000001L),Map.of("unknown",1))) {
            var input=new HashMap<>(valid);input.putAll(change);assertThatThrownBy(()->WorkflowInput.offloadPolicy(input)).isInstanceOf(IllegalArgumentException.class);
        }
        valid.put("cpuPercent",null);valid.put("memoryPercent",null);assertThatThrownBy(()->WorkflowInput.offloadPolicy(valid)).isInstanceOf(IllegalArgumentException.class);
    }
    static Map<String,Object> nodeOffload() {
        var p=new HashMap<String,Object>(Map.of("consecutiveSamples",3,"maxSampleAgeSeconds",120,"maxGapSeconds",60,"minRunningSeconds",10,"cooldownSeconds",60,"maxTransfers",1,"drainTimeoutSeconds",60,"startTimeoutSeconds",120));
        p.put("cpuPercent",null);p.put("memoryPercent",null);p.put("latencyMicros",null);
        var n=new HashMap<String,Object>(Map.of("strategy","EDGE_TO_WORKER","sourceCpuPercent",85,"targetMaxCpuPercent",60,"targetMaxMemoryPercent",70,"targetMaxGpuPercent",50));
        n.put("sourceMemoryPercent",null);n.put("sourceGpuPercent",80);p.put("nodePlacement",n);return p;
    }
    @SuppressWarnings("unchecked") @Test void nodePlacementIsOptInValidatedAndCannotMixTaskThresholds() {
        assertThat(policy().nodePlacement()).isNull();
        var parsed=WorkflowInput.offloadPolicy(nodeOffload());
        assertThat(parsed.nodePlacement().sourceCpuPercent()).isEqualTo(85);assertThat(parsed.maxSampleAgeSeconds()).isEqualTo(120);
        var legacy=new HashMap<>(nodeOffload());legacy.remove("nodePlacement");legacy.put("cpuPercent",90);
        assertThatThrownBy(()->WorkflowInput.offloadPolicy(legacy)).isInstanceOf(IllegalArgumentException.class); // Legacy sample ranges stay 10..60s.
        var mixed=new HashMap<>(nodeOffload());mixed.put("cpuPercent",90);
        assertThatThrownBy(()->WorkflowInput.offloadPolicy(mixed)).isInstanceOf(IllegalArgumentException.class);
        for(var change:List.<Map<String,Object>>of(Map.of("strategy","WORKER_TO_EDGE"),Map.of("targetMaxCpuPercent",0),Map.of("targetMaxGpuPercent",101),Map.of("unknown",1))) {
            var input=nodeOffload();var n=new HashMap<String,Object>((Map<String,Object>)input.get("nodePlacement"));n.putAll(change);input.put("nodePlacement",n);
            assertThatThrownBy(()->WorkflowInput.offloadPolicy(input)).isInstanceOf(IllegalArgumentException.class);
        }
        var none=nodeOffload();var n=new HashMap<String,Object>((Map<String,Object>)none.get("nodePlacement"));n.put("sourceCpuPercent",null);n.put("sourceGpuPercent",null);none.put("nodePlacement",n);
        assertThatThrownBy(()->WorkflowInput.offloadPolicy(none)).isInstanceOf(IllegalArgumentException.class);
        var slow=nodeOffload();slow.put("maxSampleAgeSeconds",181);assertThatThrownBy(()->WorkflowInput.offloadPolicy(slow)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nodeTriggerCountsOnlyDistinctFreshConsecutiveObservationsAfterEligibility() {
        var policy=WorkflowInput.offloadPolicy(nodeOffload());var eligible=now.minusSeconds(200);
        var window=new NodeSampleWindow(attempt,"edge-a",eligible);
        for(int i=0;i<3;i++)window.observe("CPU_USAGE",95,now.minusSeconds(60),true,now,policy); // One cached Prometheus sample.
        assertThat(policy.nodeTrigger(window.recent(),now,eligible)).isEmpty();
        window.observe("CPU_USAGE",95,now.minusSeconds(30),true,now,policy);
        assertThat(policy.nodeTrigger(window.recent(),now,eligible)).isEmpty();
        window.observe("CPU_USAGE",85,now,true,now,policy);assertThat(policy.nodeTrigger(window.recent(),now,eligible)).contains("NODE_CPU");
        assertThat(policy.nodeTrigger(window.recent(),now,now.minusSeconds(45))).isEmpty(); // Samples before warmup/cooldown end.
        assertThat(policy.nodeTrigger(window.recent(),now.plusSeconds(61),eligible)).isEmpty(); // Oldest sample stale.
        window.observe("GPU_USAGE",80,now.minusSeconds(40),true,now,policy);window.observe("GPU_USAGE",90,now.minusSeconds(20),true,now,policy);window.observe("GPU_USAGE",99,now,true,now,policy);
        assertThat(policy.nodeTrigger(window.recent(),now,eligible)).contains("NODE_GPU");
        var cool=new NodeSampleWindow(attempt,"edge-a",eligible);
        for(int age:List.of(60,30,0))cool.observe("CPU_USAGE",age==30?84.9:99,now.minusSeconds(age),true,now,policy);
        assertThat(policy.nodeTrigger(cool.recent(),now,eligible)).isEmpty();
        var gap=new NodeSampleWindow(attempt,"edge-a",eligible);
        for(int age:List.of(110,45,0))gap.observe("CPU_USAGE",99,now.minusSeconds(age),true,now,policy);
        assertThat(policy.nodeTrigger(gap.recent(),now,eligible)).isEmpty();
        var unfresh=new NodeSampleWindow(attempt,"edge-a",eligible);
        for(int age:List.of(60,30,0))unfresh.observe("CPU_USAGE",99,now.minusSeconds(age),age!=30,now,policy);
        assertThat(policy.nodeTrigger(unfresh.recent(),now,eligible)).isEmpty();
        var future=new NodeSampleWindow(attempt,"edge-a",eligible);
        for(int age:List.of(60,30,0))future.observe("CPU_USAGE",99,now.minusSeconds(age),true,now,policy);
        future.observe("CPU_USAGE",99,now.plusSeconds(6),true,now,policy); // Invalid current observation breaks the series.
        assertThat(policy.nodeTrigger(future.recent(),now,eligible)).isEmpty();
        assertThat(window.matches(attempt,"edge-a",eligible)).isTrue();assertThat(window.matches(UUID.randomUUID(),"edge-a",eligible)).isFalse();
        assertThat(window.matches(attempt,"edge-a",now)).isFalse();
    }
    @SuppressWarnings("unchecked") static Map<String,Object> returning(Integer cpu,Integer memory,Integer gpu) {
        var p=nodeOffload();var n=new HashMap<String,Object>((Map<String,Object>)p.get("nodePlacement"));
        var r=new HashMap<String,Object>();r.put("cpuPercent",cpu);r.put("memoryPercent",memory);r.put("gpuPercent",gpu);n.put("returnToEdge",r);p.put("nodePlacement",n);return p;
    }
    @SuppressWarnings("unchecked") @Test void returnToEdgeIsOptionalAndMustSitStrictlyBelowEverySourceThreshold() {
        assertThat(WorkflowInput.offloadPolicy(nodeOffload()).nodePlacement().returnToEdge()).isNull(); // Omitted: one-way policy.
        var parsed=WorkflowInput.offloadPolicy(returning(40,null,50)).nodePlacement().returnToEdge();
        assertThat(parsed.cpuPercent()).isEqualTo(40);assertThat(parsed.gpuPercent()).isEqualTo(50);
        for(var invalid:List.of(returning(85,null,50),returning(90,null,50),returning(40,null,null),returning(null,null,null),returning(0,null,50),returning(40,101,50)))
            assertThatThrownBy(()->WorkflowInput.offloadPolicy(invalid)).isInstanceOf(IllegalArgumentException.class);
        var extra=returning(40,null,50);var n=(Map<String,Object>)extra.get("nodePlacement");((Map<String,Object>)n.get("returnToEdge")).put("unknown",1);
        assertThatThrownBy(()->WorkflowInput.offloadPolicy(extra)).isInstanceOf(IllegalArgumentException.class);
        var nul=returning(40,null,50);((Map<String,Object>)nul.get("nodePlacement")).put("returnToEdge",null);
        assertThatThrownBy(()->WorkflowInput.offloadPolicy(nul)).isInstanceOf(IllegalArgumentException.class);
    }
    private static final Set<String> G0=Set.of("GPU_USAGE/GPU 0");
    @Test void returnRequiresEveryConfiguredSeriesFreshAndBelowForConsecutiveObservations() {
        var policy=WorkflowInput.offloadPolicy(returning(40,30,50));var eligible=now.minusSeconds(200);
        java.util.function.Function<double[],NodeSampleWindow> window=values->{var w=new NodeSampleWindow(attempt,"edge-a",eligible);
            for(int i=0;i<3;i++){var at=now.minusSeconds(60-30L*i);w.observe("CPU_USAGE",values[0],at,true,now,policy);w.observe("MEMORY_USAGE",values[1],at,true,now,policy);
                w.observe("GPU_USAGE/GPU 0",values[2],at,true,now,policy);}return w;};
        assertThat(policy.returnTrigger(window.apply(new double[]{10,10,10}).recent(),now,eligible,G0)).contains("NODE_RETURN");
        assertThat(policy.returnTrigger(window.apply(new double[]{40,10,10}).recent(),now,eligible,G0)).isEmpty(); // Strictly below.
        assertThat(policy.returnTrigger(window.apply(new double[]{10,35,10}).recent(),now,eligible,G0)).isEmpty(); // Every metric, not any.
        assertThat(policy.returnTrigger(window.apply(new double[]{10,10,10}).recent(),now,now.minusSeconds(45),G0)).isEmpty(); // Before cooldown end.
        assertThat(policy.returnTrigger(window.apply(new double[]{10,10,10}).recent(),now.plusSeconds(61),eligible,G0)).isEmpty(); // Stale.
        var missingGpu=new NodeSampleWindow(attempt,"edge-a",eligible);
        for(int i=0;i<3;i++){var at=now.minusSeconds(60-30L*i);missingGpu.observe("CPU_USAGE",1,at,true,now,policy);missingGpu.observe("MEMORY_USAGE",1,at,true,now,policy);}
        assertThat(policy.returnTrigger(missingGpu.recent(),now,eligible,G0)).isEmpty(); // An unobserved GPU is unknown, not idle.
        var cool=window.apply(new double[]{10,10,10});
        assertThat(policy.returnTrigger(cool.recent(),now,eligible,Set.of("GPU_USAGE/GPU 0","GPU_USAGE/GPU 1"))).isEmpty(); // Second GPU vanished.
        assertThat(policy.returnTrigger(cool.recent(),now,eligible,null)).isEmpty(); // Fewer GPUs observed than installed.
        var oneWay=WorkflowInput.offloadPolicy(nodeOffload());
        assertThat(oneWay.returnTrigger(window.apply(new double[]{1,1,1}).recent(),now,eligible,G0)).isEmpty();
    }
}
