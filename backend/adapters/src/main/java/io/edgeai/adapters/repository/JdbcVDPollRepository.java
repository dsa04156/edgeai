package io.edgeai.adapters.repository;
import io.edgeai.domain.repository.VDPollRepository;
import io.edgeai.domain.vd.VDPoll;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcVDPollRepository implements VDPollRepository {
    private final JdbcTemplate jdbc;
    public JdbcVDPollRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<VDPoll> find(UUID runtimeId){return jdbc.query("SELECT * FROM edgeai.vd_runtime_poll WHERE runtime_id=?",
        (r,n)->new VDPoll(r.getObject("runtime_id",UUID.class),r.getObject("session_id",UUID.class),r.getLong("sequence"),r.getString("request_digest"),r.getString("command"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant()),runtimeId).stream().findFirst();}
    public void save(VDPoll p){
        int count=jdbc.update("UPDATE edgeai.vd_runtime_poll SET session_id=?,sequence=?,request_digest=?,command=?,updated_at=? WHERE runtime_id=?",
            p.sessionId(),p.sequence(),p.requestDigest(),p.command(),Timestamp.from(p.updatedAt()),p.runtimeId());
        if(count==0)jdbc.update("INSERT INTO edgeai.vd_runtime_poll(runtime_id,session_id,sequence,request_digest,command,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
            p.runtimeId(),p.sessionId(),p.sequence(),p.requestDigest(),p.command(),Timestamp.from(p.createdAt()),Timestamp.from(p.updatedAt()));
    }
}
