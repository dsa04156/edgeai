package io.edgeai.domain.repository;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.workflow.*;
import java.time.Instant;
import java.util.*;
public interface ExecutionRepository {
    boolean create(WorkflowRun run);
    void initialize(WorkflowRun run, List<TaskDefinition> definitions, Set<String> roots);
    Optional<WorkflowRun> run(UUID id, boolean lock);
    Optional<WorkflowRun> byIdempotencyKey(UUID key);
    List<WorkflowRun> runs(int limit, int offset);
    List<Task> tasks(UUID runId);
    Optional<Task> task(UUID id);
    List<TaskAttempt> attempts(UUID taskId);
    void cancelTask(UUID taskId, String terminalState, String reason, Instant now);
    void reconcileRunState(UUID runId, Instant now);
}
