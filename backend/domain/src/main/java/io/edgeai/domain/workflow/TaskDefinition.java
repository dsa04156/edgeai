package io.edgeai.domain.workflow;
import java.util.UUID;
public record TaskDefinition(UUID id, UUID workflowVersionId, String key, UUID serviceProfileVersionId, String parametersJson) {}
