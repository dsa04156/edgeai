package io.edgeai.domain.workflow;
import java.time.Instant;
import java.util.UUID;
public record WorkflowVersion(UUID id, UUID workflowId, String version, String dagJson, String digest, Instant createdAt) {}
