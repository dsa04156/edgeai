package io.edgeai.domain.scheduling;
import java.time.Instant;
import java.util.UUID;
/** The original server-selected initial placement of one Run; retries and offloads never rewrite it. */
public record SchedulingDecision(UUID runId, UUID profileId, String profileDigest, String method, String decisionJson, Instant createdAt) {}
