package io.edgeai.domain.vd;

import java.time.Instant;
import java.util.UUID;

public record VDOperation(UUID id, UUID vdId, String requestKey, String requestDigest, String kind,
                          long requestedRevision, String configurationJson, String configurationDigest,
                          UUID sourceRuntimeId, UUID targetRuntimeId, String state, String reason,
                          Instant createdAt, Instant updatedAt, Instant finishedAt) {
    public boolean terminal() { return finishedAt!=null; }
}
