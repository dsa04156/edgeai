package io.edgeai.domain.runtime;
import java.time.Instant;
import java.util.UUID;
/** Internal persistence identity. Never expose the claim nonce through public API DTOs. */
public record RuntimeInstance(UUID id, UUID attemptId, UUID taskId, UUID runId, long epoch,
        String namespace, String jobName, UUID claimNonce, String desiredState, String observedState,
        UUID jobUid, UUID producerPodUid, UUID nodeUid, String nodeName, Instant expiresAt,
        String failureReason, Instant createdAt, Instant updatedAt,UUID remoteAllocationId,UUID vdId) {
    public RuntimeInstance(UUID id,UUID attemptId,UUID taskId,UUID runId,long epoch,String namespace,String jobName,
            UUID claimNonce,String desiredState,String observedState,UUID jobUid,UUID producerPodUid,UUID nodeUid,
            String nodeName,Instant expiresAt,String failureReason,Instant createdAt,Instant updatedAt,UUID remoteAllocationId) {
        this(id,attemptId,taskId,runId,epoch,namespace,jobName,claimNonce,desiredState,observedState,jobUid,producerPodUid,nodeUid,nodeName,expiresAt,failureReason,createdAt,updatedAt,remoteAllocationId,null);
    }
    public RuntimeInstance(UUID id,UUID attemptId,UUID taskId,UUID runId,long epoch,String namespace,String jobName,
            UUID claimNonce,String desiredState,String observedState,UUID jobUid,UUID producerPodUid,UUID nodeUid,
            String nodeName,Instant expiresAt,String failureReason,Instant createdAt,Instant updatedAt) {
        this(id,attemptId,taskId,runId,epoch,namespace,jobName,claimNonce,desiredState,observedState,jobUid,producerPodUid,nodeUid,nodeName,expiresAt,failureReason,createdAt,updatedAt,null);
    }
    public boolean remote(){return remoteAllocationId!=null;}
    public boolean vd(){return vdId!=null;}
}
