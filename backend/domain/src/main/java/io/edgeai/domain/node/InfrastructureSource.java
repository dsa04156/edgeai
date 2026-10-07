package io.edgeai.domain.node;

import java.time.Instant;
import java.util.List;

/** Live cluster inventory; independent of the application Device registry and execution history. */
public interface InfrastructureSource {
    record Node(String name, String kind, String architecture, String operatingSystem, String status) {}
    record Sensor(String name, String nodeName, String model, String serviceName, String adminState, String operatingState, List<String> properties) {}
    record Snapshot(String nodesStatus, String sensorsStatus, Instant fetchedAt, List<Node> nodes, List<Sensor> sensors) {}
    List<Node> nodes();
}
