package io.edgeai.domain.execution;

import java.time.Instant;
import java.util.*;

/**
 * Bounded node observations for one Attempt on one node, one series per metric (GPU: per device).
 * A repeated source timestamp is the same sample; an invalid, stale or future observation breaks that series.
 */
public final class NodeSampleWindow {
    public record Sample(double value,Instant observedAt) {}
    private final UUID attemptId;
    private final String nodeName;
    private final Instant eligibleSince;
    private final Map<String,ArrayDeque<Sample>> metrics=new TreeMap<>();
    public NodeSampleWindow(UUID attemptId,String nodeName,Instant eligibleSince){this.attemptId=attemptId;this.nodeName=nodeName;this.eligibleSince=eligibleSince;}
    public boolean matches(UUID attempt,String node,Instant eligible){return attemptId.equals(attempt) && nodeName.equals(node) && eligibleSince.equals(eligible);}
    public void observe(String metric,double value,Instant observedAt,boolean fresh,Instant now,OffloadPolicy policy) {
        if(!fresh || !Double.isFinite(value) || value<0 || value>100 || observedAt.isAfter(now.plusSeconds(5)) ||
            observedAt.isBefore(now.minusSeconds(policy.maxSampleAgeSeconds()))){metrics.remove(metric);return;}
        var samples=metrics.computeIfAbsent(metric,key->new ArrayDeque<>());
        if(!samples.isEmpty() && !observedAt.isAfter(samples.peekFirst().observedAt()))return;
        samples.addFirst(new Sample(value,observedAt));
        while(samples.size()>policy.consecutiveSamples())samples.removeLast();
    }
    /** Series not observed in the current snapshot lose continuity. */
    public void retain(Set<String> present){metrics.keySet().retainAll(present);}
    /** Newest first per metric. */
    public Map<String,List<Sample>> recent() {
        var result=new TreeMap<String,List<Sample>>();metrics.forEach((k,v)->result.put(k,List.copyOf(v)));return result;
    }
}
