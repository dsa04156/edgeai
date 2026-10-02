package io.edgeai.adapters.repository;
import io.edgeai.domain.execution.OffloadOperation;
import io.edgeai.domain.repository.OffloadRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;

public final class JdbcOffloadRepository implements OffloadRepository {
    private final JdbcTemplate jdbc;
    public JdbcOffloadRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Instant instant(ResultSet r,String name) throws SQLException {var value=r.getTimestamp(name);return value==null?null:value.toInstant();}
    private static final RowMapper<OffloadOperation> ROW=(r,n)->new OffloadOperation(r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getObject("run_id",UUID.class),
        r.getObject("source_attempt_id",UUID.class),r.getObject("target_attempt_id",UUID.class),r.getObject("target_node_id",UUID.class),r.getObject("idempotency_key",UUID.class),
        r.getString("request_digest"),r.getString("namespace"),r.getString("state"),r.getString("failure_reason"),instant(r,"drain_deadline"),r.getInt("start_timeout_seconds"),
        instant(r,"start_deadline"),instant(r,"created_at"),instant(r,"updated_at"),r.getString("trigger"),List.of((String[])r.getArray("excluded_node_names").getArray()),r.getString("decision"),RemoteTargets.read(r));
    public boolean create(OffloadOperation o) {
        return jdbc.update("""
            INSERT INTO edgeai.task_offload(id,task_id,run_id,source_attempt_id,target_node_id,idempotency_key,request_digest,namespace,state,
                drain_deadline,start_timeout_seconds,created_at,updated_at,trigger,excluded_node_names,decision,remote_provider_key,remote_configuration_digest,remote_source_mode)
            VALUES (?,?,?,?,?,?,?,?,'DRAINING',?,?,?,?,?,CAST(? AS text[]),CAST(? AS jsonb),?,?,?) ON CONFLICT(idempotency_key) DO NOTHING
            """,o.id(),o.taskId(),o.runId(),o.sourceAttemptId(),o.targetNodeId(),o.idempotencyKey(),o.requestDigest(),o.namespace(),Timestamp.from(o.drainDeadline()),
            o.startTimeoutSeconds(),Timestamp.from(o.createdAt()),Timestamp.from(o.updatedAt()),o.trigger(),"{"+String.join(",",o.excludedNodeNames())+"}",o.decisionJson(),RemoteTargets.key(o.remoteTarget()),RemoteTargets.digest(o.remoteTarget()),RemoteTargets.source(o.remoteTarget()))==1;
    }
    public Optional<OffloadOperation> find(UUID id){return jdbc.query("SELECT * FROM edgeai.task_offload WHERE id=?",ROW,id).stream().findFirst();}
    public Optional<OffloadOperation> byKey(UUID key){return jdbc.query("SELECT * FROM edgeai.task_offload WHERE idempotency_key=?",ROW,key).stream().findFirst();}
    public List<OffloadOperation> forTask(UUID id){return jdbc.query("SELECT * FROM edgeai.task_offload WHERE task_id=? ORDER BY created_at DESC,id",ROW,id);}
    public List<UUID> active(String namespace,int limit){return jdbc.query("SELECT id FROM edgeai.task_offload WHERE namespace=? AND state IN ('DRAINING','STARTING','CANCELLING') ORDER BY updated_at,id LIMIT ?",(r,n)->r.getObject(1,UUID.class),namespace,limit);}
    public List<UUID> automaticCandidates(String namespace,int limit) {
        return jdbc.query("""
            SELECT t.id FROM edgeai.task t JOIN edgeai.workflow_run w ON w.id=t.run_id
            JOIN edgeai.runtime_instance r ON r.task_id=t.id
            WHERE w.offload_policy IS NOT NULL AND w.state='RUNNING' AND t.state='RUNNING'
              AND r.namespace=? AND r.desired_state='RUNNING' AND r.observed_state='RUNNING'
            ORDER BY t.updated_at,t.id LIMIT ?
            """,(r,n)->r.getObject(1,UUID.class),namespace,limit);
    }
    public void starting(UUID id,UUID attempt,Instant deadline,Instant now){jdbc.update("UPDATE edgeai.task_offload SET target_attempt_id=?,start_deadline=?,state='STARTING',updated_at=? WHERE id=? AND state='DRAINING'",attempt,Timestamp.from(deadline),Timestamp.from(now),id);}
    public boolean canStart(UUID attempt,Instant now){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM edgeai.task_offload WHERE target_attempt_id=? AND (state NOT IN ('STARTING','SUCCEEDED') OR (state='STARTING' AND start_deadline<=?)))",Boolean.class,attempt,Timestamp.from(now)));}
    public void completedByClaim(UUID attempt,Instant now){jdbc.update("UPDATE edgeai.task_offload SET state='SUCCEEDED',updated_at=? WHERE target_attempt_id=? AND state='STARTING'",Timestamp.from(now),attempt);}
    public void failedAttempt(UUID attempt,Instant now){jdbc.update("UPDATE edgeai.task_offload SET state='FAILED',failure_reason='TARGET_FAILED',updated_at=? WHERE target_attempt_id=? AND state='STARTING'",Timestamp.from(now),attempt);}
    public void cancelForTask(UUID task,Instant now){jdbc.update("UPDATE edgeai.task_offload SET state='CANCELLING',updated_at=? WHERE task_id=? AND state IN ('DRAINING','STARTING')",Timestamp.from(now),task);}
    public void terminal(UUID id,String state,String reason,Instant now){jdbc.update("UPDATE edgeai.task_offload SET state=?,failure_reason=?,updated_at=? WHERE id=? AND state IN ('DRAINING','STARTING','CANCELLING')",state,reason,Timestamp.from(now),id);}
}
