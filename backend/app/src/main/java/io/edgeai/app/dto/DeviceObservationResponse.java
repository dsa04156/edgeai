package io.edgeai.app.dto;
import java.time.Instant;
import java.util.*;
public record DeviceObservationResponse(UUID id, UUID deviceId, UUID sessionId, long sequence, Instant observedAt, Instant receivedAt, String status, Object attributes) {
    public static DeviceObservationResponse from(io.edgeai.domain.device.DeviceObservation o) { return new DeviceObservationResponse(o.id(),o.deviceId(),o.sessionId(),o.sequence(),o.observedAt(),o.receivedAt(),o.status(),io.edgeai.app.support.DeviceInput.JSON.decode(o.attributesJson())); }
}
