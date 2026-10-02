package io.edgeai.app.service;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.repository.NodeRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NodeService {
    private final NodeRepository repository;
    public NodeService(NodeRepository repository) { this.repository=repository; }
    public List<ExecutionNode> list(int limit,int offset) { page(limit,offset);return repository.list(limit+1,offset); }
    public ExecutionNode find(UUID id) { return repository.find(id).orElseThrow(()->new ControlPlaneException(404,"NODE_NOT_FOUND","관측된 노드를 찾을 수 없습니다.")); }
    @Transactional
    public void recordSnapshot(List<ExecutionNode> nodes,Instant observedAt) { repository.replaceSnapshot(nodes,observedAt); }
    public static void page(int limit,int offset) {
        if (limit<1 || limit>100 || offset<0 || offset>1000000) throw new IllegalArgumentException("Invalid pagination");
    }
}
