package io.edgeai.domain.remote;
import java.time.Instant;
import java.util.UUID;
public record RemoteAllocation(UUID id,UUID runtimeId,RemoteTarget target,String workJson,String requestDigest,
    long providerRevision,String providerState,String observationJson,Instant observedAt,Instant createdAt) {}
