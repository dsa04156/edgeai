package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.UUID;
import io.edgeai.domain.remote.RemoteTarget;
public record WorkflowRun(UUID id, UUID workflowVersionId, UUID idempotencyKey, String requestDigest,
                          String mode, UUID nodeId, String parametersJson, RetryPolicy retry, String offloadPolicyJson, String state, Instant createdAt, Instant updatedAt,RemoteTarget remoteTarget) {
    public WorkflowRun(UUID id,UUID workflowVersionId,UUID idempotencyKey,String requestDigest,String mode,UUID nodeId,String parametersJson,RetryPolicy retry,String offloadPolicyJson,String state,Instant createdAt,Instant updatedAt){
        this(id,workflowVersionId,idempotencyKey,requestDigest,mode,nodeId,parametersJson,retry,offloadPolicyJson,state,createdAt,updatedAt,null);
    }
}
