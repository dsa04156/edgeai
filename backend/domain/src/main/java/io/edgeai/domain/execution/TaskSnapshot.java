package io.edgeai.domain.execution;
import java.util.List;
public record TaskSnapshot(Task task, List<TaskAttempt> attempts,List<OffloadOperation> offloads,io.edgeai.domain.runtime.RuntimeTelemetry telemetry) {}
