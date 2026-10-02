package io.edgeai.domain.vd;
import java.time.Instant;
import java.util.UUID;
public record VDPoll(UUID runtimeId,UUID sessionId,long sequence,String requestDigest,String command,Instant createdAt,Instant updatedAt) {}
