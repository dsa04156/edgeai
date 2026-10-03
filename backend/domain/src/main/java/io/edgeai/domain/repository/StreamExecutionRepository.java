package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.*;

/** Durable route membership and completion grants. Every mutation holds the Run lock. */
public interface StreamExecutionRepository {
    record TaskCompletion(UUID attemptId, UUID checkpointId, Instant createdAt, Instant grantedAt) {}
    record DeviceCompletion(UUID generationId, long sequence, Instant createdAt, Instant grantedAt) {}
    Optional<String> bindingDigest(UUID runId);
    void freeze(UUID runId, String digest, Instant now);
    Optional<TaskCompletion> task(UUID attemptId);
    Optional<DeviceCompletion> device(UUID generationId);
    void recordTask(UUID attemptId, UUID checkpointId, Instant now);
    void recordDevice(UUID generationId, long sequence, Instant now);
    void grant(List<UUID> attempts, List<UUID> generations, Instant now);
}
