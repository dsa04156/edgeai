package io.edgeai.app.dto;

import io.edgeai.app.support.VirtualDeviceInput;
import io.edgeai.domain.vd.VirtualDevice;
import java.time.Instant;
import java.util.*;

public record VirtualDeviceResponse(UUID id, String key, String displayName, UUID profileVersionId,
                                    UUID serviceProfileVersionId, String state, long revision, Map<String,Object> placement,
                                    Instant createdAt, Instant updatedAt) {
    public static VirtualDeviceResponse from(VirtualDevice vd) {
        return new VirtualDeviceResponse(vd.id(),vd.key(),vd.displayName(),vd.profileVersionId(),vd.serviceProfileVersionId(),
            vd.state().name(),vd.revision(),VirtualDeviceInput.document(vd.placement()),vd.createdAt(),vd.updatedAt());
    }
}
