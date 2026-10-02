package io.edgeai.app.dto;
import io.edgeai.domain.execution.TaskAttempt;
import java.time.Instant;
import java.util.UUID;
public record TaskAttemptResponse(UUID id, UUID taskId, int number, long epoch, String state,String mode,UUID nodeId,String cause, Instant createdAt, Instant updatedAt) {
    public static TaskAttemptResponse from(TaskAttempt a) { return new TaskAttemptResponse(a.id(),a.taskId(),a.number(),a.epoch(),a.state(),a.mode(),a.nodeId(),a.cause(),a.createdAt(),a.updatedAt()); }
}
