package io.edgeai.domain.vd;

import io.edgeai.domain.device.Device;
import java.time.Instant;
import java.util.UUID;

public record VDSourceBinding(UUID id, UUID vdId, String sourceKey, UUID deviceId,
                              UUID deviceProfileVersionId, Device.SourceMode sourceMode,
                              long openedRevision, Long closedRevision, Instant openedAt, Instant closedAt,
                              String managedDeviceId, UUID deviceUid, String deviceModel) {
    public VDSourceBinding(UUID id, UUID vdId, String key, UUID deviceId, UUID profile, Device.SourceMode mode,
                           long revision, Long closedRevision, Instant openedAt, Instant closedAt) {
        this(id,vdId,key,deviceId,profile,mode,revision,closedRevision,openedAt,closedAt,null,null,null);
    }
    public String sourceId() { return managedDeviceId == null ? deviceId.toString() : managedDeviceId; }
}
