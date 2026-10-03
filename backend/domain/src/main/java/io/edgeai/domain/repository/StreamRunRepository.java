package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.*;

/** Immutable public Run intent and pinned sources. Mutations hold Device -> Run locks. */
public interface StreamRunRepository {
    record Configuration(UUID runId,String namespace,String brokerDigest,int leaseSeconds,Instant createdAt) {}
    record DeviceBinding(UUID routeId,UUID runId,UUID deviceId,UUID sessionId,long epoch) {}
    void create(Configuration configuration);
    Optional<Configuration> find(UUID runId);
    void bind(DeviceBinding binding);
    List<DeviceBinding> bindings(UUID runId);
    List<UUID> active(String namespace,UUID after,int limit);
    void release(UUID runId,Collection<UUID> tasks,Instant now);
}
