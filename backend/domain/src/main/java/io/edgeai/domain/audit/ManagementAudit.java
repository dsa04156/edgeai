package io.edgeai.domain.audit;

import java.time.Instant;
import java.util.UUID;

/** The observed HTTP boundary, not the eventual result of an asynchronous domain operation. */
public record ManagementAudit(UUID id, Instant startedAt, String method, String operation,
        String routeTemplate, UUID targetId, UUID relatedId, Outcome outcome) {
    public String state() { return outcome == null ? "OUTCOME_UNKNOWN" : "OUTCOME_RECORDED"; }
    public record Request(UUID id, String method, String operation, String routeTemplate, UUID targetId, UUID relatedId) {}
    public record Actor(String type, String subject, String subjectFormat) {}
    public record Outcome(Instant completedAt, int httpStatus, String disposition, Actor actor) {}
}
