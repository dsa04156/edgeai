package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.VDRuntimeRepository;
import io.edgeai.domain.vd.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcVDRuntimeRepository implements VDRuntimeRepository {
    private final JdbcTemplate jdbc;
    public JdbcVDRuntimeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Timestamp ts(Instant value) { return value==null?null:Timestamp.from(value); }
    private static Instant time(ResultSet row,String key) throws SQLException { var value=row.getTimestamp(key);return value==null?null:value.toInstant(); }
    private static UUID id(ResultSet row,String key) throws SQLException { return row.getObject(key,UUID.class); }
    private static final RowMapper<VDRuntime> RUNTIME=(r,n)->new VDRuntime(id(r,"id"),id(r,"vd_id"),r.getLong("generation"),r.getLong("requested_revision"),
        r.getString("configuration"),r.getString("configuration_digest"),r.getString("namespace"),r.getString("pod_name"),id(r,"claim_nonce"),
        r.getString("desired_state"),r.getString("observed_state"),id(r,"pod_uid"),id(r,"node_uid"),r.getString("node_name"),id(r,"session_id"),
        time(r,"lease_until"),time(r,"ready_at"),time(r,"startup_deadline"),time(r,"drain_deadline"),r.getString("failure_reason"),time(r,"created_at"),time(r,"updated_at"));
    private static final RowMapper<VDOperation> OPERATION=(r,n)->new VDOperation(id(r,"id"),id(r,"vd_id"),r.getString("request_key"),r.getString("request_digest"),
        r.getString("kind"),r.getLong("requested_revision"),r.getString("configuration"),r.getString("configuration_digest"),id(r,"source_runtime_id"),id(r,"target_runtime_id"),
        r.getString("state"),r.getString("reason"),time(r,"created_at"),time(r,"updated_at"),time(r,"finished_at"));
    private static final RowMapper<VDCommand> COMMAND=(r,n)->new VDCommand(id(r,"id"),id(r,"runtime_id"),r.getString("kind"),r.getInt("attempts"),id(r,"lease_owner"),time(r,"lease_until"));
    public Optional<VDRuntime> runtime(UUID id) { return jdbc.query("SELECT * FROM edgeai.vd_runtime WHERE id=?",RUNTIME,id).stream().findFirst(); }
    public Optional<VDRuntime> current(UUID vdId) { return jdbc.query("SELECT * FROM edgeai.vd_runtime WHERE vd_id=? AND observed_state<>'TERMINATED'",RUNTIME,vdId).stream().findFirst(); }
    public List<VDRuntime> active(String namespace,int limit) { return jdbc.query("SELECT * FROM edgeai.vd_runtime WHERE namespace=? AND observed_state<>'TERMINATED' ORDER BY created_at,id LIMIT ?",RUNTIME,namespace,limit); }
    public long nextGeneration(UUID vdId) { return jdbc.queryForObject("SELECT coalesce(max(generation),0)+1 FROM edgeai.vd_runtime WHERE vd_id=?",Long.class,vdId); }
    public List<VDRuntime> history(UUID vdId,int limit) { return jdbc.query("SELECT * FROM edgeai.vd_runtime WHERE vd_id=? ORDER BY generation DESC LIMIT ?",RUNTIME,vdId,limit); }
    public List<VDRuntimeBinding> bindings(UUID vdId,int limit) {
        return jdbc.query("SELECT b.* FROM edgeai.vd_runtime_binding b JOIN edgeai.vd_runtime r ON r.id=b.runtime_id WHERE b.vd_id=? ORDER BY r.generation DESC LIMIT ?",
            (r,n)->new VDRuntimeBinding(id(r,"id"),id(r,"vd_id"),id(r,"runtime_id"),r.getLong("opened_revision"),r.getObject("closed_revision",Long.class),time(r,"opened_at"),time(r,"closed_at")),vdId,limit);
    }
    public void create(VDRuntime r) {
        jdbc.update("""
            INSERT INTO edgeai.vd_runtime(id,vd_id,generation,requested_revision,configuration,configuration_digest,namespace,pod_name,claim_nonce,
                desired_state,observed_state,startup_deadline,created_at,updated_at)
            VALUES (?,?,?,?,?::jsonb,?,?,?,?, 'RUNNING','PENDING',?,?,?)
            """,r.id(),r.vdId(),r.generation(),r.requestedRevision(),r.configurationJson(),r.configurationDigest(),r.namespace(),r.podName(),r.claimNonce(),
            ts(r.startupDeadline()),ts(r.createdAt()),ts(r.updatedAt()));
        jdbc.update("INSERT INTO edgeai.vd_runtime_binding(id,vd_id,runtime_id,opened_revision,opened_at) VALUES (?,?,?,?,?)",
            UUID.randomUUID(),r.vdId(),r.id(),r.requestedRevision(),ts(r.createdAt()));
        command(r.id(),"CREATE",r.createdAt());
    }
    public void submitted(UUID id,UUID uid,Instant now) {
        jdbc.update("UPDATE edgeai.vd_runtime SET pod_uid=?,observed_state=CASE WHEN observed_state='PENDING' THEN 'SUBMITTED' ELSE observed_state END,updated_at=? WHERE id=?",uid,ts(now),id);
    }
    public void attest(UUID id,UUID node,String nodeName,UUID session,Instant leaseUntil,boolean ready,Instant now) {
        jdbc.update("UPDATE edgeai.vd_runtime SET node_uid=?,node_name=?,session_id=?,lease_until=?,observed_state=CASE WHEN ? THEN 'READY' ELSE 'UNREADY' END,ready_at=CASE WHEN ? THEN coalesce(ready_at,?) ELSE ready_at END,updated_at=? WHERE id=?",
            node,nodeName,session,ts(leaseUntil),ready,ready,ts(now),ts(now),id);
    }
    public void unready(UUID id,Instant now) {
        jdbc.update("UPDATE edgeai.vd_runtime SET observed_state='UNREADY',updated_at=? WHERE id=? AND observed_state='READY'",ts(now),id);
    }
    public void drain(UUID id,Instant deadline,Instant now) {
        jdbc.update("UPDATE edgeai.vd_runtime SET desired_state='DRAINING',observed_state=CASE WHEN observed_state='READY' THEN 'UNREADY' ELSE observed_state END,drain_deadline=?,updated_at=? WHERE id=? AND desired_state='RUNNING'",
            ts(deadline),ts(now),id);
    }
    public void stop(UUID id,String reason,Instant now) {
        jdbc.update("UPDATE edgeai.vd_runtime SET desired_state='STOPPED',observed_state=CASE WHEN observed_state='READY' THEN 'UNREADY' ELSE observed_state END,failure_reason=coalesce(failure_reason,?),updated_at=? WHERE id=?",
            reason,ts(now),id);
        command(id,"DELETE",now);
    }
    public void terminated(UUID id,long revision,Instant now) {
        jdbc.update("UPDATE edgeai.vd_runtime SET observed_state='TERMINATED',updated_at=? WHERE id=? AND desired_state='STOPPED'",ts(now),id);
        jdbc.update("UPDATE edgeai.vd_runtime_binding SET closed_revision=?,closed_at=? WHERE runtime_id=? AND closed_at IS NULL",revision,ts(now),id);
    }
    public Optional<VDOperation> operation(UUID id) { return jdbc.query("SELECT * FROM edgeai.vd_operation WHERE id=?",OPERATION,id).stream().findFirst(); }
    public Optional<VDOperation> byKey(UUID vdId,String key) { return jdbc.query("SELECT * FROM edgeai.vd_operation WHERE vd_id=? AND request_key=?",OPERATION,vdId,key).stream().findFirst(); }
    public Optional<VDOperation> pending(UUID vdId) { return jdbc.query("SELECT * FROM edgeai.vd_operation WHERE vd_id=? AND state='RUNNING'",OPERATION,vdId).stream().findFirst(); }
    public void createOperation(VDOperation o) {
        jdbc.update("""
            INSERT INTO edgeai.vd_operation(id,vd_id,request_key,request_digest,kind,requested_revision,configuration,configuration_digest,
                source_runtime_id,target_runtime_id,state,reason,created_at,updated_at,finished_at)
            VALUES (?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?)
            """,o.id(),o.vdId(),o.requestKey(),o.requestDigest(),o.kind(),o.requestedRevision(),o.configurationJson(),o.configurationDigest(),
            o.sourceRuntimeId(),o.targetRuntimeId(),o.state(),o.reason(),ts(o.createdAt()),ts(o.updatedAt()),ts(o.finishedAt()));
    }
    public void operationTarget(UUID id,UUID runtimeId,Instant now) { jdbc.update("UPDATE edgeai.vd_operation SET target_runtime_id=?,updated_at=? WHERE id=? AND state='RUNNING'",runtimeId,ts(now),id); }
    public void finishOperation(UUID id,String state,String reason,Instant now) {
        jdbc.update("UPDATE edgeai.vd_operation SET state=?,reason=?,updated_at=?,finished_at=? WHERE id=? AND state='RUNNING'",state,reason,ts(now),ts(now),id);
    }
    public void command(UUID runtimeId,String kind,Instant now) {
        jdbc.update("""
            INSERT INTO edgeai.vd_runtime_command(id,runtime_id,kind,available_at,created_at,updated_at) VALUES (?,?,?,?,?,?)
            ON CONFLICT(runtime_id,kind) DO UPDATE SET completed=false,available_at=EXCLUDED.available_at,updated_at=EXCLUDED.updated_at
            WHERE vd_runtime_command.completed
            """,UUID.randomUUID(),runtimeId,kind,ts(now),ts(now),ts(now));
    }
    public boolean createCommandComplete(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT coalesce(bool_and(completed),false) FROM edgeai.vd_runtime_command WHERE runtime_id=? AND kind='CREATE'",Boolean.class,id)); }
    public Optional<VDCommand> leaseCommand(String namespace,UUID owner,Instant now,Duration duration) {
        if(duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(5))>0)throw new IllegalArgumentException("Invalid command lease duration");
        return jdbc.query("""
            WITH selected AS (
                SELECT c.id FROM edgeai.vd_runtime_command c JOIN edgeai.vd_runtime r ON r.id=c.runtime_id
                WHERE r.namespace=? AND NOT c.completed AND c.available_at<=? AND (c.lease_until IS NULL OR c.lease_until<=?)
                ORDER BY c.available_at,c.id FOR UPDATE OF c SKIP LOCKED LIMIT 1
            ) UPDATE edgeai.vd_runtime_command c SET lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=?
                FROM selected WHERE c.id=selected.id RETURNING c.*
            """,COMMAND,namespace,ts(now),ts(now),owner,ts(now.plus(duration)),ts(now)).stream().findFirst();
    }
    public boolean finishCommand(UUID id,UUID owner,Instant now) {
        return jdbc.update("UPDATE edgeai.vd_runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND NOT completed AND lease_owner=? AND lease_until>?",ts(now),id,owner,ts(now))==1;
    }
    public boolean deferCommand(UUID id,UUID owner,Instant availableAt,Instant now) {
        return jdbc.update("UPDATE edgeai.vd_runtime_command SET available_at=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND NOT completed AND lease_owner=? AND lease_until>?",ts(availableAt),ts(now),id,owner,ts(now))==1;
    }
}
