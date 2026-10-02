package io.edgeai.adapters.repository;

import io.edgeai.domain.device.Device;
import io.edgeai.domain.repository.VirtualDeviceRepository;
import io.edgeai.domain.vd.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcVirtualDeviceRepository implements VirtualDeviceRepository {
    private final JdbcTemplate jdbc;
    public JdbcVirtualDeviceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Instant time(ResultSet r,String field) throws SQLException {
        var value=r.getTimestamp(field);return value==null?null:value.toInstant();
    }
    private static final RowMapper<VirtualDevice> VD=(r,n)->new VirtualDevice(r.getObject("id",UUID.class),
        r.getString("vd_key"),r.getString("display_name"),r.getObject("profile_version_id",UUID.class),
        r.getObject("service_profile_version_id",UUID.class),VirtualDevice.State.valueOf(r.getString("state")),r.getLong("revision"),
        new VirtualDevice.Placement(VirtualDevice.Mode.valueOf(r.getString("placement_mode")),r.getObject("node_id",UUID.class)),
        r.getString("creation_digest"),time(r,"created_at"),time(r,"updated_at"));
    private static final RowMapper<VDSourceBinding> SOURCE=(r,n)->new VDSourceBinding(r.getObject("id",UUID.class),
        r.getObject("vd_id",UUID.class),r.getString("source_key"),r.getObject("device_id",UUID.class),
        r.getObject("device_profile_version_id",UUID.class),Device.SourceMode.valueOf(r.getString("source_mode")),
        r.getLong("opened_revision"),r.getObject("closed_revision",Long.class),time(r,"opened_at"),time(r,"closed_at"));
    public boolean create(String key,String name,UUID profileId,UUID serviceId,VirtualDevice.Placement placement,String digest) {
        return jdbc.update("""
            INSERT INTO edgeai.virtual_device(id,vd_key,display_name,profile_version_id,service_profile_version_id,placement_mode,node_id,creation_digest)
            VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(vd_key) DO NOTHING
            """,UUID.randomUUID(),key,name,profileId,serviceId,placement.mode().name(),placement.nodeId(),digest)==1;
    }
    public Optional<VirtualDevice> find(UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM edgeai.virtual_device WHERE id=?"+(lock?" FOR UPDATE":""),VD,id).stream().findFirst();
    }
    public Optional<VirtualDevice> findByKey(String key) {
        return jdbc.query("SELECT * FROM edgeai.virtual_device WHERE vd_key=?",VD,key).stream().findFirst();
    }
    public List<VirtualDevice> list(int limit,int offset) {
        return jdbc.query("SELECT * FROM edgeai.virtual_device ORDER BY vd_key COLLATE \"C\" LIMIT ? OFFSET ?",VD,limit,offset);
    }
    public void update(UUID id,String name,VirtualDevice.Placement placement,VirtualDevice.State state,Instant now) {
        jdbc.update("UPDATE edgeai.virtual_device SET display_name=?,placement_mode=?,node_id=?,state=?,revision=revision+1,updated_at=? WHERE id=?",
            name,placement.mode().name(),placement.nodeId(),state.name(),Timestamp.from(now),id);
    }
    public List<VDSourceBinding> activeSources(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.vd_source_binding WHERE vd_id=? AND closed_at IS NULL ORDER BY source_key COLLATE \"C\"",SOURCE,id);
    }
    public List<VDSourceBinding> sourceHistory(UUID id,int limit) {
        return jdbc.query("SELECT * FROM edgeai.vd_source_binding WHERE vd_id=? ORDER BY opened_revision DESC,source_key COLLATE \"C\",id LIMIT ?",SOURCE,id,limit);
    }
    public void bind(VDSourceBinding b) {
        jdbc.update("""
            INSERT INTO edgeai.vd_source_binding(id,vd_id,source_key,device_id,device_profile_version_id,source_mode,opened_revision,opened_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,b.id(),b.vdId(),b.sourceKey(),b.deviceId(),b.deviceProfileVersionId(),b.sourceMode().name(),b.openedRevision(),Timestamp.from(b.openedAt()));
    }
    public void close(UUID bindingId,long revision,Instant now) {
        jdbc.update("UPDATE edgeai.vd_source_binding SET closed_revision=?,closed_at=? WHERE id=? AND closed_at IS NULL",revision,Timestamp.from(now),bindingId);
    }
}
