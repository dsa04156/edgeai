package io.edgeai.domain.repository;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.TaskResult;
import java.time.*;
import java.util.*;
public interface RuntimeRepository {
    Optional<RuntimeInstance> runtime(UUID id);
    Optional<RuntimeInstance> byAttempt(UUID attemptId);
    List<UUID> readyAttempts(int limit);
    void create(RuntimeInstance runtime);
    void submitted(UUID runtimeId,UUID jobUid,Instant expiresAt,Instant now);
    void claimed(UUID runtimeId,RuntimePod pod,Instant now);
    void stopForRun(UUID runId,Instant now);
    void stop(UUID runtimeId,String reason,Instant now);
    void terminated(UUID runtimeId,Instant now);
    void fail(UUID runtimeId,String reason,Instant now);
    Optional<TaskResult> result(UUID taskId);
    void commit(TaskResult result,Instant now);
    void releaseReadyChildren(UUID runId,Instant now);
    Optional<RuntimeCommand> leaseCommand(String namespace,UUID owner,Instant now,Duration duration);
    boolean finishCommand(UUID id,UUID owner,Instant now);
    boolean deferCommand(UUID id,UUID owner,Instant availableAt,Instant now);
}
