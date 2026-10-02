package io.edgeai.domain.workflow;
import java.time.Instant;
import java.util.UUID;
public record Workflow(UUID id, String key, String displayName, String creationDigest, Instant createdAt) {}
