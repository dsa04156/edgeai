package io.edgeai.domain.vd;

import io.edgeai.domain.device.Device;
import java.time.Instant;
import java.util.UUID;

public record VDSourceBinding(UUID id, UUID vdId, String sourceKey, UUID deviceId,
                              UUID deviceProfileVersionId, Device.SourceMode sourceMode,
                              long openedRevision, Long closedRevision, Instant openedAt, Instant closedAt) {}
