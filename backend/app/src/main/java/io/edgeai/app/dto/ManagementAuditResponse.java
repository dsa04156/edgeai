package io.edgeai.app.dto;
import io.edgeai.domain.audit.ManagementAudit;
import java.time.Instant;
import java.util.UUID;

public record ManagementAuditResponse(UUID id, Instant startedAt, String method, String operation,
        String routeTemplate, UUID targetId, UUID relatedId, String state, ManagementAudit.Outcome outcome) {
    public static ManagementAuditResponse from(ManagementAudit value) {
        return new ManagementAuditResponse(value.id(),value.startedAt(),value.method(),value.operation(),value.routeTemplate(),
            value.targetId(),value.relatedId(),value.state(),value.outcome());
    }
}
