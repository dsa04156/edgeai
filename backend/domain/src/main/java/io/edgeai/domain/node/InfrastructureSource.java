package io.edgeai.domain.node;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Live cluster inventory; independent of the application Device registry and execution history. */
public interface InfrastructureSource {
    /** allocatable is the Kubernetes scheduling exposure (status.allocatable), not observed physical hardware. */
    record Node(String name, String kind, String architecture, String operatingSystem, String status, Map<String, String> allocatable) {
        public Node { allocatable = Collections.unmodifiableSortedMap(new TreeMap<>(allocatable)); }
    }
    record Sensor(String name, String nodeName, String model, String serviceName, String adminState, String operatingState, List<String> properties) {}
    record Snapshot(String nodesStatus, String sensorsStatus, Instant fetchedAt, List<Node> nodes, List<Sensor> sensors) {}
    List<Node> nodes();
}
