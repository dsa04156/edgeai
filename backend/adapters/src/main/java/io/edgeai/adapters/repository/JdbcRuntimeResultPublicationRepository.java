package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.RuntimeResultPublicationRepository;
import io.edgeai.domain.runtime.RuntimeNames;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcRuntimeResultPublicationRepository implements RuntimeResultPublicationRepository {
    private final JdbcTemplate jdbc;
    public JdbcRuntimeResultPublicationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Publication> lease(String namespace,UUID owner,Instant now,Duration duration){
        RuntimeNames.dns(namespace,63);Objects.requireNonNull(owner);
        if(duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("Publication lease must be positive and at most five minutes");
        return jdbc.query("""
            UPDATE edgeai.runtime_result_publication SET lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=?
            WHERE result_id=(SELECT p.result_id FROM edgeai.runtime_result_publication p
                JOIN edgeai.runtime_instance r ON r.id=p.runtime_id
                WHERE r.namespace=? AND NOT p.completed AND p.available_at<=? AND (p.lease_until IS NULL OR p.lease_until<=?)
                ORDER BY p.available_at,p.result_id FOR UPDATE OF p SKIP LOCKED LIMIT 1)
            RETURNING result_id,runtime_id,lease_owner
            """,(r,n)->new Publication(r.getObject("result_id",UUID.class),r.getObject("runtime_id",UUID.class),r.getObject("lease_owner",UUID.class)),
            owner,Timestamp.from(now.plus(duration)),Timestamp.from(now),namespace,Timestamp.from(now),Timestamp.from(now)).stream().findFirst();
    }
    public boolean finish(UUID resultId,UUID owner,Instant now){
        return jdbc.update("UPDATE edgeai.runtime_result_publication SET completed=true,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE result_id=? AND lease_owner=?",Timestamp.from(now),resultId,owner)==1;
    }
    public boolean defer(UUID resultId,UUID owner,Instant availableAt,Instant now){
        return jdbc.update("UPDATE edgeai.runtime_result_publication SET available_at=?,lease_owner=NULL,lease_until=NULL,updated_at=? WHERE result_id=? AND lease_owner=?",Timestamp.from(availableAt),Timestamp.from(now),resultId,owner)==1;
    }
}
