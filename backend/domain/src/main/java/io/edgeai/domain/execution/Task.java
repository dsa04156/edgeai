package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.UUID;
public record Task(UUID id, UUID runId, UUID definitionId, String key, String state, String cancellationReason,
                   Instant createdAt, Instant updatedAt) {}
