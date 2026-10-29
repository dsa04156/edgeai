package io.edgeai.domain.resourceprofile;

import java.time.Instant;
import java.util.List;

/**
 * Per-container CPU (millicores) and working-set memory (bytes) of the exact target Pods in a bounded window.
 * Aggregation is PER_POD_CONTAINER: the distribution pools every Pod's own samples, so with several replicas the
 * maximum is the largest single Pod, never the workload total. {@code perPod} keeps each replica's own envelope.
 */
public record WorkloadMeasurement(Instant start, Instant end, int stepSeconds, List<ObservedPod> pods,
                                  UsageSummary cpu, UsageSummary memory, String method, List<PodUsage> perPod) {
    public static final String AGGREGATION = "PER_POD_CONTAINER";
    public WorkloadMeasurement { pods = List.copyOf(pods); perPod = perPod == null ? List.of() : List.copyOf(perPod); }
}
