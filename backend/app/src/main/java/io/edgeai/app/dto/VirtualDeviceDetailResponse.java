package io.edgeai.app.dto;

import io.edgeai.domain.repository.VirtualDeviceRepository;
import io.edgeai.domain.vd.VDSourceBinding;
import java.util.List;

public record VirtualDeviceDetailResponse(VirtualDeviceResponse vd, List<VDSourceBinding> activeSources,
                                          List<VDSourceBinding> sourceHistory, boolean sourceHistoryTruncated) {
    public static VirtualDeviceDetailResponse from(VirtualDeviceRepository.Snapshot snapshot) {
        return new VirtualDeviceDetailResponse(VirtualDeviceResponse.from(snapshot.vd()),snapshot.activeSources(),snapshot.sourceHistory(),snapshot.sourceHistoryTruncated());
    }
}
