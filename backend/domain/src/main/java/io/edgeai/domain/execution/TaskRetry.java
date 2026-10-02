package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.UUID;
public record TaskRetry(UUID taskId,UUID failedAttemptId,String namespace,Instant availableAt,Instant deadline) {}
