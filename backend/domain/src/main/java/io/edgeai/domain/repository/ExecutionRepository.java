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
    Optional<TaskAttempt> attempt(UUID id);
    void scheduleRetry(TaskRetry retry,Instant now);
    Optional<TaskRetry> retry(UUID taskId);
    List<UUID> dueRetries(String namespace,Instant now,int limit);
    void clearRetry(UUID taskId);
    TaskAttempt startRetry(UUID taskId,Instant now);
    void failTask(UUID taskId,Instant now);
    void cancelTask(UUID taskId, String terminalState, String reason, Instant now);
    void reconcileRunState(UUID runId, Instant now);
}
