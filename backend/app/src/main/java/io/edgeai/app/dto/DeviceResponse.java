package io.edgeai.app.dto;
import java.time.Instant;
import java.util.*;
public record DeviceResponse(UUID id, String key, String displayName, UUID profileVersionId, String sourceMode, String state, long revision, long sessionEpoch, String connectionStatus, Instant lastSeenAt, Instant createdAt, Instant updatedAt) {
    public static DeviceResponse from(io.edgeai.domain.device.Device d, Instant now) {
        return new DeviceResponse(d.id(),d.key(),d.displayName(),d.profileVersionId(),d.sourceMode().name(),d.state().name(),d.revision(),d.sessionEpoch(),d.connectionStatus(now),d.lastSeenAt(),d.createdAt(),d.updatedAt());
    }
}
