package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.UUID;
public record WorkflowRun(UUID id, UUID workflowVersionId, UUID idempotencyKey, String requestDigest,
                          String mode, UUID nodeId, String parametersJson, String state, Instant createdAt, Instant updatedAt) {}
