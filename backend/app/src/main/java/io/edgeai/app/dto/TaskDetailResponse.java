package io.edgeai.app.dto;
import io.edgeai.domain.execution.TaskSnapshot;
import java.util.List;
public record TaskDetailResponse(TaskResponse task, List<TaskAttemptResponse> attempts,List<OffloadOperationResponse> offloads,RuntimeTelemetryResponse telemetry) {
    public static TaskDetailResponse from(TaskSnapshot s) { return new TaskDetailResponse(TaskResponse.from(s.task()),s.attempts().stream().map(TaskAttemptResponse::from).toList(),s.offloads().stream().map(OffloadOperationResponse::from).toList(),RuntimeTelemetryResponse.from(s.telemetry())); }
}
