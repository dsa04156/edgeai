package io.edgeai.adapters.repository;

import io.edgeai.domain.aimodel.ModelResourceProfile;
import io.edgeai.domain.aimodel.ProfilingRun;
import io.edgeai.domain.repository.ModelProfilingRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcModelProfilingRepository implements ModelProfilingRepository {
    private final JdbcTemplate jdbc;
    public JdbcModelProfilingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Instant time(ResultSet r,String column) throws SQLException { var t=r.getTimestamp(column);return t==null?null:t.toInstant(); }
    private static Timestamp ts(Instant value) { return value==null?null:Timestamp.from(value); }
    private static final RowMapper<ProfilingRun> RUN=(r,n)->new ProfilingRun(r.getObject("id",UUID.class),r.getObject("model_id",UUID.class),
        r.getObject("idempotency_key",UUID.class),r.getString("request_digest"),r.getString("state"),r.getString("node_name"),r.getString("node_architecture"),
        r.getString("request"),r.getString("job_name"),r.getObject("job_uid",UUID.class),r.getString("pod_name"),r.getObject("pod_uid",UUID.class),
        r.getString("pod_ip"),r.getObject("driver_owner",UUID.class),time(r,"driver_lease_until"),r.getString("message"),time(r,"phase_started_at"),
        time(r,"window_start"),time(r,"window_end"),time(r,"collect_after"),r.getString("progress"),r.getString("failure_code"),r.getString("failure_message"),
        r.getString("result"),time(r,"cancel_requested_at"),time(r,"finished_at"),r.getString("cleanup_state"),time(r,"cleaned_at"),
        time(r,"created_at"),time(r,"updated_at"));
    private static final RowMapper<ModelResourceProfile> PROFILE=(r,n)->new ModelResourceProfile(r.getObject("id",UUID.class),r.getObject("model_id",UUID.class),
        r.getInt("revision"),r.getObject("run_id",UUID.class),r.getObject("idempotency_key",UUID.class),r.getString("request_fingerprint"),
        r.getString("digest"),r.getString("document"),time(r,"created_at"));

    public void lockSlots() { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('edgeai-model-profiling-slots',0))"); }
    public Optional<ProfilingRun> runByIdempotencyKey(UUID key) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_profiling_run WHERE idempotency_key=?",RUN,key).stream().findFirst();
    }
    public List<ProfilingRun> holding() {
        return jdbc.query("SELECT * FROM edgeai.ai_model_profiling_run WHERE cleaned_at IS NULL ORDER BY created_at,id",RUN);
    }
    public void create(ProfilingRun r) {
        jdbc.update("INSERT INTO edgeai.ai_model_profiling_run(id,model_id,idempotency_key,request_digest,state,node_name,node_architecture,request,job_name,message,phase_started_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?)",
            r.id(),r.modelId(),r.idempotencyKey(),r.requestDigest(),r.state(),r.nodeName(),r.nodeArchitecture(),r.requestJson(),r.jobName(),r.message(),
            ts(r.phaseStartedAt()),ts(r.createdAt()),ts(r.updatedAt()));
    }
    public Optional<ProfilingRun> run(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_profiling_run WHERE id=?",RUN,id).stream().findFirst();
    }
    public List<ProfilingRun> runs(UUID modelId,int limit,int offset) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_profiling_run WHERE model_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",RUN,modelId,limit,offset);
    }
    public Optional<ProfilingRun> latestRun(UUID modelId) { return runs(modelId,1,0).stream().findFirst(); }
    public boolean update(ProfilingRun r,String expectedState,Instant expectedUpdatedAt) {
        return jdbc.update("UPDATE edgeai.ai_model_profiling_run SET state=?,job_uid=?,pod_name=?,pod_uid=?,pod_ip=?,driver_owner=?,driver_lease_until=?,message=?,phase_started_at=?,"
                +"window_start=?,window_end=?,collect_after=?,progress=CAST(? AS jsonb),failure_code=?,failure_message=?,result=CAST(? AS jsonb),cancel_requested_at=?,"
                +"finished_at=?,cleanup_state=?,updated_at=? WHERE id=? AND state=? AND updated_at=?",
            r.state(),r.jobUid(),r.podName(),r.podUid(),r.podIp(),r.driverOwner(),ts(r.driverLeaseUntil()),r.message(),ts(r.phaseStartedAt()),
            ts(r.windowStart()),ts(r.windowEnd()),ts(r.collectAfter()),r.progressJson(),r.failureCode(),r.failureMessage(),r.resultJson(),ts(r.cancelRequestedAt()),
            ts(r.finishedAt()),r.cleanupState(),ts(r.updatedAt()),r.id(),expectedState,ts(expectedUpdatedAt))==1;
    }
    public void cleaned(UUID id,Instant at) {
        jdbc.update("UPDATE edgeai.ai_model_profiling_run SET cleanup_state='DONE',cleaned_at=?,updated_at=? WHERE id=? AND finished_at IS NOT NULL AND cleaned_at IS NULL",
            ts(at),ts(at),id);
    }

    public void lockProfiles(UUID modelId,UUID idempotencyKey) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","edgeai-model-profile-key:"+idempotencyKey);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","edgeai-model-profile:"+modelId);
    }
    public Optional<ModelResourceProfile> profileByIdempotencyKey(UUID key) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_resource_profile WHERE idempotency_key=?",PROFILE,key).stream().findFirst();
    }
    public void createProfile(ModelResourceProfile p) {
        jdbc.update("INSERT INTO edgeai.ai_model_resource_profile(id,model_id,revision,run_id,idempotency_key,request_fingerprint,digest,document,created_at) VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?)",
            p.id(),p.modelId(),p.revision(),p.runId(),p.idempotencyKey(),p.requestFingerprint(),p.digest(),p.documentJson(),ts(p.createdAt()));
    }
    public Optional<ModelResourceProfile> profile(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_resource_profile WHERE id=?",PROFILE,id).stream().findFirst();
    }
    public List<ModelResourceProfile> profiles(UUID modelId) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_resource_profile WHERE model_id=? ORDER BY revision DESC",PROFILE,modelId);
    }
    public Optional<ModelResourceProfile> latestProfile(UUID modelId) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_resource_profile WHERE model_id=? ORDER BY revision DESC LIMIT 1",PROFILE,modelId).stream().findFirst();
    }
}
