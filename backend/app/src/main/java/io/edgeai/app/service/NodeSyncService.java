package io.edgeai.app.service;
import io.edgeai.domain.node.NodeInventory;
import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="edgeai.kubernetes.enabled",havingValue="true")
public class NodeSyncService {
    private final NodeInventory inventory;
    private final NodeService nodes;
    private final Clock clock;
    public NodeSyncService(NodeInventory inventory,NodeService nodes,Clock clock) { this.inventory=inventory;this.nodes=nodes;this.clock=clock; }
    @Scheduled(fixedDelayString="${edgeai.kubernetes.poll-ms:15000}")
    public void synchronize() {
        try { var now=clock.instant();nodes.recordSnapshot(inventory.snapshot(now),now); }
        catch (RuntimeException e) {
            // Do not log upstream bodies, URLs, token paths or credentials. Cached nodes naturally become STALE.
            LoggerFactory.getLogger(NodeSyncService.class).warn("Node observation failed; previous snapshot retained ({})",e.getClass().getSimpleName());
        }
    }
}
