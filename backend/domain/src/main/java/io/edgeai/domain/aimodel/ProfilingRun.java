package io.edgeai.domain.aimodel;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Durable state of one resource measurement of a registered model. {@code requestJson} is the normalised request
 * (allocation, duration, load); {@code progressJson} the latest load counts; {@code resultJson} the stored outcome.
 * A run keeps its model, node and global slot until {@code cleanedAt}: Kubernetes no longer has its Job or Pod.
 */
public record ProfilingRun(UUID id, UUID modelId, UUID idempotencyKey, String requestDigest, String state,
                           String nodeName, String nodeArchitecture, String requestJson, String jobName, UUID jobUid,
                           String podName, UUID podUid, String podIp, UUID driverOwner, Instant driverLeaseUntil,
                           String message, Instant phaseStartedAt, Instant windowStart, Instant windowEnd, Instant collectAfter,
                           String progressJson, String failureCode, String failureMessage, String resultJson,
                           Instant cancelRequestedAt, Instant finishedAt, String cleanupState, Instant cleanedAt,
                           Instant createdAt, Instant updatedAt) {
    public static final Set<String> FINISHED = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    public boolean finished() { return FINISHED.contains(state); }

    public ProfilingRun with(String state, String message, Instant now) {
        return new ProfilingRun(id, modelId, idempotencyKey, requestDigest, state, nodeName, nodeArchitecture, requestJson, jobName, jobUid,
            podName, podUid, podIp, driverOwner, driverLeaseUntil, message, this.state.equals(state) ? phaseStartedAt : now, windowStart, windowEnd,
            collectAfter, progressJson, failureCode, failureMessage, resultJson, cancelRequestedAt, finishedAt, cleanupState, cleanedAt, createdAt, now);
    }
    public ProfilingRun withJob(UUID jobUid) {
        return new ProfilingRun(id, modelId, idempotencyKey, requestDigest, state, nodeName, nodeArchitecture, requestJson, jobName, jobUid,
            podName, podUid, podIp, driverOwner, driverLeaseUntil, message, phaseStartedAt, windowStart, windowEnd, collectAfter, progressJson,
            failureCode, failureMessage, resultJson, cancelRequestedAt, finishedAt, cleanupState, cleanedAt, createdAt, updatedAt);
    }
    public ProfilingRun withPod(String podName, UUID podUid, String podIp) {
        return new ProfilingRun(id, modelId, idempotencyKey, requestDigest, state, nodeName, nodeArchitecture, requestJson, jobName, jobUid,
            podName, podUid, podIp, driverOwner, driverLeaseUntil, message, phaseStartedAt, windowStart, windowEnd, collectAfter, progressJson,
            failureCode, failureMessage, resultJson, cancelRequestedAt, finishedAt, cleanupState, cleanedAt, createdAt, updatedAt);
    }
    public ProfilingRun withDriver(UUID owner, Instant leaseUntil) {
        return new ProfilingRun(id, modelId, idempotencyKey, requestDigest, state, nodeName, nodeArchitecture, requestJson, jobName, jobUid,
            podName, podUid, podIp, owner, leaseUntil, message, phaseStartedAt, windowStart, windowEnd, collectAfter, progressJson,
            failureCode, failureMessage, resultJson, cancelRequestedAt, finishedAt, cleanupState, cleanedAt, createdAt, updatedAt);
    }
    public ProfilingRun withWindow(Instant start, Instant end, Instant collectAfter) {
        return new ProfilingRun(id, modelId, idempotencyKey, requestDigest, state, nodeName, nodeArchitecture, requestJson, jobName, jobUid,
            podName, podUid, podIp, driverOwner, driverLeaseUntil, message, phaseStartedAt, start, end, collectAfter, progressJson,
            failureCode, failureMessage, resultJson, cancelRequestedAt, finishedAt, cleanupState, cleanedAt, createdAt, updatedAt);
    }
    public ProfilingRun withProgress(String progressJson) {
        return new ProfilingRun(id, modelId, idempotencyKey, requestDigest, state, nodeName, nodeArchitecture, requestJson, jobName, jobUid,
            podName, podUid, podIp, driverOwner, driverLeaseUntil, message, phaseStartedAt, windowStart, windowEnd, collectAfter, progressJson,
            failureCode, failureMessage, resultJson, cancelRequestedAt, finishedAt, cleanupState, cleanedAt, createdAt, updatedAt);
    }
    /** Terminal outcome: the slot stays held (cleanup PENDING) until Kubernetes confirms the Job and Pod are gone. */
    public ProfilingRun finish(String state, String failureCode, String message, String resultJson, Instant cancelRequestedAt, Instant now) {
        if (!FINISHED.contains(state) || state.equals("FAILED") != (failureCode != null)) throw new IllegalArgumentException("Invalid terminal outcome");
        return new ProfilingRun(id, modelId, idempotencyKey, requestDigest, state, nodeName, nodeArchitecture, requestJson, jobName, jobUid,
            podName, podUid, podIp, driverOwner, driverLeaseUntil, message, now, windowStart, windowEnd, collectAfter, progressJson,
            failureCode, failureCode == null ? null : message, resultJson, cancelRequestedAt, now, "PENDING", null, createdAt, now);
    }
}
