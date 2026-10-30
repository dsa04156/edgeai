package io.edgeai.domain.aimodel;

import java.time.Instant;
import java.util.UUID;

/** Immutable user-confirmed resource requirement revision of one model, citing the succeeded measurement run it rests on. */
public record ModelResourceProfile(UUID id, UUID modelId, int revision, UUID runId, UUID idempotencyKey, String requestFingerprint,
                                   String digest, String documentJson, Instant createdAt) {}
