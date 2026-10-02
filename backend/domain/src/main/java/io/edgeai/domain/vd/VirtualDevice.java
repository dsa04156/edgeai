package io.edgeai.domain.vd;

import java.time.Instant;
import java.util.UUID;

public record VirtualDevice(UUID id, String key, String displayName, UUID profileVersionId,
                            UUID serviceProfileVersionId, State state, long revision, Placement placement,
                            String creationDigest, Instant createdAt, Instant updatedAt) {
    public enum State { REGISTERED, RELEASED }
    public enum Mode { AUTO, NODE }
    public record Placement(Mode mode, UUID nodeId) {
        public Placement {
            if (mode == null || (mode == Mode.NODE) != (nodeId != null))
                throw new IllegalArgumentException("NODE requires nodeId; AUTO has no nodeId");
        }
    }
}
