package io.edgeai.app.dto;
import java.util.List;
public record WorkflowDetailResponse(WorkflowResponse workflow, List<WorkflowVersionResponse> versions, Integer nextOffset) {}
