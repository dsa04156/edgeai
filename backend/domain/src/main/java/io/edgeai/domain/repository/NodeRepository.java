package io.edgeai.domain.repository;
import io.edgeai.domain.node.ExecutionNode;
import java.time.Instant;
import java.util.*;
public interface NodeRepository {
    void replaceSnapshot(List<ExecutionNode> nodes, Instant observedAt);
    List<ExecutionNode> list(int limit, int offset);
    Optional<ExecutionNode> find(UUID id);
}
