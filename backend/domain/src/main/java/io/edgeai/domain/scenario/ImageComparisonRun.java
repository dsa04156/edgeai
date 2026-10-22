package io.edgeai.domain.scenario;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A durable GPU/NPU comparison over the first requestedCount images of the fixed dataset. */
public record ImageComparisonRun(UUID id, UUID idempotencyKey, int requestedCount, String state, int dispatchedCount,
        String snapshotJson, String failureCode, String failureMessage, Instant createdAt, Instant startedAt,
        Instant stopRequestedAt, Instant finishedAt) {
    public static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "STOPPING");
    public static final List<String> TARGETS = List.of("GPU", "NPU");

    /** One target's outcome for one image; a failed call keeps only its timing and failure code. */
    public record Result(int imageIndex, String target, String status, String topKJson, String inputSha256,
            Double inferenceMs, double roundTripMs, String failureCode) {}

}
