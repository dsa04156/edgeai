package io.edgeai.app.dto;
import io.edgeai.domain.workflow.Workflow;
import java.time.Instant;
import java.util.UUID;
public record WorkflowResponse(UUID id, String key, String displayName, Instant createdAt) {
    public static WorkflowResponse from(Workflow w) { return new WorkflowResponse(w.id(),w.key(),w.displayName(),w.createdAt()); }
}
