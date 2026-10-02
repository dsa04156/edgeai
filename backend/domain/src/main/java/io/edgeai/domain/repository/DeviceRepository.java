package io.edgeai.domain.repository;
import io.edgeai.domain.device.*;
import java.time.Instant;
import java.util.*;

public interface DeviceRepository {
    boolean create(String key, String name, UUID profileId, Device.SourceMode mode, String digest);
    Optional<Device> find(UUID id, boolean lock);
    Optional<Device> findByKey(String key);
    List<Device> list(int limit, int offset);
    void rename(UUID id, String name, Instant now);
    void release(UUID id, Instant now);
    boolean hasVirtualDeviceBindings(UUID id);
    void advanceEpoch(UUID id, Instant now);
    List<DeviceAttachment> attachments(UUID deviceId);
    Optional<DeviceAttachment> activeAttachment(UUID deviceId);
    void closeAttachments(UUID deviceId, Instant now);
    void attach(DeviceAttachment attachment);
    List<DeviceSession> sessions(UUID deviceId);
    Optional<DeviceSession> sessionByBootId(UUID deviceId, UUID bootId);
    Optional<DeviceSession> activeSession(UUID deviceId);
    void closeSessions(UUID deviceId, Instant now);
    void openSession(DeviceSession session);
    Optional<DeviceObservation> observation(UUID sessionId, long sequence);
    List<DeviceObservation> observations(UUID deviceId);
    void observe(DeviceObservation observation);
    record Creation<T>(T value, boolean created) {}
}
