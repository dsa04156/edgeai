package io.edgeai.app.service;

import io.edgeai.domain.node.InfrastructureSource;
import io.edgeai.domain.node.SensorInventorySource;
import io.edgeai.domain.node.InfrastructureSource.*;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class InfrastructureService {
    private final InfrastructureSource source;
    private final SensorInventorySource sensorSource;
    private final Clock clock;
    private Snapshot cached;
    private Instant expiresAt = Instant.MIN;
    public InfrastructureService(ObjectProvider<InfrastructureSource> sources, ObjectProvider<SensorInventorySource> sensorSources, Clock clock) {
        source = sources.getIfAvailable(); sensorSource = sensorSources.getIfAvailable(); this.clock = clock;
    }
    public synchronized void invalidate() { cached = null; expiresAt = Instant.MIN; }
    public synchronized Snapshot snapshot() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(expiresAt)) return cached;
        List<Node> nodes = List.of(); List<Sensor> sensors = List.of();
        String nodesStatus = "DISABLED", sensorsStatus = "DISABLED";
        if (source != null) {
            try { nodes = source.nodes(); nodesStatus = "AVAILABLE"; } catch (RuntimeException e) { nodesStatus = "UNAVAILABLE"; }
        }
        if (sensorSource != null) {
            try { sensors = sensorSource.sensors(); sensorsStatus = "AVAILABLE"; } catch (RuntimeException e) { sensorsStatus = "UNAVAILABLE"; }
        }
        cached = new Snapshot(nodesStatus, sensorsStatus, now, nodes, sensors);
        expiresAt = clock.instant().plusSeconds(10);
        return cached;
    }
}
