package io.edgeai.domain.repository;

import io.edgeai.domain.vd.*;
import java.time.*;
import java.util.*;

/** Lifecycle writes run under the owning VD row lock. Command leasing is independently atomic. */
public interface VDRuntimeRepository {
    Optional<VDRuntime> runtime(UUID id);
    Optional<VDRuntime> current(UUID vdId);
    long nextGeneration(UUID vdId);
    List<VDRuntime> history(UUID vdId,int limit);
    List<VDRuntimeBinding> bindings(UUID vdId,int limit);
    void create(VDRuntime runtime);
    void submitted(UUID runtimeId,UUID podUid,Instant now);
    void attest(UUID runtimeId,UUID nodeUid,String nodeName,UUID sessionId,Instant leaseUntil,boolean ready,Instant now);
    void unready(UUID runtimeId,Instant now);
    void drain(UUID runtimeId,Instant deadline,Instant now);
    void stop(UUID runtimeId,String reason,Instant now);
    void terminated(UUID runtimeId,long revision,Instant now);
    Optional<VDOperation> operation(UUID id);
    Optional<VDOperation> byKey(UUID vdId,String key);
    Optional<VDOperation> pending(UUID vdId);
    void createOperation(VDOperation operation);
    void operationTarget(UUID operationId,UUID runtimeId,Instant now);
    void finishOperation(UUID operationId,String state,String reason,Instant now);
    void command(UUID runtimeId,String kind,Instant now);
    boolean createCommandComplete(UUID runtimeId);
    Optional<VDCommand> leaseCommand(String namespace,UUID owner,Instant now,Duration duration);
    boolean finishCommand(UUID id,UUID owner,Instant now);
    boolean deferCommand(UUID id,UUID owner,Instant availableAt,Instant now);
}
