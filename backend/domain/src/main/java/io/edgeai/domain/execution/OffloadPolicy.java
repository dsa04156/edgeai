package io.edgeai.domain.execution;

import io.edgeai.domain.runtime.RuntimeTelemetry;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** Configurable threshold experiment, not a node scorer or an optimality claim. */
public record OffloadPolicy(Integer cpuPercent,Integer memoryPercent,Long latencyMicros,int consecutiveSamples,
        int maxSampleAgeSeconds,int maxGapSeconds,int minRunningSeconds,int cooldownSeconds,int maxTransfers,
        int drainTimeoutSeconds,int startTimeoutSeconds,NodePlacementPolicy nodePlacement) {
    public OffloadPolicy {
        boolean task=cpuPercent!=null || memoryPercent!=null || latencyMicros!=null;
        if(nodePlacement==null && !task)throw new IllegalArgumentException("At least one offload threshold required");
        if(nodePlacement!=null && task)throw new IllegalArgumentException("Node placement replaces task telemetry thresholds");
        if(cpuPercent!=null)range(cpuPercent,1,100);if(memoryPercent!=null)range(memoryPercent,1,100);
        if(latencyMicros!=null && (latencyMicros<1 || latencyMicros>600000000L))throw new IllegalArgumentException("Invalid latency threshold");
        // Node exporters scrape slower (~30s) than Runner telemetry, so the node policy allows wider sample windows.
        range(consecutiveSamples,2,6);range(maxSampleAgeSeconds,10,nodePlacement==null?60:180);range(maxGapSeconds,5,nodePlacement==null?30:120);
        range(minRunningSeconds,10,3600);range(cooldownSeconds,10,3600);range(maxTransfers,1,8);
        range(drainTimeoutSeconds,1,600);range(startTimeoutSeconds,1,600);
    }
    public OffloadPolicy(Integer cpuPercent,Integer memoryPercent,Long latencyMicros,int consecutiveSamples,int maxSampleAgeSeconds,int maxGapSeconds,
            int minRunningSeconds,int cooldownSeconds,int maxTransfers,int drainTimeoutSeconds,int startTimeoutSeconds) {
        this(cpuPercent,memoryPercent,latencyMicros,consecutiveSamples,maxSampleAgeSeconds,maxGapSeconds,minRunningSeconds,cooldownSeconds,maxTransfers,
            drainTimeoutSeconds,startTimeoutSeconds,null);
    }
    private static void range(int n,int min,int max){if(n<min || n>max)throw new IllegalArgumentException("Offload policy value out of range");}
    /** Samples newest first. Every sample must breach the SAME metric, after warmup/cooldown. */
    public Optional<String> trigger(List<RuntimeTelemetry> samples,Instant now,Instant eligibleSince) {
        if(now.isBefore(eligibleSince) || samples.size()!=consecutiveSamples)return Optional.empty();
        for(int i=0;i<samples.size();i++) {
            var sample=samples.get(i);
            if(sample.observedAt().isBefore(eligibleSince) || sample.observedAt().isBefore(now.minusSeconds(maxSampleAgeSeconds)) ||
                sample.observedAt().isAfter(now.plusSeconds(5)) || sample.receivedAt().isBefore(now.minusSeconds(maxSampleAgeSeconds)) || sample.receivedAt().isAfter(now))return Optional.empty();
            if(i>0) {
                var newer=samples.get(i-1);
                if(!sample.attemptId().equals(newer.attemptId()) || newer.sequence()!=sample.sequence()+1 || !newer.observedAt().isAfter(sample.observedAt()) ||
                    newer.observedAt().isAfter(sample.observedAt().plusSeconds(maxGapSeconds)))return Optional.empty();
            }
        }
        if(latencyMicros!=null && samples.stream().allMatch(s->s.latencyMicros()!=null && s.latencyMicros()>=latencyMicros &&
                !s.latencyObservedAt().isBefore(eligibleSince) && !s.latencyObservedAt().isBefore(s.observedAt().minusSeconds(maxGapSeconds)) &&
                !s.latencyObservedAt().isBefore(now.minusSeconds(maxSampleAgeSeconds)) && !s.latencyObservedAt().isAfter(now.plusSeconds(5)))) {
            boolean increasing=true;
            for(int i=1;i<samples.size();i++)if(!samples.get(i-1).latencyObservedAt().isAfter(samples.get(i).latencyObservedAt()))increasing=false;
            if(increasing)return Optional.of("LATENCY");
        }
        if(memoryPercent!=null && samples.stream().allMatch(s->ratio(s.memoryBytes(),s.memoryLimitBytes(),memoryPercent)))return Optional.of("MEMORY");
        if(cpuPercent!=null && samples.stream().allMatch(s->s.cpuLimitMillicores()!=null && ratio(s.cpuUsageMicros(),(long)s.intervalMillis()*s.cpuLimitMillicores(),cpuPercent)))return Optional.of("CPU");
        return Optional.empty();
    }
    /**
     * Node samples newest first per series (CPU_USAGE, MEMORY_USAGE, GPU_USAGE/{device}); the SAME series must breach in every
     * consecutive distinct observation. Any GPU, then memory, then CPU.
     */
    public Optional<String> nodeTrigger(Map<String,List<NodeSampleWindow.Sample>> samples,Instant now,Instant eligibleSince) {
        if(nodePlacement==null || now.isBefore(eligibleSince))return Optional.empty();
        var codes=List.of("NODE_GPU","NODE_MEM","NODE_CPU");
        var thresholds=Arrays.asList(nodePlacement.sourceGpuPercent(),nodePlacement.sourceMemoryPercent(),nodePlacement.sourceCpuPercent());
        for(int rank=0;rank<codes.size();rank++) {
            Integer threshold=thresholds.get(rank);if(threshold==null)continue;
            for(var entry:new TreeMap<>(samples).entrySet())
                if(kind(entry.getKey())==rank && series(entry.getValue(),v->v>=threshold,now,eligibleSince))return Optional.of(codes.get(rank));
        }
        return Optional.empty();
    }
    /**
     * Return to the original edge: EVERY configured metric of that edge stays strictly below its return threshold for consecutive
     * distinct fresh observations. With a GPU threshold every series in requiredGpu (each installed/ever-observed GPU, at least
     * one) must be among them; a missing, stale or vanished GPU series is unknown, never idle. null requiredGpu: incomplete.
     */
    public Optional<String> returnTrigger(Map<String,List<NodeSampleWindow.Sample>> samples,Instant now,Instant eligibleSince,Set<String> requiredGpu) {
        if(nodePlacement==null || nodePlacement.returnToEdge()==null || now.isBefore(eligibleSince))return Optional.empty();
        var r=nodePlacement.returnToEdge();var thresholds=Arrays.asList(r.gpuPercent(),r.memoryPercent(),r.cpuPercent());
        if(r.gpuPercent()!=null && (requiredGpu==null || requiredGpu.isEmpty() || !samples.keySet().containsAll(requiredGpu)))return Optional.empty();
        for(int rank=0;rank<3;rank++) {
            Integer threshold=thresholds.get(rank);if(threshold==null)continue;int k=rank;
            var matching=samples.entrySet().stream().filter(e->kind(e.getKey())==k).toList();
            if(matching.isEmpty() || !matching.stream().allMatch(e->series(e.getValue(),v->v<threshold,now,eligibleSince)))return Optional.empty();
        }
        return Optional.of("NODE_RETURN");
    }
    private static int kind(String key){return key.equals("GPU_USAGE") || key.startsWith("GPU_USAGE/")?0:key.equals("MEMORY_USAGE")?1:key.equals("CPU_USAGE")?2:-1;}
    /** consecutiveSamples distinct observations, newest first, all after eligibility, fresh and within maxGap of each other. */
    private boolean series(List<NodeSampleWindow.Sample> recent,java.util.function.DoublePredicate condition,Instant now,Instant eligibleSince) {
        if(recent.size()!=consecutiveSamples)return false;
        for(int i=0;i<recent.size();i++) {
            var sample=recent.get(i);
            if(!condition.test(sample.value()) || sample.observedAt().isBefore(eligibleSince) || sample.observedAt().isBefore(now.minusSeconds(maxSampleAgeSeconds)) ||
                sample.observedAt().isAfter(now.plusSeconds(5)) || (i>0 && (!recent.get(i-1).observedAt().isAfter(sample.observedAt()) ||
                recent.get(i-1).observedAt().isAfter(sample.observedAt().plusSeconds(maxGapSeconds)))))return false;
        }
        return true;
    }
    private static boolean ratio(Long used,Long limit,int percent) {
        return used!=null && limit!=null && limit>0 && BigInteger.valueOf(used).multiply(BigInteger.valueOf(100)).compareTo(BigInteger.valueOf(limit).multiply(BigInteger.valueOf(percent)))>=0;
    }
}
