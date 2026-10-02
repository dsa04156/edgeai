package io.edgeai.domain.repository;
import io.edgeai.domain.workflow.*;
import java.util.*;
public interface WorkflowRepository {
    boolean create(String key, String displayName, String digest);
    Optional<Workflow> find(UUID id, boolean lock);
    Optional<Workflow> findByKey(String key);
    List<Workflow> list(int limit, int offset);
    Optional<WorkflowVersion> version(UUID id);
    Optional<WorkflowVersion> version(UUID workflowId, String version);
    List<WorkflowVersion> versions(UUID workflowId, int limit, int offset);
    WorkflowVersion publish(UUID workflowId, String version, Dag dag, String canonicalJson, String digest);
    List<TaskDefinition> definitions(UUID versionId);
}
