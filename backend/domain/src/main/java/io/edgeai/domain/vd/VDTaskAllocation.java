package io.edgeai.domain.vd;
import java.time.Instant;
import java.util.UUID;
/** Immutable Task-to-supervisor identity; closure confirms process or Pod termination, never Result success. */
public record VDTaskAllocation(UUID id,UUID runtimeId,UUID vdId,UUID vdRuntimeId,long generation,UUID sessionId,
        UUID podUid,int slot,long assignedSequence,Instant assignedAt,Instant closedAt,String closeReason,Long completionSequence,Integer exitCode) {
    public boolean open(){return closedAt==null;}
}
