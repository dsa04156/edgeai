package io.edgeai.domain.node;
import java.time.Instant;
import java.util.List;
public interface NodeInventory {
    List<ExecutionNode> snapshot(Instant observedAt);
}
