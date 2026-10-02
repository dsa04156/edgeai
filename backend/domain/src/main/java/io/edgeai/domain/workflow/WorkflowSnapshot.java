package io.edgeai.domain.workflow;
import java.util.List;
public record WorkflowSnapshot(Workflow workflow, List<WorkflowVersion> versions) {}
