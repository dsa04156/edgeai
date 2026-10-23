package io.edgeai.domain.scenario;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One Hawkeye inference over one pinned warehouse sample; result is present once the model answered. */
public record HawkeyeSafetyRun(UUID id, UUID idempotencyKey, String sampleId, String referenceLabel, String state,
        String snapshotJson, Result result, String failureCode, String failureMessage, Instant createdAt, Instant startedAt,
        Instant finishedAt) {
    public static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING");
    public static final List<String> CATEGORIES = List.of("spill", "forklift_violation", "improper_stacking", "obstacle", "safe");

    /** category is null when the output named no single category; the raw output is kept either way. */
    public record Result(String category, String explanation, String rawOutput, String doneReason, String inputSha256,
            String processor, long sizeBytes, long sizeVramBytes, double inferenceMs, double loadMs, double roundTripMs,
            Integer promptEvalCount, Integer evalCount) {}
}
