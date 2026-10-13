package io.edgeai.app.dto;

import io.edgeai.domain.repository.VirtualDeviceRepository;
import io.edgeai.domain.vd.VDSourceBinding;
import java.time.Instant;
import java.util.*;

public record VirtualDeviceDetailResponse(VirtualDeviceResponse vd, List<Source> activeSources,
                                          List<Source> sourceHistory, boolean sourceHistoryTruncated) {
    public record Source(UUID id,UUID vdId,String sourceKey,String deviceId,UUID deviceUid,String deviceModel,
                         String sourceKind,long openedRevision,Long closedRevision,Instant openedAt,Instant closedAt,List<String> properties,int maxAgeSeconds) {
        static Source from(VDSourceBinding b) {
            return new Source(b.id(),b.vdId(),b.sourceKey(),b.sourceId(),b.deviceUid(),b.deviceModel(),
                b.managedDeviceId()==null?"legacy":b.sourceMode().name().equals("LIVE")?"physical":"virtual",
                b.openedRevision(),b.closedRevision(),b.openedAt(),b.closedAt(),b.properties(),b.maxAgeSeconds());
        }
    }
    public static VirtualDeviceDetailResponse from(VirtualDeviceRepository.Snapshot snapshot) {
        return new VirtualDeviceDetailResponse(VirtualDeviceResponse.from(snapshot.vd()),snapshot.activeSources().stream().map(Source::from).toList(),
            snapshot.sourceHistory().stream().map(Source::from).toList(),snapshot.sourceHistoryTruncated());
    }
}
