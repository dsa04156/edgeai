package io.edgeai.app.service;

import io.edgeai.domain.node.NodeMetricsSource;
import io.edgeai.domain.node.NodeMetricsSource.Snapshot;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class NodeMetricsService {
    private final NodeMetricsSource source;
    private final Clock clock;
    private Snapshot cached;
    private Instant expiresAt = Instant.MIN;
    public NodeMetricsService(ObjectProvider<NodeMetricsSource> source, Clock clock) {
        this.source = source.getIfAvailable(); this.clock = clock;
    }
    // Concurrent dashboard requests share one bounded upstream query, including failure results.
    public synchronized Snapshot snapshot() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(expiresAt)) return cached;
        if (source == null) cached = new Snapshot("DISABLED", now, 90, List.of(), 0);
        else {
            try { cached = source.snapshot(now); }
            catch (RuntimeException failure) { cached = new Snapshot("UNAVAILABLE", now, 90, List.of(), 0); }
        }
        expiresAt = clock.instant().plusSeconds(5);
        return cached;
    }
}
