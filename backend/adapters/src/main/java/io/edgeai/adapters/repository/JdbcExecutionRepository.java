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
    private static final RowMapper<WorkflowRun> RUN=(r,n)->new WorkflowRun(r.getObject("id",UUID.class),r.getObject("workflow_version_id",UUID.class),r.getObject("idempotency_key",UUID.class),r.getString("request_digest"),r.getString("mode"),r.getObject("node_id",UUID.class),r.getString("parameters"),r.getString("state"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    private static final RowMapper<Task> TASK=(r,n)->new Task(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getObject("definition_id",UUID.class),r.getString("task_key"),r.getString("state"),r.getString("cancellation_reason"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    private static final RowMapper<TaskAttempt> ATTEMPT=(r,n)->new TaskAttempt(r.getObject("id",UUID.class),r.getObject("task_id",UUID.class),r.getInt("number"),r.getLong("epoch"),r.getString("state"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    private static final String TASK_QUERY="SELECT t.*,d.task_key FROM edgeai.task t JOIN edgeai.task_definition d ON t.definition_id=d.id";
    public boolean create(WorkflowRun r) {
        return jdbc.update("""
            INSERT INTO edgeai.workflow_run(id,workflow_version_id,idempotency_key,request_digest,mode,node_id,parameters,state,created_at,updated_at)
            VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?,?) ON CONFLICT(idempotency_key) DO NOTHING
            """,r.id(),r.workflowVersionId(),r.idempotencyKey(),r.requestDigest(),r.mode(),r.nodeId(),r.parametersJson(),r.state(),Timestamp.from(r.createdAt()),Timestamp.from(r.updatedAt()))==1;
    }
    public void initialize(WorkflowRun run,List<TaskDefinition> definitions,Set<String> roots) {
        for(var d:definitions) {
            UUID id=UUID.randomUUID();boolean root=roots.contains(d.key());Timestamp now=Timestamp.from(run.createdAt());
            jdbc.update("INSERT INTO edgeai.task(id,run_id,workflow_version_id,definition_id,state,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",id,run.id(),run.workflowVersionId(),d.id(),root?"READY":"WAITING",now,now);
            if(root) jdbc.update("INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,created_at,updated_at) VALUES (?,?,1,1,'QUEUED',?,?)",UUID.randomUUID(),id,now,now);
        }
    }
    public Optional<WorkflowRun> run(UUID id,boolean lock) { return jdbc.query("SELECT * FROM edgeai.workflow_run WHERE id=?"+(lock?" FOR UPDATE":""),RUN,id).stream().findFirst(); }
    public Optional<WorkflowRun> byIdempotencyKey(UUID key) { return jdbc.query("SELECT * FROM edgeai.workflow_run WHERE idempotency_key=?",RUN,key).stream().findFirst(); }
    public List<WorkflowRun> runs(int limit,int offset) { return jdbc.query("SELECT * FROM edgeai.workflow_run ORDER BY created_at DESC,id LIMIT ? OFFSET ?",RUN,limit,offset); }
    public List<Task> tasks(UUID id) { return jdbc.query(TASK_QUERY+" WHERE t.run_id=? ORDER BY d.task_key COLLATE \"C\"",TASK,id); }
    public Optional<Task> task(UUID id) { return jdbc.query(TASK_QUERY+" WHERE t.id=?",TASK,id).stream().findFirst(); }
    public List<TaskAttempt> attempts(UUID id) { return jdbc.query("SELECT * FROM edgeai.task_attempt WHERE task_id=? ORDER BY number DESC",ATTEMPT,id); }
    public Optional<TaskAttempt> attempt(UUID id) { return jdbc.query("SELECT * FROM edgeai.task_attempt WHERE id=?",ATTEMPT,id).stream().findFirst(); }
    public void cancelTask(UUID id,String terminal,String reason,Instant now) {
        Timestamp time=Timestamp.from(now);
        jdbc.update("UPDATE edgeai.task_attempt SET state=CASE WHEN state='QUEUED' THEN 'CANCELLED' ELSE 'CANCELLING' END,updated_at=? WHERE task_id=? AND state IN ('QUEUED','DISPATCHING','RUNNING')",time,id);
        jdbc.update("UPDATE edgeai.task SET state=CASE WHEN state IN ('WAITING','READY') AND NOT EXISTS (SELECT 1 FROM edgeai.task_attempt a WHERE a.task_id=? AND a.state='CANCELLING') THEN ? ELSE 'CANCELLING' END,cancellation_reason=?,updated_at=? WHERE id=? AND state IN ('WAITING','READY','RUNNING')",id,terminal,reason,time,id);
    }
    public void reconcileRunState(UUID id,Instant now) {
        var states=tasks(id).stream().map(Task::state).toList();
        boolean active=states.stream().anyMatch(s->Set.of("WAITING","READY","RUNNING","CANCELLING").contains(s));
        if(!active) {
            String state=states.contains("FAILED")?"FAILED":states.stream().anyMatch(s->Set.of("CANCELLED","SKIPPED").contains(s))?"CANCELLED":"SUCCEEDED";
            jdbc.update("UPDATE edgeai.workflow_run SET state=?,updated_at=? WHERE id=?",state,Timestamp.from(now),id);
        } else if(states.contains("CANCELLING") && states.stream().noneMatch(s->Set.of("WAITING","READY","RUNNING").contains(s)))
            jdbc.update("UPDATE edgeai.workflow_run SET state='CANCELLING',updated_at=? WHERE id=?",Timestamp.from(now),id);
    }
}
