package io.edgeai.domain.device;
import java.time.Instant;
import java.util.UUID;
public record DeviceObservation(UUID id, UUID deviceId, UUID sessionId, long sequence, Instant observedAt,
                                Instant receivedAt, String status, String attributesJson, String digest) {}
