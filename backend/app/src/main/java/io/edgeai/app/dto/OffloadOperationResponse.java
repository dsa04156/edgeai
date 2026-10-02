package io.edgeai.app.dto;
import io.edgeai.domain.execution.OffloadOperation;
import java.time.Instant;
import java.util.UUID;
public record OffloadOperationResponse(UUID id,String kind,UUID taskId,UUID sourceAttemptId,UUID targetAttemptId,UUID targetNodeId,
        String trigger,java.util.List<String> excludedNodeNames,Object decision,String state,String failureReason,Instant drainDeadline,Instant startDeadline,Instant createdAt,Instant updatedAt,io.edgeai.domain.remote.RemoteTarget remoteTarget) implements OperationResponse {
    public static OffloadOperationResponse from(OffloadOperation o){return new OffloadOperationResponse(o.id(),"TASK_OFFLOAD",o.taskId(),o.sourceAttemptId(),o.targetAttemptId(),o.targetNodeId(),o.trigger(),o.excludedNodeNames(),o.decisionJson()==null?null:io.edgeai.app.support.WorkflowInput.JSON.decode(o.decisionJson()),o.state(),o.failureReason(),o.drainDeadline(),o.startDeadline(),o.createdAt(),o.updatedAt(),o.remoteTarget());}
}
