package io.edgeai.app.dto;

import io.edgeai.domain.node.NodeMetricsSource;
import java.time.Instant;
import java.util.List;

public record NodeMetricsResponse(String status, Instant fetchedAt, int maxAgeSeconds, List<Node> items, int unmappedSeries) {
    public record Measurement(String key, String device, double value, String unit, Instant observedAt, boolean fresh) {}
    public record Readiness(String status, Instant observedAt, boolean fresh) {}
    public record Accelerator(String device, String kind, String vendor, String model, Instant observedAt, boolean fresh) {}
    public record Node(String nodeName, List<Measurement> measurements, Readiness readiness, List<Accelerator> accelerators) {}
    public static NodeMetricsResponse from(NodeMetricsSource.Snapshot snapshot) {
        return new NodeMetricsResponse(snapshot.status(), snapshot.fetchedAt(), snapshot.maxAgeSeconds(),
            snapshot.items().stream().map(node -> new Node(node.nodeName(), node.measurements().stream()
                .map(value -> new Measurement(value.key(), value.device(), value.value(), value.unit(), value.observedAt(), value.fresh())).toList(),
                node.readiness() == null ? null : new Readiness(node.readiness().status(), node.readiness().observedAt(), node.readiness().fresh()),
                node.accelerators().stream().map(a -> new Accelerator(a.device(), a.kind(), a.vendor(), a.model(), a.observedAt(), a.fresh())).toList())).toList(),
            snapshot.unmappedSeries());
    }
}
