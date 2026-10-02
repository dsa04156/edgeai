package io.edgeai.adapters.repository;

import io.edgeai.domain.execution.*;
import io.edgeai.domain.workflow.TaskDefinition;
import io.edgeai.domain.repository.ExecutionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcExecutionRepository implements ExecutionRepository {
    private final JdbcTemplate jdbc;
    public JdbcExecutionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<WorkflowRun> RUN=(r,n)->new WorkflowRun(r.getObject("id",UUID.class),r.getObject("workflow_version_id",UUID.class),r.getObject("idempotency_key",UUID.class),r.getString("request_digest"),r.getString("mode"),r.getObject("node_id",UUID.class),r.getString("parameters"),new RetryPolicy(r.getInt("retry_max_attempts"),r.getInt("retry_backoff_seconds"),r.getInt("retry_max_elapsed_seconds"),Set.of((String[])r.getArray("retry_on").getArray())),r.getString("offload_policy"),r.getString("state"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),RemoteTargets.read(r));
    private static final RowMapper<Task> TASK=(r,n)->new Task(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getObject("definition_id",UUID.class),r.getString("task_key"),r.getString("state"),r.getString("cancellation_reason"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    private static final RowMapper<TaskAttempt> ATTEMPT=(r,n)->new TaskAttempt(r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getInt("number"),r.getLong("epoch"),r.getString("state"),r.getString("mode"),r.getObject("node_id",UUID.class),r.getString("cause"),List.of((String[])r.getArray("excluded_node_names").getArray()),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),RemoteTargets.read(r));
    private static final String TASK_QUERY="SELECT t.*,d.task_key FROM edgeai.task t JOIN edgeai.task_definition d ON t.definition_id=d.id";
    public boolean create(WorkflowRun r) {
        return jdbc.update("""
            INSERT INTO edgeai.workflow_run(id,workflow_version_id,idempotency_key,request_digest,mode,node_id,parameters,retry_max_attempts,retry_backoff_seconds,retry_max_elapsed_seconds,retry_on,offload_policy,state,created_at,updated_at,remote_provider_key,remote_configuration_digest,remote_source_mode)
            VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,CAST(? AS text[]),CAST(? AS jsonb),?,?,?,?,?,?) ON CONFLICT(idempotency_key) DO NOTHING
            """,r.id(),r.workflowVersionId(),r.idempotencyKey(),r.requestDigest(),r.mode(),r.nodeId(),r.parametersJson(),r.retry().maxAttempts(),r.retry().backoffSeconds(),r.retry().maxElapsedSeconds(),"{"+String.join(",",r.retry().retryOn().stream().sorted().toList())+"}",r.offloadPolicyJson(),r.state(),Timestamp.from(r.createdAt()),Timestamp.from(r.updatedAt()),RemoteTargets.key(r.remoteTarget()),RemoteTargets.digest(r.remoteTarget()),RemoteTargets.source(r.remoteTarget()))==1;
    }
    public void initialize(WorkflowRun run,List<TaskDefinition> definitions,Set<String> roots) {
        for(var d:definitions) {
            UUID id=UUID.randomUUID();boolean root=roots.contains(d.key());Timestamp now=Timestamp.from(run.createdAt());
            jdbc.update("INSERT INTO edgeai.task(id,run_id,workflow_version_id,definition_id,state,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",id,run.id(),run.workflowVersionId(),d.id(),root?"READY":"WAITING",now,now);
            if(root) jdbc.update("INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,created_at,updated_at,remote_provider_key,remote_configuration_digest,remote_source_mode) VALUES (?,?,1,1,'QUEUED',?,?,'INITIAL',?,?,?,?,?)",UUID.randomUUID(),id,run.mode(),run.nodeId(),now,now,RemoteTargets.key(run.remoteTarget()),RemoteTargets.digest(run.remoteTarget()),RemoteTargets.source(run.remoteTarget()));
        }
    }
    public Optional<WorkflowRun> run(UUID id,boolean lock) { return jdbc.query("SELECT * FROM edgeai.workflow_run WHERE id=?"+(lock?" FOR UPDATE":""),RUN,id).stream().findFirst(); }
    public Optional<WorkflowRun> byIdempotencyKey(UUID key) { return jdbc.query("SELECT * FROM edgeai.workflow_run WHERE idempotency_key=?",RUN,key).stream().findFirst(); }
    public List<WorkflowRun> runs(int limit,int offset) { return jdbc.query("SELECT * FROM edgeai.workflow_run ORDER BY created_at DESC,id LIMIT ? OFFSET ?",RUN,limit,offset); }
    public List<Task> tasks(UUID id) { return jdbc.query(TASK_QUERY+" WHERE t.run_id=? ORDER BY d.task_key COLLATE \"C\"",TASK,id); }
    public Optional<Task> task(UUID id) { return jdbc.query(TASK_QUERY+" WHERE t.id=?",TASK,id).stream().findFirst(); }
    public List<TaskAttempt> attempts(UUID id) { return jdbc.query("SELECT * FROM edgeai.task_attempt WHERE task_id=? ORDER BY number DESC",ATTEMPT,id); }
    public Optional<TaskAttempt> attempt(UUID id) { return jdbc.query("SELECT * FROM edgeai.task_attempt WHERE id=?",ATTEMPT,id).stream().findFirst(); }
    public void scheduleRetry(TaskRetry retry,Instant now) {
        jdbc.update("INSERT INTO edgeai.task_retry(task_id,failed_attempt_id,namespace,available_at,deadline) VALUES (?,?,?,?,?)",
            retry.taskId(),retry.failedAttemptId(),retry.namespace(),Timestamp.from(retry.availableAt()),Timestamp.from(retry.deadline()));
        jdbc.update("UPDATE edgeai.task SET state='RETRY_WAIT',updated_at=? WHERE id=?",Timestamp.from(now),retry.taskId());
    }
    public Optional<TaskRetry> retry(UUID taskId) {
        return jdbc.query("SELECT * FROM edgeai.task_retry WHERE task_id=?",(r,n)->new TaskRetry(r.getObject("task_id",UUID.class),
            r.getObject("failed_attempt_id",UUID.class),r.getString("namespace"),r.getTimestamp("available_at").toInstant(),r.getTimestamp("deadline").toInstant()),taskId).stream().findFirst();
    }
    public List<UUID> dueRetries(String namespace,Instant now,int limit) {
        return jdbc.query("SELECT task_id FROM edgeai.task_retry WHERE namespace=? AND available_at<=? ORDER BY available_at,task_id LIMIT ?",
            (r,n)->r.getObject(1,UUID.class),namespace,Timestamp.from(now),limit);
    }
    public void clearRetry(UUID taskId){jdbc.update("DELETE FROM edgeai.task_retry WHERE task_id=?",taskId);}
    public TaskAttempt startRetry(UUID taskId,Instant now) {
        var previous=attempts(taskId).getFirst();
        return startAttempt(taskId,previous.mode(),previous.nodeId(),"RETRY",previous.excludedNodeNames(),now,previous.remoteTarget());
    }
    public TaskAttempt startOffload(UUID taskId,UUID nodeId,List<String> excluded,Instant now) { return startAttempt(taskId,nodeId==null?"AUTO":"NODE",nodeId,"OFFLOAD",excluded,now,null); }
    public TaskAttempt startRemoteOffload(UUID taskId,io.edgeai.domain.remote.RemoteTarget target,Instant now){return startAttempt(taskId,"REMOTE",null,"OFFLOAD",List.of(),now,target);}
    private TaskAttempt startAttempt(UUID taskId,String mode,UUID nodeId,String cause,List<String> excluded,Instant now,io.edgeai.domain.remote.RemoteTarget target) {
        var attempt=jdbc.query("""
            INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,excluded_node_names,created_at,updated_at,remote_provider_key,remote_configuration_digest,remote_source_mode)
            SELECT ?,?,max(number)+1,max(epoch)+1,'QUEUED',?,?,?,CAST(? AS text[]), ?,?,?,?,? FROM edgeai.task_attempt WHERE task_id=? RETURNING *
            """,ATTEMPT,UUID.randomUUID(),taskId,mode,nodeId,cause,"{"+String.join(",",excluded)+"}",Timestamp.from(now),Timestamp.from(now),RemoteTargets.key(target),RemoteTargets.digest(target),RemoteTargets.source(target),taskId).getFirst();
        jdbc.update("UPDATE edgeai.task SET state='READY',updated_at=? WHERE id=?",Timestamp.from(now),taskId);
        clearRetry(taskId);return attempt;
    }
    public void failTask(UUID taskId,Instant now){jdbc.update("UPDATE edgeai.task SET state='FAILED',updated_at=? WHERE id=?",Timestamp.from(now),taskId);clearRetry(taskId);}
    public void cancelTask(UUID id,String terminal,String reason,Instant now) {
        Timestamp time=Timestamp.from(now);
        jdbc.update("DELETE FROM edgeai.task_retry WHERE task_id=?",id);
        jdbc.update("UPDATE edgeai.task_attempt SET state=CASE WHEN state='QUEUED' THEN 'CANCELLED' ELSE 'CANCELLING' END,updated_at=? WHERE task_id=? AND state IN ('QUEUED','DISPATCHING','RUNNING')",time,id);
        jdbc.update("UPDATE edgeai.task SET state=CASE WHEN state IN ('WAITING','READY','RETRY_WAIT','OFFLOADING') AND NOT EXISTS (SELECT 1 FROM edgeai.task_attempt a WHERE a.task_id=? AND a.state='CANCELLING') AND NOT EXISTS (SELECT 1 FROM edgeai.runtime_instance r WHERE r.task_id=edgeai.task.id AND r.observed_state<>'TERMINATED') THEN ? ELSE 'CANCELLING' END,cancellation_reason=?,updated_at=? WHERE id=? AND state IN ('WAITING','READY','RUNNING','RETRY_WAIT','OFFLOADING')",id,terminal,reason,time,id);
    }
    public void reconcileRunState(UUID id,Instant now) {
        var states=tasks(id).stream().map(Task::state).toList();
        boolean active=states.stream().anyMatch(s->Set.of("WAITING","READY","RUNNING","RETRY_WAIT","OFFLOADING","CANCELLING").contains(s));
        if(!active) {
            String state=states.contains("FAILED")?"FAILED":states.stream().anyMatch(s->Set.of("CANCELLED","SKIPPED").contains(s))?"CANCELLED":"SUCCEEDED";
            jdbc.update("UPDATE edgeai.workflow_run SET state=?,updated_at=? WHERE id=?",state,Timestamp.from(now),id);
        } else if(states.contains("CANCELLING") && states.stream().noneMatch(s->Set.of("WAITING","READY","RUNNING","RETRY_WAIT","OFFLOADING").contains(s)))
            jdbc.update("UPDATE edgeai.workflow_run SET state='CANCELLING',updated_at=? WHERE id=?",Timestamp.from(now),id);
    }
}
