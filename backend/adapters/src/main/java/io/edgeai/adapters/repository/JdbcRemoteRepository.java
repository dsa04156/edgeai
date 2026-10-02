package io.edgeai.adapters.repository;
import io.edgeai.domain.remote.*;
import io.edgeai.domain.repository.RemoteRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
public final class JdbcRemoteRepository implements RemoteRepository {
    private final JdbcTemplate jdbc;
    public JdbcRemoteRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final RowMapper<RemoteAllocation> ROW=(r,n)->new RemoteAllocation(r.getObject("id",UUID.class),r.getObject("runtime_id",UUID.class),
        new RemoteTarget(r.getString("provider_key"),r.getString("configuration_digest"),r.getString("source_mode")),r.getString("work"),r.getString("request_digest"),
        r.getLong("provider_revision"),r.getString("provider_state"),r.getString("observation"),r.getTimestamp("observed_at")==null?null:r.getTimestamp("observed_at").toInstant(),r.getTimestamp("created_at").toInstant());
    public Optional<RemoteAllocation> find(UUID id){return jdbc.query("SELECT * FROM edgeai.remote_allocation WHERE id=?",ROW,id).stream().findFirst();}
    public void create(RemoteAllocation a){jdbc.update("""
        INSERT INTO edgeai.remote_allocation(id,runtime_id,provider_key,configuration_digest,source_mode,work,request_digest,created_at)
        VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?)
        """,a.id(),a.runtimeId(),a.target().providerKey(),a.target().configurationDigest(),a.target().sourceMode(),a.workJson(),a.requestDigest(),Timestamp.from(a.createdAt()));}
    public void observe(UUID id,long revision,String state,String observation,Instant now){jdbc.update("""
        UPDATE edgeai.remote_allocation SET provider_revision=?,provider_state=?,observation=CAST(? AS jsonb),observed_at=? WHERE id=?
        """,revision,state,observation,Timestamp.from(now),id);}
}
