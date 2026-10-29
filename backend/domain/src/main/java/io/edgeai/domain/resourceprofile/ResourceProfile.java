package io.edgeai.domain.resourceprofile;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable resource requirement revision of one immutable SERVICE profile version. It is separate from the
 * timing-based SchedulingProfile and declares requirements; it does not reserve or guarantee resources.
 */
public record ResourceProfile(UUID id, UUID serviceProfileVersionId, int revision, UUID idempotencyKey, UUID analysisId,
                              String evidenceStatus, String digest, String documentJson, Instant createdAt) {
    public ResourceProfile { if (revision < 1) throw new IllegalArgumentException("Revision starts at 1"); }
}
