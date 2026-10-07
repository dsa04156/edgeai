package io.edgeai.domain.node;

import java.util.List;

/** EdgeX is the authoritative sensor registry, separate from Kubernetes node placement. */
public interface SensorInventorySource {
    List<InfrastructureSource.Sensor> sensors();
}
