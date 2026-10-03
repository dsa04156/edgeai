package io.edgeai.domain.repository;
import io.edgeai.domain.execution.OffloadOperation;
import java.time.Instant;
import java.util.*;
public interface OffloadRepository {
    boolean create(OffloadOperation operation);
    Optional<OffloadOperation> find(UUID id);
    Optional<OffloadOperation> byKey(UUID key);
    List<OffloadOperation> forTask(UUID taskId);
    List<UUID> active(String namespace,int limit);
    List<UUID> automaticCandidates(String namespace,int limit);
    void starting(UUID id,UUID targetAttemptId,Instant deadline,Instant now);
    void memberTarget(UUID id,UUID taskId,UUID targetAttemptId);
    boolean pendingStream(UUID attemptId);
    boolean streamSuccessor(UUID sourceAttemptId,UUID targetAttemptId);
    boolean canStart(UUID attemptId,Instant now);
    void completedByClaim(UUID attemptId,Instant now);
    void failedAttempt(UUID attemptId,Instant now);
    void cancelForTask(UUID taskId,Instant now);
    void terminal(UUID id,String state,String reason,Instant now);
}
