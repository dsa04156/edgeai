package io.edgeai.app.dto;
import io.edgeai.domain.execution.TaskSnapshot;
import java.util.List;
import java.util.Map;
public record TaskDetailResponse(TaskResponse task,List<TaskAttemptResponse> attempts,List<OffloadOperationResponse> offloads,RuntimeTelemetryResponse telemetry,Map<?,?> vdInputs) {
    public static TaskDetailResponse from(TaskSnapshot s) { return new TaskDetailResponse(TaskResponse.from(s.task()),s.attempts().stream().map(TaskAttemptResponse::from).toList(),s.offloads().stream().map(OffloadOperationResponse::from).toList(),RuntimeTelemetryResponse.from(s.telemetry()),s.vdInputsJson()==null?null:(Map<?,?>)io.edgeai.app.support.WorkflowInput.JSON.decode(s.vdInputsJson())); }
}
