package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.HawkeyeVideoRepository;
import io.edgeai.domain.scenario.HawkeyeVideoSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcHawkeyeVideoRepository implements HawkeyeVideoRepository {
    private static final RowMapper<HawkeyeVideoSession> SESSION = (rs, n) -> new HawkeyeVideoSession(
        rs.getObject("id", UUID.class), rs.getObject("idempotency_key", UUID.class), rs.getString("request_digest"),
        rs.getObject("workflow_version_id", UUID.class), rs.getString("device_id"), rs.getObject("device_uid", UUID.class),
        rs.getString("camera_source"), rs.getInt("max_windows"), rs.getString("state"), rs.getString("end_reason"), rs.getString("failure_code"),
        rs.getString("failure_message"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
        instant(rs.getTimestamp("ended_at")), rs.getString("observer_epoch"), instant(rs.getTimestamp("observer_started_at")));
    private final JdbcTemplate jdbc;

    public JdbcHawkeyeVideoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    @Override public boolean create(HawkeyeVideoSession s) {
        try {
            jdbc.update("INSERT INTO edgeai.hawkeye_video_session(id,idempotency_key,request_digest,workflow_version_id,device_id,device_uid," +
                "max_windows,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'RUNNING',?,?)", s.id(), s.idempotencyKey(), s.requestDigest(),
                s.workflowVersionId(), s.deviceId(), s.deviceUid(), s.maxWindows(), Timestamp.from(s.createdAt()), Timestamp.from(s.createdAt()));
            return true;
        } catch (DuplicateKeyException e) { return false; }
    }
    @Override public Optional<HawkeyeVideoSession> find(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.hawkeye_video_session WHERE id=?", SESSION, id).stream().findFirst();
    }
    @Override public Optional<HawkeyeVideoSession> lock(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.hawkeye_video_session WHERE id=? FOR UPDATE", SESSION, id).stream().findFirst();
    }
    @Override public Optional<HawkeyeVideoSession> byIdempotencyKey(UUID key) {
        return jdbc.query("SELECT * FROM edgeai.hawkeye_video_session WHERE idempotency_key=?", SESSION, key).stream().findFirst();
    }
    @Override public Optional<UUID> active() { return running().stream().findFirst(); }
    @Override public List<UUID> running() {
        return jdbc.queryForList("SELECT id FROM edgeai.hawkeye_video_session WHERE state='RUNNING'", UUID.class);
    }
    @Override public List<HawkeyeVideoSession> list(int limit, int offset) {
        return jdbc.query("SELECT * FROM edgeai.hawkeye_video_session ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", SESSION, limit, offset);
    }
    @Override public List<HawkeyeVideoSession.Window> windows(UUID sessionId) {
        return jdbc.query("SELECT w.seq,w.run_id,r.state,w.created_at,r.updated_at FROM edgeai.hawkeye_video_window w " +
            "JOIN edgeai.workflow_run r ON r.id=w.run_id WHERE w.session_id=? ORDER BY w.seq DESC",
            (rs, n) -> new HawkeyeVideoSession.Window(rs.getInt(1), rs.getObject(2, UUID.class), rs.getString(3),
                instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5))), sessionId);
    }
    @Override public void addWindow(UUID sessionId, int seq, UUID runId, Instant at) {
        jdbc.update("INSERT INTO edgeai.hawkeye_video_window(session_id,seq,run_id,created_at) VALUES(?,?,?,?)",
            sessionId, seq, runId, Timestamp.from(at));
        jdbc.update("UPDATE edgeai.hawkeye_video_session SET updated_at=? WHERE id=? AND state='RUNNING'", Timestamp.from(at), sessionId);
    }
    @Override public boolean pinCamera(UUID sessionId, String cameraSourceJson) {
        return jdbc.update("UPDATE edgeai.hawkeye_video_session SET camera_source=?::jsonb WHERE id=? AND camera_source IS NULL", cameraSourceJson, sessionId) == 1;
    }
    @Override public boolean windowRun(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM edgeai.hawkeye_video_window WHERE run_id=?)", Boolean.class, runId));
    }
    @Override public boolean recordObserver(UUID sessionId, String epoch, Instant startedAt) {
        return jdbc.update("UPDATE edgeai.hawkeye_video_session SET observer_epoch=?,observer_started_at=? WHERE id=? AND observer_epoch IS NULL AND state='RUNNING'",
            epoch, Timestamp.from(startedAt), sessionId) == 1;
    }
    @Override public boolean end(UUID id, String state, String reason, String failureCode, String failureMessage, Instant at) {
        return jdbc.update("UPDATE edgeai.hawkeye_video_session SET state=?,end_reason=?,failure_code=?,failure_message=?,updated_at=?,ended_at=? " +
            "WHERE id=? AND state='RUNNING'", state, reason, failureCode, failureMessage, Timestamp.from(at), Timestamp.from(at), id) == 1;
    }
}
