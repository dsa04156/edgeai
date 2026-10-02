package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.VDTaskRepository;
import io.edgeai.domain.vd.VDTaskAllocation;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;

public final class JdbcVDTaskRepository implements VDTaskRepository {
    private final JdbcTemplate jdbc;
    public JdbcVDTaskRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Instant instant(ResultSet r,String key)throws SQLException{var t=r.getTimestamp(key);return t==null?null:t.toInstant();}
    private static final RowMapper<VDTaskAllocation> MAP=(r,n)->new VDTaskAllocation(r.getObject("id",UUID.class),r.getObject("runtime_id",UUID.class),r.getObject("vd_id",UUID.class),
        r.getObject("vd_runtime_id",UUID.class),r.getLong("generation"),r.getObject("session_id",UUID.class),r.getObject("pod_uid",UUID.class),r.getInt("slot"),r.getLong("assigned_sequence"),
        instant(r,"assigned_at"),instant(r,"closed_at"),r.getString("close_reason"),r.getObject("completion_sequence",Long.class),r.getObject("exit_code",Integer.class));
    public Optional<VDTaskAllocation> byRuntime(UUID id){return jdbc.query("SELECT * FROM edgeai.vd_task_allocation WHERE runtime_id=?",MAP,id).stream().findFirst();}
    public List<VDTaskAllocation> open(UUID id){return jdbc.query("SELECT * FROM edgeai.vd_task_allocation WHERE vd_runtime_id=? AND closed_at IS NULL ORDER BY slot",MAP,id);}
    public List<VDTaskAllocation> assigned(UUID id,long sequence){return jdbc.query("SELECT * FROM edgeai.vd_task_allocation WHERE vd_runtime_id=? AND assigned_sequence=? ORDER BY slot",MAP,id,sequence);}
    public List<UUID> pending(UUID vdId,int limit){return jdbc.query("""
        SELECT r.id FROM edgeai.runtime_instance r WHERE r.vd_id=? AND r.desired_state='RUNNING' AND r.observed_state='PENDING'
        AND NOT EXISTS(SELECT 1 FROM edgeai.vd_task_allocation a WHERE a.runtime_id=r.id) ORDER BY r.created_at,r.id LIMIT ?
        """,(r,n)->r.getObject(1,UUID.class),vdId,limit);}
    public void create(VDTaskAllocation a,Instant expiresAt){
        jdbc.update("""
            INSERT INTO edgeai.vd_task_allocation(id,runtime_id,vd_id,vd_runtime_id,generation,session_id,pod_uid,slot,assigned_sequence,assigned_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,a.id(),a.runtimeId(),a.vdId(),a.vdRuntimeId(),a.generation(),a.sessionId(),a.podUid(),a.slot(),a.assignedSequence(),Timestamp.from(a.assignedAt()));
        jdbc.update("UPDATE edgeai.runtime_instance SET observed_state='SUBMITTED',expires_at=?,updated_at=? WHERE id=? AND vd_id=?",
            Timestamp.from(expiresAt),Timestamp.from(a.assignedAt()),a.runtimeId(),a.vdId());
    }
    public void close(UUID id,String reason,Long sequence,Integer exitCode,Instant now){
        jdbc.update("UPDATE edgeai.vd_task_allocation SET closed_at=?,close_reason=?,completion_sequence=?,exit_code=? WHERE runtime_id=?",
            Timestamp.from(now),reason,sequence,exitCode,id);
    }
    public void claimed(UUID id,UUID podUid,UUID nodeUid,String nodeName,Instant now){
        var changed=jdbc.query("""
            UPDATE edgeai.runtime_instance r SET producer_pod_uid=?,node_uid=?,node_name=?,observed_state='RUNNING',updated_at=?
            FROM edgeai.vd_task_allocation a,edgeai.vd_runtime vr
            WHERE r.id=? AND a.runtime_id=r.id AND a.vd_runtime_id=vr.id AND a.closed_at IS NULL
              AND a.pod_uid=? AND vr.node_uid=? AND vr.node_name=? AND vr.desired_state IN ('RUNNING','DRAINING') AND vr.lease_until>?
              AND r.desired_state='RUNNING' AND r.observed_state IN ('SUBMITTED','RUNNING') AND r.expires_at>?
              AND (r.producer_pod_uid IS NULL OR (r.producer_pod_uid=? AND r.node_uid=? AND r.node_name=?))
              AND EXISTS(SELECT 1 FROM edgeai.task_attempt attempt JOIN edgeai.task t ON t.id=attempt.task_id
                  JOIN edgeai.workflow_run w ON w.id=t.run_id WHERE attempt.id=r.attempt_id
                    AND attempt.state IN ('DISPATCHING','RUNNING') AND t.state='RUNNING' AND w.state='RUNNING')
            RETURNING r.attempt_id
            """,(r,n)->r.getObject(1,UUID.class),podUid,nodeUid,nodeName,Timestamp.from(now),id,podUid,nodeUid,nodeName,Timestamp.from(now),Timestamp.from(now),podUid,nodeUid,nodeName);
        if(changed.size()!=1)throw new IllegalStateException("VD allocation producer is fenced");
        jdbc.update("UPDATE edgeai.task_attempt SET state='RUNNING',updated_at=? WHERE id=?",Timestamp.from(now),changed.getFirst());
    }
}
