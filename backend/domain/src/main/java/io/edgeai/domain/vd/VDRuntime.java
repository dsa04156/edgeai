package io.edgeai.domain.vd;

import java.time.Instant;
import java.util.UUID;

/** Internal record. claimNonce and configuration are not public API credentials or DTOs. */
public record VDRuntime(UUID id, UUID vdId, long generation, long requestedRevision, String configurationJson,
                        String configurationDigest, String namespace, String podName, UUID claimNonce,
                        String desiredState, String observedState, UUID podUid, UUID nodeUid, String nodeName,
                        UUID sessionId, Instant leaseUntil, Instant readyAt, Instant startupDeadline, Instant drainDeadline,
                        String failureReason, Instant createdAt, Instant updatedAt) {
    public boolean terminal() { return observedState.equals("TERMINATED"); }
    public boolean ready(Instant now) {
        return desiredState.equals("RUNNING") && observedState.equals("READY") && leaseUntil!=null && now.isBefore(leaseUntil);
    }
}
