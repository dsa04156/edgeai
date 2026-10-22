package io.edgeai.domain.repository;

import io.edgeai.domain.scenario.ImageComparisonRun;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every state change is a single conditional statement, so Stop, dispatch, lease expiry and
 * completion linearize on the run row. Methods taking an owner only act on runs that owner still holds.
 */
public interface ImageComparisonRepository {
    /** False when the idempotency key exists or another run is active. */
    boolean create(UUID id, UUID key, int count, UUID owner, Duration lease);
    Optional<ImageComparisonRun> find(UUID id);
    Optional<ImageComparisonRun> byIdempotencyKey(UUID key);
    Optional<UUID> active();
    List<ImageComparisonRun> list(int limit, int offset);
    /** QUEUED → RUNNING with the immutable snapshot. */
    boolean begin(UUID id, UUID owner, String snapshotJson);
    /** Claims image index (dispatchedCount == index) while RUNNING; false once Stop was accepted. */
    boolean claim(UUID id, UUID owner, int index, Duration lease);
    /** Stores both targets' outcomes for one claimed image while the owner still holds the run. */
    boolean record(UUID id, UUID owner, List<ImageComparisonRun.Result> results);
    /** RUNNING → SUCCEEDED or STOPPING → STOPPED. */
    boolean complete(UUID id, UUID owner);
    /** QUEUED/RUNNING → FAILED; STOPPING → STOPPED (an accepted Stop wins), both keeping the failure details. */
    boolean fail(UUID id, UUID owner, String code, String message);
    /** QUEUED → STOPPED, RUNNING → STOPPING; other states are unchanged. */
    void stop(UUID id);
    /** Renews the leases of the owner's active runs. */
    void renew(UUID owner, Collection<UUID> ids, Duration lease);
    /** Marks the owner's active runs INTERRUPTED (orderly shutdown). */
    int interruptOwned(UUID owner, String code);
    /** Marks active runs whose lease expired INTERRUPTED; they are never resumed. */
    int interruptExpired(String code);
    List<ImageComparisonRun.Result> results(UUID id, int fromIndex, int limit);
}
