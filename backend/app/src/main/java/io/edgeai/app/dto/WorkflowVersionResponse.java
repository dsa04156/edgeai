package io.edgeai.app.dto;
import io.edgeai.domain.workflow.WorkflowVersion;
import io.edgeai.app.support.WorkflowInput;
import java.time.Instant;
import java.util.UUID;
public record WorkflowVersionResponse(UUID id, UUID workflowId, String version, Object dag, String digest, Instant createdAt) {
    public static WorkflowVersionResponse from(WorkflowVersion v) { return new WorkflowVersionResponse(v.id(),v.workflowId(),v.version(),WorkflowInput.JSON.decode(v.dagJson()),v.digest(),v.createdAt()); }
}
