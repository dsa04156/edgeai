package io.edgeai.domain.device;
import java.time.Instant;
import java.util.UUID;
public record DeviceAttachment(UUID id, UUID deviceId, UUID nodeId, String port, Instant attachedAt, Instant detachedAt) {}
