package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.ResourceProfileRepository;
import io.edgeai.domain.resourceprofile.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcResourceProfileRepository implements ResourceProfileRepository {
    private final JdbcTemplate jdbc;
    public JdbcResourceProfileRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<ResourceAnalysis> ANALYSIS=(r,n)->new ResourceAnalysis(r.getObject("id",UUID.class),
        r.getObject("service_profile_version_id",UUID.class),r.getString("document"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<ResourceProfile> PROFILE=(r,n)->new ResourceProfile(r.getObject("id",UUID.class),
        r.getObject("service_profile_version_id",UUID.class),r.getInt("revision"),r.getObject("idempotency_key",UUID.class),
        r.getObject("analysis_id",UUID.class),r.getString("measurement_status"),r.getString("digest"),r.getString("document"),
        r.getTimestamp("created_at").toInstant());
    public void createAnalysis(ResourceAnalysis a) {
        jdbc.update("INSERT INTO edgeai.resource_profile_analysis(id,service_profile_version_id,document,created_at) VALUES (?,?,CAST(? AS jsonb),?)",
            a.id(),a.serviceProfileVersionId(),a.documentJson(),Timestamp.from(a.createdAt()));
    }
    public Optional<ResourceAnalysis> analysis(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.resource_profile_analysis WHERE id=?",ANALYSIS,id).stream().findFirst();
    }
    public void lockService(UUID serviceProfileVersionId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","edgeai-resource-profile:"+serviceProfileVersionId);
    }
    public Optional<ResourceProfile> byIdempotencyKey(UUID key) {
        return jdbc.query("SELECT * FROM edgeai.resource_profile WHERE idempotency_key=?",PROFILE,key).stream().findFirst();
    }
    public int nextRevision(UUID serviceProfileVersionId) {
        Integer max=jdbc.queryForObject("SELECT max(revision) FROM edgeai.resource_profile WHERE service_profile_version_id=?",Integer.class,serviceProfileVersionId);
        return max==null?1:max+1;
    }
    public void create(ResourceProfile p) {
        jdbc.update("INSERT INTO edgeai.resource_profile(id,service_profile_version_id,revision,idempotency_key,analysis_id,measurement_status,digest,document,created_at) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?)",
            p.id(),p.serviceProfileVersionId(),p.revision(),p.idempotencyKey(),p.analysisId(),p.evidenceStatus(),p.digest(),p.documentJson(),Timestamp.from(p.createdAt()));
    }
    public Optional<ResourceProfile> profile(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.resource_profile WHERE id=?",PROFILE,id).stream().findFirst();
    }
    public Optional<ResourceProfile> revision(UUID serviceProfileVersionId,int revision) {
        return jdbc.query("SELECT * FROM edgeai.resource_profile WHERE service_profile_version_id=? AND revision=?",PROFILE,serviceProfileVersionId,revision).stream().findFirst();
    }
    public List<ResourceProfile> list(UUID serviceProfileVersionId,int limit,int offset) {
        return serviceProfileVersionId==null
            ? jdbc.query("SELECT * FROM edgeai.resource_profile ORDER BY created_at DESC,id LIMIT ? OFFSET ?",PROFILE,limit,offset)
            : jdbc.query("SELECT * FROM edgeai.resource_profile WHERE service_profile_version_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",PROFILE,serviceProfileVersionId,limit,offset);
    }
}
