package io.edgeai.domain.aimodel;

import java.time.Instant;
import java.util.*;

/**
 * Kubernetes access of model profiling, confined to one namespace and to objects carrying this feature's labels.
 * Failures are {@link io.edgeai.domain.runtime.RuntimeGatewayException}: UNAVAILABLE when the API did not answer
 * clearly (never treated as success) and OWNERSHIP_CONFLICT when an object of the run's name is not exactly ours.
 */
public interface ProfilingGateway {
    /**
     * The model container of the measurement Pod; times are null until Kubernetes reports them. {@code imageId} is the
     * runtime's resolved image reference (normally {@code repo@sha256:…}), used to confirm the pinned image really ran.
     */
    record PodState(String name, UUID uid, String nodeName, String podIp, String phase, boolean ready, int restarts,
                    String waitingReason, String terminatedReason, Integer exitCode, Instant startedAt, Instant finishedAt,
                    boolean deleting, String imageId) {}
    /**
     * {@code jobFailedReason} is the reason of a Failed condition (e.g. DeadlineExceeded); complete is a Complete condition;
     * {@code suspended} is a Job still held by {@code spec.suspend} (created, never resumed: no Pod can exist for it).
     */
    record Observation(UUID jobUid, boolean suspended, boolean complete, String jobFailedReason, List<PodState> pods) {
        public Observation { pods = List.copyOf(pods); }
    }

    String namespace();
    /**
     * Creates the run's Job <em>suspended</em> (no Pod is created for it) or adopts an existing one only when its whole
     * intended spec is identical; returns its UID. A create delayed past a cancel therefore never runs anything.
     */
    UUID ensureJob(ProfilingJob job);
    /**
     * Lets the suspended Job of {@code jobUid} start Pods. Reads the Job, then asks {@code admitted} (the caller's durable
     * record that the run still wants it), then patches {@code spec.suspend=false} conditioned on the UID and the
     * resourceVersion read first, so a resume delayed past a delete or any other change is refused and never recreates
     * the Job. True when the Job runs (now or already), false when absent, deleting, changed concurrently or not admitted.
     */
    boolean resume(UUID runId, UUID jobUid, java.util.function.BooleanSupplier admitted);
    /** Empty when the run's Job does not exist (never created, deleted or garbage-collected). */
    Optional<Observation> observe(UUID runId);
    /** Deletes the run's Job (UID precondition, foreground); true only once no Job or Pod of the run remains. */
    boolean delete(UUID runId);
    /** Runs that still own a Job or Pod in the namespace, for orphan cleanup after restarts. */
    Set<UUID> ownedRuns();
}
