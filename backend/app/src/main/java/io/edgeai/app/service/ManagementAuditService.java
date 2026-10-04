package io.edgeai.app.service;

import io.edgeai.domain.audit.ManagementAudit;
import io.edgeai.domain.repository.ManagementAuditRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagementAuditService {
    private final ManagementAuditRepository repository;
    public ManagementAuditService(ManagementAuditRepository repository) { this.repository = repository; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void begin(ManagementAudit.Request request) { repository.begin(request); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(UUID id, int status, String disposition, ManagementAudit.Actor actor) { repository.finish(id,status,disposition,actor); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void deniedRead(ManagementAudit.Request request, int status, ManagementAudit.Actor actor) {
        repository.begin(request); repository.finish(request.id(),status,"HTTP_COMPLETED",actor);
    }
    public Optional<ManagementAudit> find(UUID id) { return repository.find(id); }
    public List<ManagementAudit> list(int limit, int offset) {
        if (limit<1 || limit>100 || offset<0 || offset>1_000_000) throw new IllegalArgumentException("Invalid audit pagination");
        return repository.list(limit+1,offset);
    }
}
