package io.edgeai.app.dto;
import java.time.Instant;
import java.util.*;
public record DeviceSessionResponse(UUID id, UUID deviceId, UUID bootId, long epoch, long lastSequence, Instant openedAt, Instant closedAt) {
    public static DeviceSessionResponse from(io.edgeai.domain.device.DeviceSession s) { return new DeviceSessionResponse(s.id(),s.deviceId(),s.bootId(),s.epoch(),s.lastSequence(),s.openedAt(),s.closedAt()); }
}
