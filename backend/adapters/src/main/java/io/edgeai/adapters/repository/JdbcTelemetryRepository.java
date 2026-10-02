package io.edgeai.adapters.repository;
import io.edgeai.domain.repository.TelemetryRepository;
import io.edgeai.domain.runtime.RuntimeTelemetry;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
public final class JdbcTelemetryRepository implements TelemetryRepository {
    private final JdbcTemplate jdbc;
    public JdbcTelemetryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Instant instant(ResultSet r,String column) throws SQLException {var value=r.getTimestamp(column);return value==null?null:value.toInstant();}
    private static final RowMapper<RuntimeTelemetry> ROW=(r,n)->new RuntimeTelemetry(r.getObject("attempt_id",UUID.class),r.getLong("sequence"),instant(r,"observed_at"),instant(r,"received_at"),r.getInt("interval_millis"),
        r.getObject("cpu_usage_micros",Long.class),r.getObject("cpu_limit_millicores",Long.class),r.getObject("memory_bytes",Long.class),r.getObject("memory_limit_bytes",Long.class),r.getObject("latency_micros",Long.class),instant(r,"latency_observed_at"));
    public Optional<RuntimeTelemetry> find(UUID attempt,long sequence){return jdbc.query("SELECT * FROM edgeai.runtime_telemetry WHERE attempt_id=? AND sequence=?",ROW,attempt,sequence).stream().findFirst();}
    public List<RuntimeTelemetry> recent(UUID attempt,int limit){if(limit<1 || limit>64)throw new IllegalArgumentException("Invalid telemetry limit");return jdbc.query("SELECT * FROM edgeai.runtime_telemetry WHERE attempt_id=? ORDER BY sequence DESC LIMIT ?",ROW,attempt,limit);}
    public void append(RuntimeTelemetry t) {
        jdbc.update("INSERT INTO edgeai.runtime_telemetry VALUES (?,?,?,?,?,?,?,?,?,?,?)",t.attemptId(),t.sequence(),Timestamp.from(t.observedAt()),Timestamp.from(t.receivedAt()),t.intervalMillis(),
            t.cpuUsageMicros(),t.cpuLimitMillicores(),t.memoryBytes(),t.memoryLimitBytes(),t.latencyMicros(),t.latencyObservedAt()==null?null:Timestamp.from(t.latencyObservedAt()));
        jdbc.update("DELETE FROM edgeai.runtime_telemetry WHERE attempt_id=? AND sequence NOT IN (SELECT sequence FROM edgeai.runtime_telemetry WHERE attempt_id=? ORDER BY sequence DESC LIMIT 64)",t.attemptId(),t.attemptId());
    }
}
