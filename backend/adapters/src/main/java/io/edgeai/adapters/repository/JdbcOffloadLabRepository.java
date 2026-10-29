package io.edgeai.adapters.repository;

import io.edgeai.domain.execution.OffloadLabSession;
import io.edgeai.domain.repository.OffloadLabRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;

public final class JdbcOffloadLabRepository implements OffloadLabRepository {
    private final JdbcTemplate jdbc;
    public JdbcOffloadLabRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Instant instant(ResultSet r,String name) throws SQLException {var value=r.getTimestamp(name);return value==null?null:value.toInstant();}
    private static Integer integer(ResultSet r,String name) throws SQLException {int value=r.getInt(name);return r.wasNull()?null:value;}
    private static final RowMapper<OffloadLabSession> ROW=(r,n)->new OffloadLabSession(r.getObject("id",UUID.class),r.getObject("idempotency_key",UUID.class),
        r.getString("request_digest"),r.getObject("run_id",UUID.class),r.getObject("edge_node_id",UUID.class),r.getString("edge_node_name"),r.getString("state"),
        r.getInt("load_generation"),r.getString("load_desired"),r.getObject("load_key",UUID.class),r.getString("load_digest"),integer(r,"load_workers"),integer(r,"load_seconds"),
        instant(r,"load_started_at"),instant(r,"load_expires_at"),r.getObject("load_job_uid",UUID.class),instant(r,"created_at"),instant(r,"updated_at"),instant(r,"stopped_at"));
    public boolean create(OffloadLabSession s) {
        // No conflict target: covers both the key and the single-open-session index without aborting the transaction.
        return jdbc.update("""
            INSERT INTO edgeai.offload_lab_session(id,idempotency_key,request_digest,run_id,edge_node_id,edge_node_name,state,created_at,updated_at)
            VALUES (?,?,?,?,?,?,'ACTIVE',?,?) ON CONFLICT DO NOTHING
            """,s.id(),s.idempotencyKey(),s.requestDigest(),s.runId(),s.edgeNodeId(),s.edgeNodeName(),Timestamp.from(s.createdAt()),Timestamp.from(s.updatedAt()))==1;
    }
    public Optional<OffloadLabSession> find(UUID id,boolean lock){return jdbc.query("SELECT * FROM edgeai.offload_lab_session WHERE id=?"+(lock?" FOR UPDATE":""),ROW,id).stream().findFirst();}
    public Optional<OffloadLabSession> byKey(UUID key){return jdbc.query("SELECT * FROM edgeai.offload_lab_session WHERE idempotency_key=?",ROW,key).stream().findFirst();}
    public Optional<OffloadLabSession> latest(){return jdbc.query("SELECT * FROM edgeai.offload_lab_session ORDER BY state='STOPPED',created_at DESC,id LIMIT 1",ROW).stream().findFirst();}
    public List<UUID> unfinished(){return jdbc.query("SELECT id FROM edgeai.offload_lab_session WHERE state<>'STOPPED' OR load_desired='RUNNING' ORDER BY created_at",(r,n)->r.getObject(1,UUID.class));}
    public void startLoad(UUID id,int generation,UUID key,String digest,int workers,int seconds,Instant now) {
        jdbc.update("""
            UPDATE edgeai.offload_lab_session SET load_generation=?,load_desired='RUNNING',load_key=?,load_digest=?,load_workers=?,load_seconds=?,
                load_started_at=?,load_expires_at=?,load_job_uid=NULL,updated_at=? WHERE id=? AND state='ACTIVE'
            """,generation,key,digest,workers,seconds,Timestamp.from(now),Timestamp.from(now.plusSeconds(seconds)),Timestamp.from(now),id);
    }
    public void loadSubmitted(UUID id,int generation,UUID jobUid){jdbc.update("UPDATE edgeai.offload_lab_session SET load_job_uid=? WHERE id=? AND load_generation=? AND load_job_uid IS NULL",jobUid,id,generation);}
    public void stopLoad(UUID id,int generation,Instant now){jdbc.update("UPDATE edgeai.offload_lab_session SET load_desired='STOPPED',updated_at=? WHERE id=? AND load_generation=? AND load_desired='RUNNING'",Timestamp.from(now),id,generation);}
    public void stopping(UUID id,Instant now) {
        jdbc.update("""
            UPDATE edgeai.offload_lab_session SET state='STOPPING',load_desired=CASE WHEN load_desired='RUNNING' THEN 'STOPPED' ELSE load_desired END,updated_at=?
            WHERE id=? AND state='ACTIVE'
            """,Timestamp.from(now),id);
    }
    public void stopped(UUID id,Instant now){jdbc.update("UPDATE edgeai.offload_lab_session SET state='STOPPED',stopped_at=?,updated_at=? WHERE id=? AND state='STOPPING'",Timestamp.from(now),Timestamp.from(now),id);}
}
