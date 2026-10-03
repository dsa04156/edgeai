package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.UUID;
public record Task(UUID id, UUID runId, UUID definitionId, String key, String state, String cancellationReason,
                   Instant createdAt, Instant updatedAt,String initialMode,UUID initialNodeId,UUID initialVdId,io.edgeai.domain.remote.RemoteTarget initialRemoteTarget) {
    public Task(UUID id,UUID runId,UUID definitionId,String key,String state,String cancellationReason,Instant createdAt,Instant updatedAt,String initialMode,UUID initialNodeId){
        this(id,runId,definitionId,key,state,cancellationReason,createdAt,updatedAt,initialMode,initialNodeId,null,null);
    }
}
