package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.SchedulingRepository;
import io.edgeai.domain.scheduling.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcSchedulingRepository implements SchedulingRepository {
    private final JdbcTemplate jdbc;
    public JdbcSchedulingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<SchedulingProfile> PROFILE=(r,n)->new SchedulingProfile(r.getObject("id",UUID.class),r.getObject("workflow_version_id",UUID.class),
        r.getObject("idempotency_key",UUID.class),r.getString("digest"),r.getTimestamp("measured_at").toInstant(),r.getTimestamp("valid_until").toInstant(),
        r.getString("document"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<SchedulingDecision> DECISION=(r,n)->new SchedulingDecision(r.getObject("run_id",UUID.class),r.getObject("profile_id",UUID.class),
        r.getString("profile_digest"),r.getString("method"),r.getString("decision"),r.getTimestamp("created_at").toInstant());
    public void lockProfileKey(UUID key) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","edgeai-scheduling-profile:"+key);
    }
    public Optional<SchedulingProfile> profileByIdempotencyKey(UUID key) {
        return jdbc.query("SELECT * FROM edgeai.scheduling_profile WHERE idempotency_key=?",PROFILE,key).stream().findFirst();
    }
    public void create(SchedulingProfile p) {
        jdbc.update("INSERT INTO edgeai.scheduling_profile(id,workflow_version_id,idempotency_key,digest,measured_at,valid_until,document,created_at) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?)",
            p.id(),p.workflowVersionId(),p.idempotencyKey(),p.digest(),Timestamp.from(p.measuredAt()),Timestamp.from(p.validUntil()),p.documentJson(),Timestamp.from(p.createdAt()));
    }
    public Optional<SchedulingProfile> profile(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.scheduling_profile WHERE id=?",PROFILE,id).stream().findFirst();
    }
    public void record(SchedulingDecision d) {
        jdbc.update("INSERT INTO edgeai.workflow_run_scheduling(run_id,profile_id,profile_digest,method,decision,created_at) VALUES (?,?,?,?,CAST(? AS jsonb),?)",
            d.runId(),d.profileId(),d.profileDigest(),d.method(),d.decisionJson(),Timestamp.from(d.createdAt()));
    }
    public Optional<SchedulingDecision> decision(UUID runId) {
        return jdbc.query("SELECT * FROM edgeai.workflow_run_scheduling WHERE run_id=?",DECISION,runId).stream().findFirst();
    }
}
