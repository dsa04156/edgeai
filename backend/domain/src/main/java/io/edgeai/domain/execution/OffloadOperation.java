package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.UUID;
public record OffloadOperation(UUID id,UUID taskId,UUID runId,UUID sourceAttemptId,UUID targetAttemptId,
        UUID targetNodeId,UUID idempotencyKey,String requestDigest,String namespace,String state,String failureReason,
        Instant drainDeadline,int startTimeoutSeconds,Instant startDeadline,Instant createdAt,Instant updatedAt) {}
