package io.edgeai.domain.resourceprofile;

import java.time.Instant;
import java.util.List;

/** Read-only observed usage. Implementations use fixed queries; callers only supply validated identities. */
public interface WorkloadMetricsSource {
    List<WorkloadCandidate> workloads(String namespace, Instant start, Instant end);
    List<ObservedPod> pods(WorkloadTarget target, Instant start, Instant end);
    WorkloadMeasurement measure(WorkloadTarget target, Instant start, Instant end);
    /** Device-level telemetry; attribution is TARGET_ASSOCIATED only when the exporter's Pod labels name only target Pods. */
    DeviceObservation device(DeviceRef device, String namespace, List<ObservedPod> targetPods, Instant start, Instant end);
}
