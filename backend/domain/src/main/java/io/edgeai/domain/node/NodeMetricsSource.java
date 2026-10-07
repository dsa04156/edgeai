package io.edgeai.domain.node;

import java.time.Instant;
import java.util.List;

/** Read-only observations, independent of Kubernetes allocatable capacity. */
public interface NodeMetricsSource {
    Snapshot snapshot(Instant now);

    record Measurement(String key, String device, double value, String unit, Instant observedAt, boolean fresh) {}
    record Readiness(String status, Instant observedAt, boolean fresh) {}
    record Accelerator(String device, String kind, String vendor, String model, Instant observedAt, boolean fresh) {}
    record NodeMetrics(String nodeName, List<Measurement> measurements, Readiness readiness, List<Accelerator> accelerators) {
        public NodeMetrics(String nodeName, List<Measurement> measurements, Readiness readiness) { this(nodeName, measurements, readiness, List.of()); }
        public NodeMetrics(String nodeName, List<Measurement> measurements) { this(nodeName, measurements, null); }
    }
    record Snapshot(String status, Instant fetchedAt, int maxAgeSeconds, List<NodeMetrics> items, int unmappedSeries) {}
}
