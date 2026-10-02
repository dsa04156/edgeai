package io.edgeai.app.dto;
import java.time.Instant;
import java.util.*;
public record DeviceAttachmentResponse(UUID id, UUID deviceId, UUID nodeId, String port, Instant attachedAt, Instant detachedAt) {
    public static DeviceAttachmentResponse from(io.edgeai.domain.device.DeviceAttachment a) { return new DeviceAttachmentResponse(a.id(),a.deviceId(),a.nodeId(),a.port(),a.attachedAt(),a.detachedAt()); }
}
