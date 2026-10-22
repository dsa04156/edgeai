package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.ImageComparisonRepository;
import io.edgeai.domain.scenario.ImageComparisonRun;
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
import org.springframework.transaction.support.TransactionTemplate;

/** Leases use the database clock, so API instances with skewed clocks agree on expiry. */
public final class JdbcImageComparisonRepository implements ImageComparisonRepository {
    private static final String ACTIVE = "('QUEUED','RUNNING','STOPPING')";
    private static final RowMapper<ImageComparisonRun> RUN = (rs, n) -> new ImageComparisonRun(
        rs.getObject("id", UUID.class), rs.getObject("idempotency_key", UUID.class), rs.getInt("requested_count"),
        rs.getString("state"), rs.getInt("dispatched_count"), rs.getString("snapshot"), rs.getString("failure_code"),
        rs.getString("failure_message"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
        instant(rs.getTimestamp("stop_requested_at")), instant(rs.getTimestamp("finished_at")));
    private static final RowMapper<ImageComparisonRun.Result> RESULT = (rs, n) -> new ImageComparisonRun.Result(
        rs.getInt("image_index"), rs.getString("target"), rs.getString("status"), rs.getString("top_k"),
        rs.getString("input_sha256"), (Double) rs.getObject("inference_ms"), rs.getDouble("round_trip_ms"), rs.getString("failure_code"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcImageComparisonRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static double seconds(Duration lease) { return lease.toMillis() / 1000.0; }

    @Override public boolean create(UUID id, UUID key, int count, UUID owner, Duration lease) {
        try {
            jdbc.update("INSERT INTO edgeai.image_comparison_run(id,idempotency_key,requested_count,state,owner_instance,lease_expires_at) " +
                "VALUES(?,?,?,'QUEUED',?,now()+make_interval(secs=>?))", id, key, count, owner, seconds(lease));
            return true;
        } catch (DuplicateKeyException e) { return false; }
    }
    @Override public Optional<ImageComparisonRun> find(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.image_comparison_run WHERE id=?", RUN, id).stream().findFirst();
    }
    @Override public Optional<ImageComparisonRun> byIdempotencyKey(UUID key) {
        return jdbc.query("SELECT * FROM edgeai.image_comparison_run WHERE idempotency_key=?", RUN, key).stream().findFirst();
    }
    @Override public Optional<UUID> active() {
        return jdbc.queryForList("SELECT id FROM edgeai.image_comparison_run WHERE state IN " + ACTIVE, UUID.class).stream().findFirst();
    }
    @Override public List<ImageComparisonRun> list(int limit, int offset) {
        return jdbc.query("SELECT * FROM edgeai.image_comparison_run ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", RUN, limit, offset);
    }
    @Override public boolean begin(UUID id, UUID owner, String snapshotJson) {
        return jdbc.update("UPDATE edgeai.image_comparison_run SET state='RUNNING',snapshot=?::jsonb,started_at=now() " +
            "WHERE id=? AND owner_instance=? AND state='QUEUED'", snapshotJson, id, owner) == 1;
    }
    @Override public boolean claim(UUID id, UUID owner, int index, Duration lease) {
        return jdbc.update("UPDATE edgeai.image_comparison_run SET dispatched_count=dispatched_count+1,lease_expires_at=now()+make_interval(secs=>?) " +
            "WHERE id=? AND owner_instance=? AND state='RUNNING' AND dispatched_count=? AND dispatched_count<requested_count",
            seconds(lease), id, owner, index) == 1;
    }
    @Override public boolean record(UUID id, UUID owner, List<ImageComparisonRun.Result> results) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // Row lock: a concurrent lease expiry cannot interleave with the insert.
            var held = jdbc.queryForList("SELECT id FROM edgeai.image_comparison_run WHERE id=? AND owner_instance=? " +
                "AND state IN ('RUNNING','STOPPING') FOR UPDATE", UUID.class, id, owner);
            if (held.isEmpty()) return false;
            for (var r : results)
                jdbc.update("INSERT INTO edgeai.image_comparison_result(run_id,image_index,target,status,top_k,input_sha256,inference_ms,round_trip_ms,failure_code) " +
                    "VALUES(?,?,?,?,?::jsonb,?,?,?,?)", id, r.imageIndex(), r.target(), r.status(), r.topKJson(), r.inputSha256(),
                    r.inferenceMs(), r.roundTripMs(), r.failureCode());
            return true;
        }));
    }
    @Override public boolean complete(UUID id, UUID owner) {
        return jdbc.update("UPDATE edgeai.image_comparison_run SET state=CASE WHEN state='STOPPING' THEN 'STOPPED' ELSE 'SUCCEEDED' END," +
            "finished_at=now() WHERE id=? AND owner_instance=? AND state IN ('RUNNING','STOPPING')", id, owner) == 1;
    }
    @Override public boolean fail(UUID id, UUID owner, String code, String message) {
        // An accepted Stop wins: the run ends STOPPED and still records why the image in flight failed.
        return jdbc.update("UPDATE edgeai.image_comparison_run SET state=CASE WHEN state='STOPPING' THEN 'STOPPED' ELSE 'FAILED' END," +
            "failure_code=?,failure_message=?,finished_at=now() WHERE id=? AND owner_instance=? AND state IN " + ACTIVE, code, message, id, owner) == 1;
    }
    @Override public void stop(UUID id) {
        jdbc.update("UPDATE edgeai.image_comparison_run SET state=CASE WHEN state='QUEUED' THEN 'STOPPED' ELSE 'STOPPING' END," +
            "stop_requested_at=now(),finished_at=CASE WHEN state='QUEUED' THEN now() END WHERE id=? AND state IN ('QUEUED','RUNNING')", id);
    }
    @Override public void renew(UUID owner, Collection<UUID> ids, Duration lease) {
        for (var id : ids)
            jdbc.update("UPDATE edgeai.image_comparison_run SET lease_expires_at=now()+make_interval(secs=>?) " +
                "WHERE id=? AND owner_instance=? AND state IN " + ACTIVE, seconds(lease), id, owner);
    }
    @Override public int interruptOwned(UUID owner, String code) {
        return jdbc.update("UPDATE edgeai.image_comparison_run SET state='INTERRUPTED',failure_code=?,finished_at=now() " +
            "WHERE owner_instance=? AND state IN " + ACTIVE, code, owner);
    }
    @Override public int interruptExpired(String code) {
        return jdbc.update("UPDATE edgeai.image_comparison_run SET state='INTERRUPTED',failure_code=?,finished_at=now() " +
            "WHERE state IN " + ACTIVE + " AND lease_expires_at<now()", code);
    }
    @Override public List<ImageComparisonRun.Result> results(UUID id, int fromIndex, int limit) {
        return jdbc.query("SELECT * FROM edgeai.image_comparison_result WHERE run_id=? AND image_index>=? AND image_index<? " +
            "ORDER BY image_index,target", RESULT, id, fromIndex, fromIndex + limit);
    }
}
