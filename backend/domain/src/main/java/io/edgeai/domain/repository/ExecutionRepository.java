package io.edgeai.domain.repository;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.workflow.*;
import java.time.Instant;
import java.util.*;
public interface ExecutionRepository {
    boolean create(WorkflowRun run);
    default void initialize(WorkflowRun run,List<TaskDefinition> definitions,Set<String> roots){initialize(run,definitions,roots,Map.of());}
    void initialize(WorkflowRun run,List<TaskDefinition> definitions,Set<String> roots,Map<String,io.edgeai.domain.remote.RemoteTarget> taskRemoteTargets);
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
    TaskAttempt startOffload(UUID taskId,UUID nodeId,List<String> excluded,Instant now);
    TaskAttempt startVdOffload(UUID taskId,UUID vdId,Instant now);
    TaskAttempt startRemoteOffload(UUID taskId,io.edgeai.domain.remote.RemoteTarget target,Instant now);
    void failTask(UUID taskId,Instant now);
    void cancelTask(UUID taskId, String terminalState, String reason, Instant now);
    void reconcileRunState(UUID runId, Instant now);
}
