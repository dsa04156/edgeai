package io.edgeai.adapters.repository;

import io.edgeai.domain.device.*;
import io.edgeai.domain.repository.DeviceRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcDeviceRepository implements DeviceRepository {
    private final JdbcTemplate jdbc;
    public JdbcDeviceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Instant time(ResultSet r, String key) throws SQLException {
        var value = r.getTimestamp(key); return value == null ? null : value.toInstant();
    }
    private static final String SELECT = """
        SELECT d.*, o.status AS last_status, o.received_at AS last_seen_at FROM edgeai.device d
        LEFT JOIN edgeai.device_session s ON s.device_id=d.id AND s.closed_at IS NULL
        LEFT JOIN edgeai.device_observation o ON o.session_id=s.id AND o.sequence=s.last_sequence
        """;
    private static final RowMapper<Device> DEVICE = (r,n) -> new Device(r.getObject("id",UUID.class),r.getString("device_key"),
        r.getString("display_name"),r.getObject("profile_version_id",UUID.class),Device.SourceMode.valueOf(r.getString("source_mode")),
        Device.State.valueOf(r.getString("state")),r.getLong("revision"),r.getLong("session_epoch"),r.getString("creation_digest"),
        r.getString("last_status"),time(r,"last_seen_at"),time(r,"created_at"),time(r,"updated_at"));
    private static final RowMapper<DeviceAttachment> ATTACHMENT = (r,n) -> new DeviceAttachment(r.getObject("id",UUID.class),
        r.getObject("device_id",UUID.class),r.getObject("node_id",UUID.class),r.getString("port"),time(r,"attached_at"),time(r,"detached_at"));
    private static final RowMapper<DeviceSession> SESSION = (r,n) -> new DeviceSession(r.getObject("id",UUID.class),r.getObject("device_id",UUID.class),
        r.getObject("boot_id",UUID.class),r.getLong("epoch"),r.getLong("last_sequence"),time(r,"opened_at"),time(r,"closed_at"));
    private static final RowMapper<DeviceObservation> OBSERVATION = (r,n) -> new DeviceObservation(r.getObject("id",UUID.class),
        r.getObject("device_id",UUID.class),r.getObject("session_id",UUID.class),r.getLong("sequence"),time(r,"observed_at"),time(r,"received_at"),
        r.getString("status"),r.getString("attributes"),r.getString("digest"));
    public boolean create(String key,String name,UUID profileId,Device.SourceMode mode,String digest) {
        return jdbc.update("""
            INSERT INTO edgeai.device(id,device_key,display_name,profile_version_id,source_mode,creation_digest)
            VALUES (?,?,?,?,?,?) ON CONFLICT(device_key) DO NOTHING
            """,UUID.randomUUID(),key,name,profileId,mode.name(),digest)==1;
    }
    public Optional<Device> find(UUID id,boolean lock) {
        return jdbc.query(SELECT+" WHERE d.id=?"+(lock?" FOR UPDATE OF d":""),DEVICE,id).stream().findFirst();
    }
    public Optional<Device> findByKey(String key) { return jdbc.query(SELECT+" WHERE d.device_key=?",DEVICE,key).stream().findFirst(); }
    public List<Device> list(int limit,int offset) { return jdbc.query(SELECT+" ORDER BY d.device_key COLLATE \"C\" LIMIT ? OFFSET ?",DEVICE,limit,offset); }
    public void rename(UUID id,String name,Instant now) { jdbc.update("UPDATE edgeai.device SET display_name=?,revision=revision+1,updated_at=? WHERE id=?",name,Timestamp.from(now),id); }
    public void release(UUID id,Instant now) { jdbc.update("UPDATE edgeai.device SET state='RELEASED',revision=revision+1,updated_at=? WHERE id=?",Timestamp.from(now),id); }
    public void delete(UUID id) {
        jdbc.update("DELETE FROM edgeai.device_observation WHERE device_id=?",id);
        jdbc.update("DELETE FROM edgeai.device_session WHERE device_id=?",id);
        jdbc.update("DELETE FROM edgeai.device_attachment WHERE device_id=?",id);
        jdbc.update("DELETE FROM edgeai.device WHERE id=?",id);
    }
    public boolean hasVirtualDeviceBindings(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM edgeai.vd_source_binding b WHERE b.device_id=? AND
                (b.closed_at IS NULL OR EXISTS (SELECT 1 FROM edgeai.vd_runtime r WHERE r.vd_id=b.vd_id
                    AND r.observed_state<>'TERMINATED' AND r.configuration->'sources'->>b.source_key=b.id::text)))
            """,Boolean.class,id));
    }
    public void advanceEpoch(UUID id,Instant now) { jdbc.update("UPDATE edgeai.device SET session_epoch=session_epoch+1,updated_at=? WHERE id=?",Timestamp.from(now),id); }
    public List<DeviceAttachment> attachments(UUID id) { return jdbc.query("SELECT * FROM edgeai.device_attachment WHERE device_id=? ORDER BY attached_at DESC,id LIMIT 20",ATTACHMENT,id); }
    public Optional<DeviceAttachment> activeAttachment(UUID id) { return jdbc.query("SELECT * FROM edgeai.device_attachment WHERE device_id=? AND detached_at IS NULL",ATTACHMENT,id).stream().findFirst(); }
    public void closeAttachments(UUID id,Instant now) { jdbc.update("UPDATE edgeai.device_attachment SET detached_at=? WHERE device_id=? AND detached_at IS NULL",Timestamp.from(now),id); }
    public void attach(DeviceAttachment a) { jdbc.update("INSERT INTO edgeai.device_attachment(id,device_id,node_id,port,attached_at) VALUES (?,?,?,?,?)",a.id(),a.deviceId(),a.nodeId(),a.port(),Timestamp.from(a.attachedAt())); }
    public List<DeviceSession> sessions(UUID id) { return jdbc.query("SELECT * FROM edgeai.device_session WHERE device_id=? ORDER BY epoch DESC LIMIT 20",SESSION,id); }
    public Optional<DeviceSession> sessionByBootId(UUID id,UUID bootId) { return jdbc.query("SELECT * FROM edgeai.device_session WHERE device_id=? AND boot_id=?",SESSION,id,bootId).stream().findFirst(); }
    public Optional<DeviceSession> activeSession(UUID id) { return jdbc.query("SELECT * FROM edgeai.device_session WHERE device_id=? AND closed_at IS NULL",SESSION,id).stream().findFirst(); }
    public void closeSessions(UUID id,Instant now) { jdbc.update("UPDATE edgeai.device_session SET closed_at=? WHERE device_id=? AND closed_at IS NULL",Timestamp.from(now),id); }
    public void openSession(DeviceSession s) { jdbc.update("INSERT INTO edgeai.device_session(id,device_id,boot_id,epoch,opened_at) VALUES (?,?,?,?,?)",s.id(),s.deviceId(),s.bootId(),s.epoch(),Timestamp.from(s.openedAt())); }
    public Optional<DeviceObservation> observation(UUID id,long seq) { return jdbc.query("SELECT * FROM edgeai.device_observation WHERE session_id=? AND sequence=?",OBSERVATION,id,seq).stream().findFirst(); }
    public List<DeviceObservation> observations(UUID id) { return jdbc.query("SELECT * FROM edgeai.device_observation WHERE device_id=? ORDER BY received_at DESC,id LIMIT 20",OBSERVATION,id); }
    public void observe(DeviceObservation o) {
        jdbc.update("""
            INSERT INTO edgeai.device_observation(id,device_id,session_id,sequence,observed_at,received_at,status,attributes,digest)
            VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?)
            """,o.id(),o.deviceId(),o.sessionId(),o.sequence(),Timestamp.from(o.observedAt()),Timestamp.from(o.receivedAt()),o.status(),o.attributesJson(),o.digest());
        jdbc.update("UPDATE edgeai.device_session SET last_sequence=? WHERE id=?",o.sequence(),o.sessionId());
    }
}
