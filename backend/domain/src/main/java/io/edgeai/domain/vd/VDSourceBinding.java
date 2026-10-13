package io.edgeai.domain.vd;

import io.edgeai.domain.device.Device;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record VDSourceBinding(UUID id, UUID vdId, String sourceKey, UUID deviceId,
                              UUID deviceProfileVersionId, Device.SourceMode sourceMode,
                              long openedRevision, Long closedRevision, Instant openedAt, Instant closedAt,
                              String managedDeviceId, UUID deviceUid, String deviceModel,List<String> properties,int maxAgeSeconds) {
    public VDSourceBinding { properties=List.copyOf(properties); }
    public VDSourceBinding(UUID id,UUID vdId,String key,UUID deviceId,UUID profile,Device.SourceMode mode,
                           long revision,Long closedRevision,Instant openedAt,Instant closedAt,String managedId,UUID uid,String model) {
        this(id,vdId,key,deviceId,profile,mode,revision,closedRevision,openedAt,closedAt,managedId,uid,model,List.of(),30);
    }
    public VDSourceBinding(UUID id, UUID vdId, String key, UUID deviceId, UUID profile, Device.SourceMode mode,
                           long revision, Long closedRevision, Instant openedAt, Instant closedAt) {
        this(id,vdId,key,deviceId,profile,mode,revision,closedRevision,openedAt,closedAt,null,null,null,List.of(),30);
    }
    public String sourceId() { return managedDeviceId == null ? deviceId.toString() : managedDeviceId; }
}
