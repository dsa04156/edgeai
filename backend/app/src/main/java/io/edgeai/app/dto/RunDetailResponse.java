package io.edgeai.app.dto;
import java.util.List;
public record RunDetailResponse(WorkflowRunResponse run, List<TaskResponse> tasks) {}
