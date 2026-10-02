package io.edgeai.app.dto;
import io.edgeai.domain.execution.WorkflowRun;
import io.edgeai.domain.execution.RetryPolicy;
import io.edgeai.app.support.WorkflowInput;
import java.time.Instant;
import java.util.UUID;
public record WorkflowRunResponse(UUID id, UUID workflowVersionId, String mode, UUID nodeId, Object parameters, RetryPolicy retry, Object offload, String state, Instant createdAt, Instant updatedAt) {
    public static WorkflowRunResponse from(WorkflowRun r) { return new WorkflowRunResponse(r.id(),r.workflowVersionId(),r.mode(),r.nodeId(),WorkflowInput.JSON.decode(r.parametersJson()),r.retry(),r.offloadPolicyJson()==null?null:WorkflowInput.JSON.decode(r.offloadPolicyJson()),r.state(),r.createdAt(),r.updatedAt()); }
}
