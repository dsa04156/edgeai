package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.HawkeyeSafetyRepository;
import io.edgeai.domain.scenario.HawkeyeSafetyRun;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Leases use the database clock, so API instances with skewed clocks agree on expiry. */
public final class JdbcHawkeyeSafetyRepository implements HawkeyeSafetyRepository {
    private static final String ACTIVE = "('QUEUED','RUNNING')";
    private static final RowMapper<HawkeyeSafetyRun> RUN = (rs, n) -> new HawkeyeSafetyRun(
        rs.getObject("id", UUID.class), rs.getObject("idempotency_key", UUID.class), rs.getString("sample_id"),
        rs.getString("reference_label"), rs.getString("state"), rs.getString("snapshot"),
        rs.getString("raw_output") == null ? null : new HawkeyeSafetyRun.Result(rs.getString("category"), rs.getString("explanation"),
            rs.getString("raw_output"), rs.getString("done_reason"), rs.getString("input_sha256"), rs.getString("processor"),
            rs.getLong("size_bytes"), rs.getLong("size_vram_bytes"), rs.getDouble("inference_ms"), rs.getDouble("load_ms"),
            rs.getDouble("round_trip_ms"), (Integer) rs.getObject("prompt_eval_count"), (Integer) rs.getObject("eval_count")),
        rs.getString("failure_code"), rs.getString("failure_message"), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")));
    private final JdbcTemplate jdbc;

    public JdbcHawkeyeSafetyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static double seconds(Duration lease) { return lease.toMillis() / 1000.0; }

    @Override public boolean create(UUID id, UUID key, String sampleId, String referenceLabel, UUID owner, Duration lease) {
        try {
            jdbc.update("INSERT INTO edgeai.hawkeye_safety_run(id,idempotency_key,sample_id,reference_label,state,owner_instance,lease_expires_at) " +
                "VALUES(?,?,?,?,'QUEUED',?,now()+make_interval(secs=>?))", id, key, sampleId, referenceLabel, owner, seconds(lease));
            return true;
        } catch (DuplicateKeyException e) { return false; }
    }
    @Override public Optional<HawkeyeSafetyRun> find(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.hawkeye_safety_run WHERE id=?", RUN, id).stream().findFirst();
    }
    @Override public Optional<HawkeyeSafetyRun> byIdempotencyKey(UUID key) {
        return jdbc.query("SELECT * FROM edgeai.hawkeye_safety_run WHERE idempotency_key=?", RUN, key).stream().findFirst();
    }
    @Override public Optional<UUID> active() {
        return jdbc.queryForList("SELECT id FROM edgeai.hawkeye_safety_run WHERE state IN " + ACTIVE, UUID.class).stream().findFirst();
    }
    @Override public List<HawkeyeSafetyRun> list(int limit, int offset) {
        return jdbc.query("SELECT * FROM edgeai.hawkeye_safety_run ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", RUN, limit, offset);
    }
    @Override public boolean begin(UUID id, UUID owner, String snapshotJson) {
        return jdbc.update("UPDATE edgeai.hawkeye_safety_run SET state='RUNNING',snapshot=?::jsonb,started_at=now() " +
            "WHERE id=? AND owner_instance=? AND state='QUEUED'", snapshotJson, id, owner) == 1;
    }
    @Override public boolean finish(UUID id, UUID owner, HawkeyeSafetyRun.Result r, String failureCode, String failureMessage) {
        return jdbc.update("UPDATE edgeai.hawkeye_safety_run SET state=?,category=?,explanation=?,raw_output=?,done_reason=?,input_sha256=?," +
            "processor=?,size_bytes=?,size_vram_bytes=?,inference_ms=?,load_ms=?,round_trip_ms=?,prompt_eval_count=?,eval_count=?," +
            "failure_code=?,failure_message=?,finished_at=now() WHERE id=? AND owner_instance=? AND state='RUNNING'",
            failureCode == null ? "SUCCEEDED" : "FAILED", r.category(), r.explanation(), r.rawOutput(), r.doneReason(), r.inputSha256(),
            r.processor(), r.sizeBytes(), r.sizeVramBytes(), r.inferenceMs(), r.loadMs(), r.roundTripMs(), r.promptEvalCount(),
            r.evalCount(), failureCode, failureMessage, id, owner) == 1;
    }
    @Override public boolean fail(UUID id, UUID owner, String code, String message) {
        return jdbc.update("UPDATE edgeai.hawkeye_safety_run SET state='FAILED',failure_code=?,failure_message=?,finished_at=now() " +
            "WHERE id=? AND owner_instance=? AND state IN " + ACTIVE, code, message, id, owner) == 1;
    }
    @Override public void renew(UUID owner, Collection<UUID> ids, Duration lease) {
        for (var id : ids)
            jdbc.update("UPDATE edgeai.hawkeye_safety_run SET lease_expires_at=now()+make_interval(secs=>?) " +
                "WHERE id=? AND owner_instance=? AND state IN " + ACTIVE, seconds(lease), id, owner);
    }
    @Override public int interruptOwned(UUID owner, String code) {
        return jdbc.update("UPDATE edgeai.hawkeye_safety_run SET state='INTERRUPTED',failure_code=?,finished_at=now() " +
            "WHERE owner_instance=? AND state IN " + ACTIVE, code, owner);
    }
    @Override public int interruptExpired(String code) {
        return jdbc.update("UPDATE edgeai.hawkeye_safety_run SET state='INTERRUPTED',failure_code=?,finished_at=now() " +
            "WHERE state IN " + ACTIVE + " AND lease_expires_at<now()", code);
    }
}
