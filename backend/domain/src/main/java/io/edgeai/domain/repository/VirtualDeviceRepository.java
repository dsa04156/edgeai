package io.edgeai.domain.repository;

import io.edgeai.domain.vd.*;
import java.time.Instant;
import java.util.*;

public interface VirtualDeviceRepository {
    boolean create(String key, String name, UUID profileId, UUID serviceId, VirtualDevice.Placement placement, String digest);
    Optional<VirtualDevice> find(UUID id, boolean lock);
    Optional<VirtualDevice> findByKey(String key);
    List<VirtualDevice> list(int limit, int offset);
    void update(UUID id, String name, VirtualDevice.Placement placement, VirtualDevice.State state, Instant now);
    List<VDSourceBinding> activeSources(UUID id);
    List<VDSourceBinding> sourceHistory(UUID id, int limit);
    void bind(VDSourceBinding binding);
    void close(UUID bindingId, long revision, Instant now);
    boolean canDeleteRegistration(UUID id);
    void deleteRegistration(UUID id);
    record Creation(VirtualDevice value, boolean created) {}
    record Snapshot(VirtualDevice vd, List<VDSourceBinding> activeSources,
                    List<VDSourceBinding> sourceHistory, boolean sourceHistoryTruncated) {}
}
