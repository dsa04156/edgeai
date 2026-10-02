package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.UUID;
public record TaskAttempt(UUID id, UUID taskId, int number, long epoch, String state, Instant createdAt, Instant updatedAt) {}
