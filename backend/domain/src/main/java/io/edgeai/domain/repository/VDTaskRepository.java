package io.edgeai.domain.repository;
import io.edgeai.domain.vd.VDTaskAllocation;
import java.time.Instant;
import java.util.*;
/** Mutations require the VD row lock, followed by the parent Run lock. No Kubernetes or storage I/O. */
public interface VDTaskRepository {
    Optional<VDTaskAllocation> byRuntime(UUID runtimeId);
    List<VDTaskAllocation> open(UUID vdRuntimeId);
    List<VDTaskAllocation> assigned(UUID vdRuntimeId,long sequence);
    List<UUID> pending(UUID vdId,int limit);
    void create(VDTaskAllocation allocation,Instant expiresAt);
    void claimed(UUID runtimeId,UUID podUid,UUID nodeUid,String nodeName,Instant now);
    void close(UUID runtimeId,String reason,Long completionSequence,Integer exitCode,Instant now);
}
