package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.StreamRunRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcStreamRunRepository implements StreamRunRepository {
    private final JdbcTemplate jdbc;
    public JdbcStreamRunRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void create(Configuration c){jdbc.update("INSERT INTO edgeai.stream_run_configuration(run_id,namespace,broker_digest,lease_seconds,created_at) VALUES (?,?,?,?,?)",
        c.runId(),c.namespace(),c.brokerDigest(),c.leaseSeconds(),Timestamp.from(c.createdAt()));}
    public Optional<Configuration> find(UUID id){return jdbc.query("SELECT * FROM edgeai.stream_run_configuration WHERE run_id=?",
        (r,n)->new Configuration(r.getObject("run_id",UUID.class),r.getString("namespace"),r.getString("broker_digest"),r.getInt("lease_seconds"),r.getTimestamp("created_at").toInstant()),id).stream().findFirst();}
    public void bind(DeviceBinding b){jdbc.update("INSERT INTO edgeai.stream_device_binding(route_id,run_id,device_id,session_id,epoch) VALUES (?,?,?,?,?)",
        b.routeId(),b.runId(),b.deviceId(),b.sessionId(),b.epoch());}
    public List<DeviceBinding> bindings(UUID id){return jdbc.query("SELECT * FROM edgeai.stream_device_binding WHERE run_id=? ORDER BY route_id",
        (r,n)->new DeviceBinding(r.getObject("route_id",UUID.class),r.getObject("run_id",UUID.class),r.getObject("device_id",UUID.class),r.getObject("session_id",UUID.class),r.getLong("epoch")),id);}
    public List<UUID> active(String namespace,UUID after,int limit){return jdbc.query("""
        SELECT c.run_id FROM edgeai.stream_run_configuration c JOIN edgeai.workflow_run w ON w.id=c.run_id
        WHERE c.namespace=? AND w.state IN ('PENDING','RUNNING') AND (CAST(? AS uuid) IS NULL OR c.run_id>?)
        ORDER BY c.run_id LIMIT ?
        """,(r,n)->r.getObject(1,UUID.class),namespace,after,after,limit);}
    public void release(UUID run,Collection<UUID> tasks,Instant now){for(var id:tasks){
        if(jdbc.update("UPDATE edgeai.task SET state='READY',updated_at=? WHERE run_id=? AND id=? AND state='WAITING'",Timestamp.from(now),run,id)!=1)
            throw new IllegalStateException("Stream group release must be atomic from WAITING");
        jdbc.update("""
            INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,created_at,updated_at,remote_provider_key,remote_configuration_digest,remote_source_mode,vd_id)
            SELECT ?,t.id,1,1,'QUEUED',t.initial_mode,t.initial_node_id,'INITIAL',?,?,w.remote_provider_key,w.remote_configuration_digest,w.remote_source_mode,w.vd_id
            FROM edgeai.task t JOIN edgeai.workflow_run w ON w.id=t.run_id WHERE t.id=? AND w.id=?
            """,UUID.randomUUID(),Timestamp.from(now),Timestamp.from(now),id,run);
    }}
}
