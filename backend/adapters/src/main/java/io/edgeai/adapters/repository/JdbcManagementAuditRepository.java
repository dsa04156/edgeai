package io.edgeai.adapters.repository;

import io.edgeai.domain.audit.ManagementAudit;
import io.edgeai.domain.repository.ManagementAuditRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcManagementAuditRepository implements ManagementAuditRepository {
    private final JdbcTemplate jdbc;
    private static final String SELECT = "SELECT r.*, o.completed_at, o.http_status, o.disposition, o.actor_type, o.actor_subject, o.subject_format " +
        "FROM edgeai.management_audit_request r LEFT JOIN edgeai.management_audit_outcome o ON o.request_id=r.id";
    private static final RowMapper<ManagementAudit> ROW = (rs, n) -> {
        var completed = rs.getTimestamp("completed_at");
        var outcome = completed == null ? null : new ManagementAudit.Outcome(completed.toInstant(), rs.getInt("http_status"),
            rs.getString("disposition"), new ManagementAudit.Actor(rs.getString("actor_type"), rs.getString("actor_subject"), rs.getString("subject_format")));
        return new ManagementAudit(rs.getObject("id", UUID.class), rs.getTimestamp("started_at").toInstant(),
            rs.getString("method"), rs.getString("operation"), rs.getString("route_template"),
            rs.getObject("target_id", UUID.class), rs.getObject("related_id", UUID.class), outcome);
    };
    public JdbcManagementAuditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void begin(ManagementAudit.Request r) {
        jdbc.update("INSERT INTO edgeai.management_audit_request(id,method,operation,route_template,target_id,related_id) VALUES(?,?,?,?,?,?)",
            r.id(), r.method(), r.operation(), r.routeTemplate(), r.targetId(), r.relatedId());
    }
    @Override public void finish(UUID id, int status, String disposition, ManagementAudit.Actor actor) {
        jdbc.update("INSERT INTO edgeai.management_audit_outcome(request_id,http_status,disposition,actor_type,actor_subject,subject_format) VALUES(?,?,?,?,?,?)",
            id, status, disposition, actor.type(), actor.subject(), actor.subjectFormat());
    }
    @Override public Optional<ManagementAudit> find(UUID id) { return jdbc.query(SELECT+" WHERE r.id=?", ROW, id).stream().findFirst(); }
    @Override public List<ManagementAudit> list(int limit, int offset) {
        return jdbc.query(SELECT+" ORDER BY r.started_at DESC,r.id DESC LIMIT ? OFFSET ?", ROW, limit, offset);
    }
}
