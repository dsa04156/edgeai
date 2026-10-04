package io.edgeai.domain.repository;

import io.edgeai.domain.audit.ManagementAudit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManagementAuditRepository {
    void begin(ManagementAudit.Request request);
    void finish(UUID id, int status, String disposition, ManagementAudit.Actor actor);
    Optional<ManagementAudit> find(UUID id);
    List<ManagementAudit> list(int limit, int offset);
}
