package io.edgeai.domain.repository;

import io.edgeai.domain.aimodel.ModelResourceProfile;
import io.edgeai.domain.aimodel.ProfilingRun;
import java.util.*;

public interface ModelProfilingRepository {
    /** Serialises run admission (model, node and global slots) inside the caller's transaction. */
    void lockSlots();
    Optional<ProfilingRun> runByIdempotencyKey(UUID key);
    /** Runs still holding a slot: not finished, or finished but not yet confirmed gone from Kubernetes. */
    List<ProfilingRun> holding();
    void create(ProfilingRun run);
    Optional<ProfilingRun> run(UUID id);
    /** Newest first. */
    List<ProfilingRun> runs(UUID modelId, int limit, int offset);
    Optional<ProfilingRun> latestRun(UUID modelId);
    /**
     * Writes {@code run} only if the stored row is still in {@code expectedState} with {@code expectedUpdatedAt}, so a
     * cancel and the reconciler never overwrite each other's transition. Returns false when the row moved on.
     */
    boolean update(ProfilingRun run, String expectedState, java.time.Instant expectedUpdatedAt);
    /** Releases the slot once Kubernetes no longer reports the run's Job or Pod. */
    void cleaned(UUID id, java.time.Instant at);

    void lockProfiles(UUID modelId, UUID idempotencyKey);
    Optional<ModelResourceProfile> profileByIdempotencyKey(UUID key);
    void createProfile(ModelResourceProfile profile);
    Optional<ModelResourceProfile> profile(UUID id);
    /** Newest revision first. */
    List<ModelResourceProfile> profiles(UUID modelId);
    Optional<ModelResourceProfile> latestProfile(UUID modelId);
}
