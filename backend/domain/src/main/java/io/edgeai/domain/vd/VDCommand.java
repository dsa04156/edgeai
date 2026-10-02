package io.edgeai.domain.vd;

import java.time.Instant;
import java.util.UUID;

public record VDCommand(UUID id, UUID runtimeId, String kind, int attempts, UUID leaseOwner, Instant leaseUntil) {}
