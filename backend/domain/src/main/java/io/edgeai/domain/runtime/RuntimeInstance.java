package io.edgeai.domain.runtime;
import java.time.Instant;
import java.util.UUID;
/** Internal persistence identity. Never expose the claim nonce through public API DTOs. */
public record RuntimeInstance(UUID id, UUID attemptId, UUID taskId, UUID runId, long epoch,
        String namespace, String jobName, UUID claimNonce, String desiredState, String observedState,
        UUID jobUid, UUID producerPodUid, UUID nodeUid, String nodeName, Instant expiresAt,
        String failureReason, Instant createdAt, Instant updatedAt) {}
