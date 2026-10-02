package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.*;
import io.edgeai.domain.remote.RemoteTarget;
public record TaskAttempt(UUID id, UUID taskId, int number, long epoch, String state, String mode,UUID nodeId,String cause,List<String> excludedNodeNames,Instant createdAt,Instant updatedAt,RemoteTarget remoteTarget) {
    public TaskAttempt { excludedNodeNames=List.copyOf(excludedNodeNames); }
    public TaskAttempt(UUID id,UUID taskId,int number,long epoch,String state,String mode,UUID nodeId,String cause,List<String> excludedNodeNames,Instant createdAt,Instant updatedAt){
        this(id,taskId,number,epoch,state,mode,nodeId,cause,excludedNodeNames,createdAt,updatedAt,null);
    }
}
