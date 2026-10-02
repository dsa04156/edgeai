package io.edgeai.domain.storage;
import java.time.Instant;
import java.util.*;
public record TaskResult(UUID id, UUID taskId, UUID attemptId, UUID runtimeId, long epoch,
        UUID producerPodUid, String manifestDigest, Instant createdAt, List<Output> outputs) {
    public TaskResult { outputs=List.copyOf(outputs); }
    public record Output(String port, VerifiedArtifact artifact) {}
}
