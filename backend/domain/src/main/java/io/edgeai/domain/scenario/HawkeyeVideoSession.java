package io.edgeai.domain.scenario;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A sequence of bounded BATCH Runs (windows) of one published workflow version over one pinned camera. For a
 * continuous-observation version, observerEpoch identifies the observer instance the session started.
 */
public record HawkeyeVideoSession(UUID id, UUID idempotencyKey, String requestDigest, UUID workflowVersionId, String deviceId,
        UUID deviceUid, String cameraSourceJson, int maxWindows, String state, String endReason, String failureCode, String failureMessage,
        Instant createdAt, Instant updatedAt, Instant endedAt, String observerEpoch, Instant observerStartedAt) {
    /** Run states after which a window no longer changes. */
    public static final Set<String> FINISHED_RUN = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    /** One window: the platform Run it created and that Run's current state. */
    public record Window(int seq, UUID runId, String runState, Instant createdAt, Instant runUpdatedAt) {}
}
