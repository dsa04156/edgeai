package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Mutations run inside a service transaction holding the parent Run lock. */
public final class JdbcRuntimeRepository implements RuntimeRepository {
    private final JdbcTemplate jdbc;
    public JdbcRuntimeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Instant instant(ResultSet row,String key) throws SQLException {
        var value=row.getTimestamp(key); return value==null?null:value.toInstant();
    }
    private static final RowMapper<RuntimeInstance> RUNTIME=(r,n)->new RuntimeInstance(
        r.getObject("id",UUID.class),r.getObject("attempt_id",UUID.class),r.getObject("task_id",UUID.class),r.getObject("run_id",UUID.class),r.getLong("epoch"),
        r.getString("namespace"),r.getString("job_name"),r.getObject("claim_nonce",UUID.class),r.getString("desired_state"),r.getString("observed_state"),
        r.getObject("job_uid",UUID.class),r.getObject("producer_pod_uid",UUID.class),r.getObject("node_uid",UUID.class),r.getString("node_name"),
        instant(r,"expires_at"),r.getString("failure_reason"),instant(r,"created_at"),instant(r,"updated_at"),r.getObject("remote_allocation_id",UUID.class));
    public Optional<RuntimeInstance> runtime(UUID id) { return jdbc.query("SELECT * FROM edgeai.runtime_instance WHERE id=?",RUNTIME,id).stream().findFirst(); }
    public Optional<RuntimeInstance> byAttempt(UUID id) { return jdbc.query("SELECT * FROM edgeai.runtime_instance WHERE attempt_id=?",RUNTIME,id).stream().findFirst(); }
    public List<UUID> readyAttempts(UUID runId,int limit) {
        return jdbc.query("""
            SELECT a.id FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id
            JOIN edgeai.workflow_run w ON w.id=t.run_id
            WHERE a.state='QUEUED' AND t.state='READY' AND w.state IN ('PENDING','RUNNING') AND w.id=?
            ORDER BY a.created_at,a.id LIMIT ?
            """,(r,n)->r.getObject(1,UUID.class),runId,limit);
    }
    public List<RuntimeInstance> active(String namespace,int limit) {
        return jdbc.query("SELECT * FROM edgeai.runtime_instance WHERE namespace=? AND remote_allocation_id IS NULL AND observed_state<>'TERMINATED' ORDER BY updated_at,id LIMIT ?",RUNTIME,namespace,limit);
    }
    public List<RuntimeInstance> activeRemote(String namespace,int limit) {
        return jdbc.query("SELECT * FROM edgeai.runtime_instance WHERE namespace=? AND remote_allocation_id IS NOT NULL AND observed_state<>'TERMINATED' ORDER BY updated_at,id LIMIT ?",RUNTIME,namespace,limit);
    }
    public void create(RuntimeInstance r) {
        Timestamp now=Timestamp.from(r.createdAt());
        jdbc.update("""
            INSERT INTO edgeai.runtime_instance(id,attempt_id,task_id,run_id,epoch,namespace,job_name,claim_nonce,
                desired_state,observed_state,created_at,updated_at,remote_allocation_id,expires_at)
            VALUES (?,?,?,?,?,?,?,?,'RUNNING','PENDING',?,?,?,?)
            """,r.id(),r.attemptId(),r.taskId(),r.runId(),r.epoch(),r.namespace(),r.jobName(),r.claimNonce(),now,now,r.remoteAllocationId(),r.expiresAt()==null?null:Timestamp.from(r.expiresAt()));
        jdbc.update("UPDATE edgeai.task_attempt SET state='DISPATCHING',updated_at=? WHERE id=?",now,r.attemptId());
        jdbc.update("UPDATE edgeai.task SET state='RUNNING',updated_at=? WHERE id=?",now,r.taskId());
        jdbc.update("UPDATE edgeai.workflow_run SET state='RUNNING',updated_at=? WHERE id=?",now,r.runId());
        command(r.id(),"CREATE",r.createdAt());
    }
    public void submitted(UUID id,UUID uid,Instant expiresAt,Instant now) {
        jdbc.update("""
            UPDATE edgeai.runtime_instance SET job_uid=?,expires_at=COALESCE(expires_at,?),
                observed_state=CASE WHEN observed_state IN ('PENDING','TERMINATED') THEN 'SUBMITTED' ELSE observed_state END,updated_at=? WHERE id=?
            """,uid,Timestamp.from(expiresAt),Timestamp.from(now),id);
    }
    public void claimed(UUID id,RuntimePod pod,Instant now) {
        jdbc.update("""
            UPDATE edgeai.runtime_instance SET producer_pod_uid=?,node_uid=?,node_name=?,observed_state='RUNNING',updated_at=? WHERE id=?
            """,pod.podUid(),pod.nodeUid(),pod.nodeName(),Timestamp.from(now),id);
        jdbc.update("UPDATE edgeai.task_attempt SET state='RUNNING',updated_at=? WHERE id=(SELECT attempt_id FROM edgeai.runtime_instance WHERE id=?)",Timestamp.from(now),id);
    }
    public void remoteObserved(UUID id,boolean running,Instant now) {
        jdbc.update("UPDATE edgeai.runtime_instance SET observed_state=?,updated_at=? WHERE id=? AND remote_allocation_id IS NOT NULL",running?"RUNNING":"SUBMITTED",Timestamp.from(now),id);
        if(running)jdbc.update("UPDATE edgeai.task_attempt SET state='RUNNING',updated_at=? WHERE state='DISPATCHING' AND id=(SELECT attempt_id FROM edgeai.runtime_instance WHERE id=?)",Timestamp.from(now),id);
    }
    public void stopForRun(UUID runId,Instant now) {
        var ids=jdbc.query("""
            SELECT r.id FROM edgeai.runtime_instance r JOIN edgeai.task t ON t.id=r.task_id
            JOIN edgeai.task_attempt a ON a.id=r.attempt_id
            WHERE r.run_id=? AND r.desired_state='RUNNING'
              AND (t.state IN ('CANCELLING','CANCELLED','SKIPPED','FAILED','SUCCEEDED')
                OR a.state IN ('CANCELLING','CANCELLED','FAILED','SUCCEEDED','OFFLOADED'))
            """,(r,n)->r.getObject(1,UUID.class),runId);
        ids.forEach(id->stop(id,null,now));
    }
    public void stop(UUID id,String reason,Instant now) {
        jdbc.update("UPDATE edgeai.runtime_instance SET desired_state='STOPPED',failure_reason=COALESCE(?,failure_reason),updated_at=? WHERE id=?",reason,Timestamp.from(now),id);
        command(id,"DELETE",now);
    }
    private void command(UUID id,String kind,Instant now) {
        jdbc.update("""
            INSERT INTO edgeai.runtime_command(id,runtime_id,kind,available_at,created_at,updated_at) VALUES (?,?,?,?,?,?)
            ON CONFLICT(runtime_id,kind) DO UPDATE SET completed=false,available_at=EXCLUDED.available_at,
                lease_owner=NULL,lease_until=NULL,updated_at=EXCLUDED.updated_at
            """,UUID.randomUUID(),id,kind,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
    }
    public void terminated(UUID id,Instant now) {
        Timestamp time=Timestamp.from(now);
        jdbc.update("UPDATE edgeai.runtime_instance SET observed_state='TERMINATED',updated_at=? WHERE id=?",time,id);
        jdbc.update("UPDATE edgeai.task_attempt SET state='CANCELLED',updated_at=? WHERE state='CANCELLING' AND id=(SELECT attempt_id FROM edgeai.runtime_instance WHERE id=?)",time,id);
        jdbc.update("""
            UPDATE edgeai.task SET state=CASE WHEN cancellation_reason LIKE 'UPSTREAM_%' THEN 'SKIPPED' ELSE 'CANCELLED' END,
                updated_at=? WHERE state='CANCELLING' AND id=(SELECT task_id FROM edgeai.runtime_instance WHERE id=?)
                AND NOT EXISTS(SELECT 1 FROM edgeai.runtime_instance remaining WHERE remaining.task_id=edgeai.task.id AND remaining.observed_state<>'TERMINATED')
            """,time,id);
    }
    public void fail(UUID id,String reason,Instant now) {
        jdbc.update("UPDATE edgeai.task_attempt SET state='FAILED',updated_at=? WHERE id=(SELECT attempt_id FROM edgeai.runtime_instance WHERE id=?)",Timestamp.from(now),id);
        jdbc.update("UPDATE edgeai.task SET state='FAILED',updated_at=? WHERE id=(SELECT task_id FROM edgeai.runtime_instance WHERE id=?)",Timestamp.from(now),id);
        stop(id,reason,now);
    }
    public void offload(UUID runtimeId,Instant now) {
        jdbc.update("UPDATE edgeai.task_attempt SET state='OFFLOADED',updated_at=? WHERE id=(SELECT attempt_id FROM edgeai.runtime_instance WHERE id=?)",Timestamp.from(now),runtimeId);
        jdbc.update("UPDATE edgeai.task SET state='OFFLOADING',updated_at=? WHERE id=(SELECT task_id FROM edgeai.runtime_instance WHERE id=?)",Timestamp.from(now),runtimeId);
        stop(runtimeId,"OFFLOADED",now);
    }
    public boolean retryReady(UUID taskId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM edgeai.runtime_instance r WHERE r.task_id=?
                AND (r.desired_state<>'STOPPED' OR r.observed_state<>'TERMINATED'
                    OR EXISTS(SELECT 1 FROM edgeai.runtime_command c WHERE c.runtime_id=r.id AND c.kind='CREATE' AND NOT c.completed)))
            """,Boolean.class,taskId));
    }
    public Optional<TaskResult> result(UUID taskId) {
        var values=jdbc.query("SELECT * FROM edgeai.task_result WHERE task_id=? AND committed",(r,n)->new TaskResult(
            r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getObject("attempt_id",UUID.class),r.getObject("runtime_id",UUID.class),
            r.getLong("epoch"),r.getObject("producer_pod_uid",UUID.class),r.getString("manifest_digest"),instant(r,"created_at"),List.of(),r.getObject("remote_allocation_id",UUID.class)),taskId);
        if(values.isEmpty())return Optional.empty();var r=values.getFirst();
        var outputs=jdbc.query("SELECT * FROM edgeai.result_artifact WHERE result_id=? ORDER BY port COLLATE \"C\"",(a,n)->new TaskResult.Output(a.getString("port"),
            new VerifiedArtifact(a.getString("bucket"),a.getString("object_key"),a.getString("object_version"),a.getString("sha256"),a.getLong("bytes"),a.getString("media_type"))),r.id());
        return Optional.of(new TaskResult(r.id(),r.taskId(),r.attemptId(),r.runtimeId(),r.epoch(),r.producerPodUid(),r.manifestDigest(),r.createdAt(),outputs,r.remoteAllocationId()));
    }
    public void commit(TaskResult r,Instant now) {
        Timestamp time=Timestamp.from(now);
        jdbc.update("""
            INSERT INTO edgeai.task_result(id,task_id,attempt_id,runtime_id,epoch,producer_pod_uid,manifest_digest,created_at,remote_allocation_id)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,r.id(),r.taskId(),r.attemptId(),r.runtimeId(),r.epoch(),r.producerPodUid(),r.manifestDigest(),time,r.remoteAllocationId());
        for(var output:r.outputs()) {
            var a=output.artifact();
            jdbc.update("""
                INSERT INTO edgeai.result_artifact(id,result_id,port,bucket,object_key,object_version,sha256,bytes,media_type)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,UUID.randomUUID(),r.id(),output.port(),a.bucket(),a.objectKey(),a.versionId(),a.sha256(),a.bytes(),a.mediaType());
        }
        jdbc.update("UPDATE edgeai.task_result SET committed=true WHERE id=?",r.id());
        jdbc.update("UPDATE edgeai.task_attempt SET state='SUCCEEDED',updated_at=? WHERE id=?",time,r.attemptId());
        jdbc.update("UPDATE edgeai.task SET state='SUCCEEDED',updated_at=? WHERE id=?",time,r.taskId());
        stop(r.runtimeId(),null,now);
    }
    public void releaseReadyChildren(UUID runId,Instant now) {
        var ids=jdbc.query("""
            UPDATE edgeai.task t SET state='READY',updated_at=? WHERE t.run_id=? AND t.state='WAITING'
            AND NOT EXISTS (
                SELECT 1 FROM edgeai.task_dependency d JOIN edgeai.task parent ON parent.definition_id=d.from_task_id AND parent.run_id=t.run_id
                WHERE d.to_task_id=t.definition_id AND (parent.state<>'SUCCEEDED'
                    OR NOT EXISTS(SELECT 1 FROM edgeai.task_result result WHERE result.task_id=parent.id AND result.committed)))
            RETURNING t.id
            """,(r,n)->r.getObject(1,UUID.class),Timestamp.from(now),runId);
        for(UUID id:ids) jdbc.update("INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,created_at,updated_at,remote_provider_key,remote_configuration_digest,remote_source_mode) SELECT ?,?,1,1,'QUEUED',w.mode,w.node_id,'INITIAL',?,?,w.remote_provider_key,w.remote_configuration_digest,w.remote_source_mode FROM edgeai.workflow_run w WHERE w.id=?",UUID.randomUUID(),id,Timestamp.from(now),Timestamp.from(now),runId);
    }
    public Optional<RuntimeCommand> leaseCommand(String namespace,UUID owner,Instant now,Duration duration) {
        return lease(namespace,owner,now,duration,false);
    }
    public Optional<RuntimeCommand> leaseRemoteCommand(String namespace,UUID owner,Instant now,Duration duration) {
        return lease(namespace,owner,now,duration,true);
    }
    private Optional<RuntimeCommand> lease(String namespace,UUID owner,Instant now,Duration duration,boolean remote) {
        RuntimeNames.dns(namespace,63);
        if(owner==null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(5))>0) throw new IllegalArgumentException("Lease must be positive and at most five minutes");
        return jdbc.query("""
            UPDATE edgeai.runtime_command SET lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=?
            WHERE id=(SELECT id FROM edgeai.runtime_command WHERE NOT completed AND available_at<=?
                AND runtime_id IN (SELECT id FROM edgeai.runtime_instance WHERE namespace=? AND (remote_allocation_id IS NOT NULL)=?)
                AND (lease_until IS NULL OR lease_until<=?) ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING *
            """,(r,n)->new RuntimeCommand(r.getObject("id",UUID.class),r.getObject("runtime_id",UUID.class),r.getString("kind"),r.getInt("attempts"),
                r.getObject("lease_owner",UUID.class),instant(r,"lease_until")),owner,Timestamp.from(now.plus(duration)),Timestamp.from(now),Timestamp.from(now),namespace,remote,Timestamp.from(now)).stream().findFirst();
    }
    public boolean finishCommand(UUID id,UUID owner,Instant now) {
        return jdbc.update("UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND lease_owner=?",Timestamp.from(now),id,owner)==1;
    }
    public boolean deferCommand(UUID id,UUID owner,Instant availableAt,Instant now) {
        return jdbc.update("UPDATE edgeai.runtime_command SET available_at=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND lease_owner=?",Timestamp.from(availableAt),Timestamp.from(now),id,owner)==1;
    }
}
