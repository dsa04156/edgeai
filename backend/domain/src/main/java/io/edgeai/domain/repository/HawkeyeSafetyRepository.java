package io.edgeai.domain.repository;

import io.edgeai.domain.scenario.HawkeyeSafetyRun;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every state change is one conditional statement on the run row; owner-taking methods act only on runs that owner holds. */
public interface HawkeyeSafetyRepository {
    /** False when the idempotency key exists or another run is active. */
    boolean create(UUID id, UUID key, String sampleId, String referenceLabel, UUID owner, Duration lease);
    Optional<HawkeyeSafetyRun> find(UUID id);
    Optional<HawkeyeSafetyRun> byIdempotencyKey(UUID key);
    Optional<UUID> active();
    List<HawkeyeSafetyRun> list(int limit, int offset);
    /** QUEUED → RUNNING with the immutable target snapshot. */
    boolean begin(UUID id, UUID owner, String snapshotJson);
    /** RUNNING → SUCCEEDED (category present) or FAILED with code, keeping the model's answer. */
    boolean finish(UUID id, UUID owner, HawkeyeSafetyRun.Result result, String failureCode, String failureMessage);
    /** QUEUED/RUNNING → FAILED without a model answer. */
    boolean fail(UUID id, UUID owner, String code, String message);
    void renew(UUID owner, Collection<UUID> ids, Duration lease);
    int interruptOwned(UUID owner, String code);
    int interruptExpired(String code);
}
