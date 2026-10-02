package io.edgeai.domain.device;
import java.time.Instant;
import java.util.UUID;
public record DeviceSession(UUID id, UUID deviceId, UUID bootId, long epoch, long lastSequence, Instant openedAt, Instant closedAt) {}
