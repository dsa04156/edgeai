package io.edgeai.domain.runtime;
import java.time.Instant;
import java.util.UUID;
public record RuntimeCommand(UUID id, UUID runtimeId, String kind, int attempts, UUID leaseOwner, Instant leaseUntil) {}
