package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.StreamCompletionPublicationRepository;
import io.edgeai.domain.stream.StreamCompletionAuthority;
import io.edgeai.domain.stream.StreamCheckpointAuthority;
import io.edgeai.domain.runtime.RuntimeNames;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcStreamCompletionPublicationRepository implements StreamCompletionPublicationRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<StreamCompletionAuthority> ROW=(r,n)->new StreamCompletionAuthority(
        r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getString("namespace"),
        r.getTimestamp("granted_at").toInstant(),r.getString("document"));
    public JdbcStreamCompletionPublicationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<StreamCompletionAuthority> find(UUID id){return jdbc.query("SELECT * FROM edgeai.stream_completion_publication WHERE id=?",ROW,id).stream().findFirst();}
    public Optional<StreamCompletionAuthority> forAttempt(UUID attempt){
        var rows=jdbc.query("""
            SELECT p.* FROM edgeai.stream_completion_publication p WHERE ?=ANY(p.attempt_ids)
            OR (SELECT granted_attempt_id FROM edgeai.stream_finalization_recovery WHERE attempt_id=?)=ANY(p.attempt_ids)
            """,ROW,attempt,attempt);
        if(rows.size()>1)throw new IllegalStateException("Ambiguous original stream completion");
        return rows.stream().findFirst();
    }
    public Optional<StreamCompletionAuthority> forGeneration(UUID generation){
        var rows=jdbc.query("SELECT p.* FROM edgeai.stream_completion_publication p JOIN edgeai.route_generation g ON g.id=? WHERE g.consumer_attempt_id=ANY(p.attempt_ids)",ROW,generation);
        if(rows.size()>1)throw new IllegalStateException("Ambiguous original stream completion");
        return rows.stream().findFirst();
    }
    public Optional<Lease> lease(String namespace,UUID owner,Instant now,Duration duration){
        RuntimeNames.dns(namespace,63);Objects.requireNonNull(owner);
        if(duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(5))>0)throw new IllegalArgumentException("Invalid publication lease");
        return jdbc.query("""
            UPDATE edgeai.stream_completion_publication SET lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=?
            WHERE id=(SELECT id FROM edgeai.stream_completion_publication WHERE namespace=? AND (NOT completed OR NOT checkpoint_history_completed)
                AND available_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id,lease_owner
            """,(r,n)->new Lease(r.getObject("id",UUID.class),r.getObject("lease_owner",UUID.class)),owner,Timestamp.from(now.plus(duration)),
            Timestamp.from(now),namespace,Timestamp.from(now),Timestamp.from(now)).stream().findFirst();
    }
    public boolean finish(UUID id,UUID owner,Instant now){return jdbc.update("UPDATE edgeai.stream_completion_publication SET completed=true,checkpoint_history_completed=true,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND lease_owner=?",Timestamp.from(now),id,owner)==1;}
    public boolean defer(UUID id,UUID owner,Instant availableAt,Instant now){return jdbc.update("UPDATE edgeai.stream_completion_publication SET available_at=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND lease_owner=?",Timestamp.from(availableAt),Timestamp.from(now),id,owner)==1;}
    public List<StreamCheckpointAuthority> checkpointHistory(UUID completion){
        // A checkpoint row is immutable. Canonical UTC microseconds make its derived
        // journal independent of the PostgreSQL connection's timezone.
        return jdbc.query("""
            WITH RECURSIVE history AS (
                SELECT c.* FROM edgeai.stream_checkpoint c JOIN edgeai.stream_task_completion d ON d.checkpoint_id=c.id
                    JOIN edgeai.stream_completion_publication p ON d.attempt_id=ANY(p.attempt_ids) WHERE p.id=?
                UNION SELECT c.* FROM history h JOIN edgeai.stream_checkpoint c ON c.id=h.previous_id
            ) SELECT h.id,h.run_id,r.namespace,jsonb_build_object(
                'apiVersion','edgeai.stream.checkpoint-authority/v1','id',h.id,'runId',h.run_id,'namespace',r.namespace,
                'checkpoint',to_jsonb(h)||jsonb_build_object('created_at',to_char(h.created_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')))::text AS document
                FROM history h JOIN edgeai.runtime_instance r ON r.id=h.runtime_id ORDER BY h.task_id,h.serial
            """,(r,n)->new StreamCheckpointAuthority(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getString("namespace"),r.getString("document")),completion);
    }
}
