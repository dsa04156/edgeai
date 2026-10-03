package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.*;
import io.edgeai.domain.remote.RemoteTarget;
public record OffloadOperation(UUID id,UUID taskId,UUID runId,UUID sourceAttemptId,UUID targetAttemptId,
        UUID targetNodeId,UUID idempotencyKey,String requestDigest,String namespace,String state,String failureReason,
        Instant drainDeadline,int startTimeoutSeconds,Instant startDeadline,Instant createdAt,Instant updatedAt,
        String trigger,List<String> excludedNodeNames,String decisionJson,RemoteTarget remoteTarget,List<OffloadMember> members) {
    public OffloadOperation { excludedNodeNames=List.copyOf(excludedNodeNames);members=List.copyOf(members); }
    public OffloadOperation(UUID id,UUID taskId,UUID runId,UUID sourceAttemptId,UUID targetAttemptId,UUID targetNodeId,UUID idempotencyKey,
            String requestDigest,String namespace,String state,String failureReason,Instant drainDeadline,int startTimeoutSeconds,
            Instant startDeadline,Instant createdAt,Instant updatedAt,String trigger,List<String> excludedNodeNames,String decisionJson,RemoteTarget remoteTarget) {
        this(id,taskId,runId,sourceAttemptId,targetAttemptId,targetNodeId,idempotencyKey,requestDigest,namespace,state,failureReason,
            drainDeadline,startTimeoutSeconds,startDeadline,createdAt,updatedAt,trigger,excludedNodeNames,decisionJson,remoteTarget,List.of());
    }
    public OffloadOperation withMembers(List<OffloadMember> values){
        return new OffloadOperation(id,taskId,runId,sourceAttemptId,targetAttemptId,targetNodeId,idempotencyKey,requestDigest,namespace,state,
            failureReason,drainDeadline,startTimeoutSeconds,startDeadline,createdAt,updatedAt,trigger,excludedNodeNames,decisionJson,remoteTarget,values);
    }
    public OffloadOperation(UUID id,UUID taskId,UUID runId,UUID sourceAttemptId,UUID targetAttemptId,UUID targetNodeId,UUID idempotencyKey,
            String requestDigest,String namespace,String state,String failureReason,Instant drainDeadline,int startTimeoutSeconds,
            Instant startDeadline,Instant createdAt,Instant updatedAt,String trigger,List<String> excludedNodeNames,String decisionJson) {
        this(id,taskId,runId,sourceAttemptId,targetAttemptId,targetNodeId,idempotencyKey,requestDigest,namespace,state,failureReason,
            drainDeadline,startTimeoutSeconds,startDeadline,createdAt,updatedAt,trigger,excludedNodeNames,decisionJson,null);
    }
    public OffloadOperation(UUID id,UUID taskId,UUID runId,UUID sourceAttemptId,UUID targetAttemptId,UUID targetNodeId,UUID idempotencyKey,
            String requestDigest,String namespace,String state,String failureReason,Instant drainDeadline,int startTimeoutSeconds,
            Instant startDeadline,Instant createdAt,Instant updatedAt) {
        this(id,taskId,runId,sourceAttemptId,targetAttemptId,targetNodeId,idempotencyKey,requestDigest,namespace,state,failureReason,
            drainDeadline,startTimeoutSeconds,startDeadline,createdAt,updatedAt,"MANUAL",List.of(),null);
    }
}
