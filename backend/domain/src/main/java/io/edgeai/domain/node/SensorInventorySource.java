package io.edgeai.domain.node;

import java.util.List;

/** Device CRDs are the authoritative sensor registry; DeviceStatus supplies observed values/state. */
public interface SensorInventorySource {
    List<InfrastructureSource.Sensor> sensors();
}
