package io.edgeai.app.support;
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
}
