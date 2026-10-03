package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.StreamExecutionRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcStreamExecutionRepository implements StreamExecutionRepository {
    private final JdbcTemplate jdbc;
    public JdbcStreamExecutionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Instant instant(ResultSet r,String key)throws SQLException { var t=r.getTimestamp(key);return t==null?null:t.toInstant(); }
    public Optional<String> bindingDigest(UUID run) {
        return jdbc.queryForList("SELECT route_digest FROM edgeai.stream_run_binding WHERE run_id=?",String.class,run).stream().findFirst();
    }
    public void freeze(UUID run,String digest,Instant now) {
        jdbc.update("INSERT INTO edgeai.stream_run_binding(run_id,route_digest,created_at) VALUES (?,?,?)",run,digest,Timestamp.from(now));
    }
    public Optional<TaskCompletion> task(UUID attempt) {
        return jdbc.query("SELECT * FROM edgeai.stream_task_completion WHERE attempt_id=?",
            (r,n)->new TaskCompletion(r.getObject("attempt_id",UUID.class),r.getObject("checkpoint_id",UUID.class),instant(r,"created_at"),instant(r,"granted_at")),attempt).stream().findFirst();
    }
    public Optional<DeviceCompletion> device(UUID generation) {
        return jdbc.query("SELECT * FROM edgeai.stream_device_completion WHERE generation_id=?",
            (r,n)->new DeviceCompletion(r.getObject("generation_id",UUID.class),r.getLong("sequence"),instant(r,"created_at"),instant(r,"granted_at")),generation).stream().findFirst();
    }
    public void recordTask(UUID attempt,UUID checkpoint,Instant now) {
        jdbc.update("INSERT INTO edgeai.stream_task_completion(attempt_id,checkpoint_id,created_at) VALUES (?,?,?)",attempt,checkpoint,Timestamp.from(now));
    }
    public void recordDevice(UUID generation,long sequence,Instant now) {
        jdbc.update("INSERT INTO edgeai.stream_device_completion(generation_id,sequence,created_at) VALUES (?,?,?)",generation,sequence,Timestamp.from(now));
    }
    public void grant(List<UUID> attempts,List<UUID> generations,Instant now) {
        for(var id:attempts)jdbc.update("UPDATE edgeai.stream_task_completion SET granted_at=? WHERE attempt_id=? AND granted_at IS NULL",Timestamp.from(now),id);
        for(var id:generations)jdbc.update("UPDATE edgeai.stream_device_completion SET granted_at=? WHERE generation_id=? AND granted_at IS NULL",Timestamp.from(now),id);
    }
}
