package io.edgeai.domain.scheduling;
import java.time.Instant;
import java.util.UUID;
/** Caller-supplied measurements of the artifact-store BATCH path; provenance is evidence, not a server-verified truth. */
public record SchedulingProfile(UUID id, UUID workflowVersionId, UUID idempotencyKey, String digest,
                                Instant measuredAt, Instant validUntil, String documentJson, Instant createdAt) {}
