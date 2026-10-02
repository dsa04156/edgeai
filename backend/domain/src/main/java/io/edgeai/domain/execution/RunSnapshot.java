package io.edgeai.domain.execution;
import java.util.List;
public record RunSnapshot(WorkflowRun run, List<Task> tasks) {}
