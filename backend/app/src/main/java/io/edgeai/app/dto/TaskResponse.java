package io.edgeai.app.dto;
import io.edgeai.domain.execution.Task;
import java.time.Instant;
import java.util.UUID;
public record TaskResponse(UUID id, UUID runId, UUID definitionId, String key, String state, String cancellationReason, Instant createdAt, Instant updatedAt,String initialMode,UUID initialNodeId,UUID initialVdId,io.edgeai.domain.remote.RemoteTarget initialRemoteTarget) {
    public static TaskResponse from(Task t) { return new TaskResponse(t.id(),t.runId(),t.definitionId(),t.key(),t.state(),t.cancellationReason(),t.createdAt(),t.updatedAt(),t.initialMode(),t.initialNodeId(),t.initialVdId(),t.initialRemoteTarget()); }
}
