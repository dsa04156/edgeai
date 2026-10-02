package io.edgeai.domain.device;

import java.time.Instant;
import java.util.UUID;

public record Device(UUID id, String key, String displayName, UUID profileVersionId, SourceMode sourceMode,
                     State state, long revision, long sessionEpoch, String creationDigest,
                     String lastStatus, Instant lastSeenAt, Instant createdAt, Instant updatedAt) {
    public enum SourceMode { LIVE, REPLAY, SYNTHETIC }
    public enum State { ACTIVE, RELEASED }
    public String connectionStatus(Instant now) {
        if (state == State.RELEASED) return "RELEASED";
        if (lastSeenAt == null) return "UNKNOWN";
        return lastSeenAt.plusSeconds(60).isBefore(now) ? "STALE" : lastStatus;
    }
}
