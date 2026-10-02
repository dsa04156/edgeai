package io.edgeai.domain.storage;
import java.time.Instant;
import java.util.*;
public record TaskResult(UUID id, UUID taskId, UUID attemptId, UUID runtimeId, long epoch,
        UUID producerPodUid, String manifestDigest, Instant createdAt, List<Output> outputs,UUID remoteAllocationId,UUID vdRuntimeId) {
    public TaskResult { outputs=List.copyOf(outputs); }
    public TaskResult(UUID id,UUID taskId,UUID attemptId,UUID runtimeId,long epoch,UUID producerPodUid,String manifestDigest,Instant createdAt,List<Output> outputs,UUID remoteAllocationId) {
        this(id,taskId,attemptId,runtimeId,epoch,producerPodUid,manifestDigest,createdAt,outputs,remoteAllocationId,null);
    }
    public TaskResult(UUID id,UUID taskId,UUID attemptId,UUID runtimeId,long epoch,UUID producerPodUid,String manifestDigest,Instant createdAt,List<Output> outputs) {
        this(id,taskId,attemptId,runtimeId,epoch,producerPodUid,manifestDigest,createdAt,outputs,null);
    }
    public record Output(String port, VerifiedArtifact artifact) {}
}
