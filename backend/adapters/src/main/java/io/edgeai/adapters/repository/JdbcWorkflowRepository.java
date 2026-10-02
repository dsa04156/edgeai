package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.WorkflowRepository;
import io.edgeai.domain.workflow.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcWorkflowRepository implements WorkflowRepository {
    private final JdbcTemplate jdbc;
    public JdbcWorkflowRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<Workflow> WORKFLOW=(r,n)->new Workflow(r.getObject("id",UUID.class),r.getString("workflow_key"),r.getString("display_name"),r.getString("creation_digest"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<WorkflowVersion> VERSION=(r,n)->new WorkflowVersion(r.getObject("id",UUID.class),r.getObject("workflow_id",UUID.class),r.getString("version"),r.getString("dag"),r.getString("digest"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<TaskDefinition> DEFINITION=(r,n)->new TaskDefinition(r.getObject("id",UUID.class),r.getObject("workflow_version_id",UUID.class),r.getString("task_key"),r.getObject("service_profile_version_id",UUID.class),r.getString("parameters"));
    public boolean create(String key,String name,String digest) {
        return jdbc.update("INSERT INTO edgeai.workflow(id,workflow_key,display_name,creation_digest) VALUES (?,?,?,?) ON CONFLICT(workflow_key) DO NOTHING",UUID.randomUUID(),key,name,digest)==1;
    }
    public Optional<Workflow> find(UUID id,boolean lock) { return jdbc.query("SELECT * FROM edgeai.workflow WHERE id=?"+(lock?" FOR UPDATE":""),WORKFLOW,id).stream().findFirst(); }
    public Optional<Workflow> findByKey(String key) { return jdbc.query("SELECT * FROM edgeai.workflow WHERE workflow_key=?",WORKFLOW,key).stream().findFirst(); }
    public List<Workflow> list(int limit,int offset) { return jdbc.query("SELECT * FROM edgeai.workflow ORDER BY workflow_key COLLATE \"C\" LIMIT ? OFFSET ?",WORKFLOW,limit,offset); }
    public Optional<WorkflowVersion> version(UUID id) { return jdbc.query("SELECT * FROM edgeai.workflow_version WHERE id=? AND published",VERSION,id).stream().findFirst(); }
    public Optional<WorkflowVersion> version(UUID id,String version) { return jdbc.query("SELECT * FROM edgeai.workflow_version WHERE workflow_id=? AND version=? AND published",VERSION,id,version).stream().findFirst(); }
    public List<WorkflowVersion> versions(UUID id,int limit,int offset) { return jdbc.query("SELECT * FROM edgeai.workflow_version WHERE workflow_id=? AND published ORDER BY created_at DESC,id LIMIT ? OFFSET ?",VERSION,id,limit,offset); }
    public List<TaskDefinition> definitions(UUID id) { return jdbc.query("SELECT * FROM edgeai.task_definition WHERE workflow_version_id=? ORDER BY task_key COLLATE \"C\"",DEFINITION,id); }
    public WorkflowVersion publish(UUID workflowId,String version,Dag dag,String canonical,String digest) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO edgeai.workflow_version(id,workflow_id,version,dag,digest) VALUES (?,?,?,CAST(? AS jsonb),?)",id,workflowId,version,canonical,digest);
        var ids=new HashMap<String,UUID>();
        for(var node:dag.tasks()) {
            UUID taskId=UUID.randomUUID(); ids.put(node.key(),taskId);
            jdbc.update("INSERT INTO edgeai.task_definition(id,workflow_version_id,task_key,service_profile_version_id,parameters) VALUES (?,?,?,?,CAST(? AS jsonb))",taskId,id,node.key(),node.serviceProfileVersionId(),node.parametersJson());
        }
        for(var edge:dag.dependencies()) jdbc.update("INSERT INTO edgeai.task_dependency(workflow_version_id,from_task_id,to_task_id,from_port,to_port,mode) VALUES (?,?,?,?,?,?)",id,ids.get(edge.fromTask()),ids.get(edge.toTask()),edge.fromPort(),edge.toPort(),edge.mode().name());
        jdbc.update("UPDATE edgeai.workflow_version SET published=true WHERE id=?",id);
        return version(id).orElseThrow();
    }
}
