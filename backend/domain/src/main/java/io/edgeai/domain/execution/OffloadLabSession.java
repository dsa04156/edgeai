package io.edgeai.domain.execution;

import java.time.Instant;
import java.util.UUID;

/** One offload test console session: a normal Run plus the desired state of its own bounded CPU load Job generation. */
public record OffloadLabSession(UUID id,UUID idempotencyKey,String requestDigest,UUID runId,UUID edgeNodeId,String edgeNodeName,String state,
        int loadGeneration,String loadDesired,UUID loadKey,String loadDigest,Integer loadWorkers,Integer loadSeconds,Instant loadStartedAt,Instant loadExpiresAt,
        UUID loadJobUid,Instant createdAt,Instant updatedAt,Instant stoppedAt) {
    public boolean loadRunning(Instant now){return loadDesired.equals("RUNNING") && now.isBefore(loadExpiresAt);}
}
