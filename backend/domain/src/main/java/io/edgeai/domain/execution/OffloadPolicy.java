package io.edgeai.domain.execution;

import io.edgeai.domain.runtime.RuntimeTelemetry;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** Configurable threshold experiment, not a node scorer or an optimality claim. */
public record OffloadPolicy(Integer cpuPercent,Integer memoryPercent,Long latencyMicros,int consecutiveSamples,
        int maxSampleAgeSeconds,int maxGapSeconds,int minRunningSeconds,int cooldownSeconds,int maxTransfers,
        int drainTimeoutSeconds,int startTimeoutSeconds) {
    public OffloadPolicy {
        if(cpuPercent==null && memoryPercent==null && latencyMicros==null)throw new IllegalArgumentException("At least one offload threshold required");
        if(cpuPercent!=null)range(cpuPercent,1,100);if(memoryPercent!=null)range(memoryPercent,1,100);
        if(latencyMicros!=null && (latencyMicros<1 || latencyMicros>600000000L))throw new IllegalArgumentException("Invalid latency threshold");
        range(consecutiveSamples,2,6);range(maxSampleAgeSeconds,10,60);range(maxGapSeconds,5,30);
        range(minRunningSeconds,10,3600);range(cooldownSeconds,10,3600);range(maxTransfers,1,8);
        range(drainTimeoutSeconds,1,600);range(startTimeoutSeconds,1,600);
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
    private static boolean ratio(Long used,Long limit,int percent) {
        return used!=null && limit!=null && limit>0 && BigInteger.valueOf(used).multiply(BigInteger.valueOf(100)).compareTo(BigInteger.valueOf(limit).multiply(BigInteger.valueOf(percent)))>=0;
    }
}
