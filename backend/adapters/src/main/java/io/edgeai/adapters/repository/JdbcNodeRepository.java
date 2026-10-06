package io.edgeai.adapters.repository;

import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.repository.NodeRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcNodeRepository implements NodeRepository {
    private final JdbcTemplate jdbc;
    public JdbcNodeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<ExecutionNode> ROW = (r,n) -> new ExecutionNode(r.getObject("id",UUID.class),r.getString("name"),
        r.getString("architecture"),r.getString("operating_system"),r.getString("observed_status"),r.getString("cpu"),r.getString("memory"),
        r.getString("labels"),r.getTimestamp("observed_at").toInstant(),r.getString("allocatable"));
    public void replaceSnapshot(List<ExecutionNode> nodes,Instant now) {
        jdbc.update("UPDATE edgeai.execution_node SET observed_status='REMOVED'");
        for (var n:nodes) jdbc.update("""
            INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at,allocatable)
            VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb)) ON CONFLICT(id) DO UPDATE SET
            name=excluded.name,architecture=excluded.architecture,operating_system=excluded.operating_system,
            observed_status=excluded.observed_status,cpu=excluded.cpu,memory=excluded.memory,labels=excluded.labels,observed_at=excluded.observed_at,allocatable=excluded.allocatable
            """,n.id(),n.name(),n.architecture(),n.operatingSystem(),n.observedStatus(),n.cpu(),n.memory(),n.labelsJson(),Timestamp.from(now),n.allocatableJson());
    }
    public List<ExecutionNode> list(int limit,int offset) { return jdbc.query("SELECT * FROM edgeai.execution_node ORDER BY name,id LIMIT ? OFFSET ?",ROW,limit,offset); }
    public Optional<ExecutionNode> find(UUID id) { return jdbc.query("SELECT * FROM edgeai.execution_node WHERE id=?",ROW,id).stream().findFirst(); }
}
