package io.edgeai.app.dto;

import io.edgeai.domain.vd.VDRuntime;
import java.time.Instant;
import java.util.UUID;

/** Public observation only: credentials, session, claim nonce and internal configuration stay private. */
public record VDRuntimeResponse(UUID id,UUID vdId,long generation,long requestedRevision,String desiredState,
        String observedState,boolean ready,UUID podUid,UUID nodeUid,String nodeName,Instant leaseUntil,Instant readyAt,
        Instant startupDeadline,Instant drainDeadline,String failureReason,Instant createdAt,Instant updatedAt) {
    public static VDRuntimeResponse from(VDRuntime value,Instant now) {
        if(value==null)return null;
        return new VDRuntimeResponse(value.id(),value.vdId(),value.generation(),value.requestedRevision(),value.desiredState(),value.observedState(),
            value.ready(now),value.podUid(),value.nodeUid(),value.nodeName(),value.leaseUntil(),value.readyAt(),value.startupDeadline(),
            value.drainDeadline(),value.failureReason(),value.createdAt(),value.updatedAt());
    }
}
