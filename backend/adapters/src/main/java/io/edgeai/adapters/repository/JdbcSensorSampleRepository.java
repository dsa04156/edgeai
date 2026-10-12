package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.SensorSampleRepository;
import io.edgeai.domain.vd.VDSourceBinding;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcSensorSampleRepository implements SensorSampleRepository {
    private final JdbcTemplate jdbc;
    private final JdbcVirtualDeviceRepository vds;
    public JdbcSensorSampleRepository(JdbcTemplate jdbc){this.jdbc=jdbc;this.vds=new JdbcVirtualDeviceRepository(jdbc);}
    public void append(UUID uid,String property,String configuration,Sample sample) {
        jdbc.update("INSERT INTO edgeai.sensor_sample(device_uid,property_name,observed_at,value_json,configuration_sha256) VALUES (?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",uid,property,Timestamp.from(sample.observedAt()),sample.valueJson(),configuration);
    }
    public List<Sample> recent(UUID uid,String property,String configuration,Instant earliest,Instant latest,int limit) {
        if(limit<1 || limit>1024)throw new IllegalArgumentException("Invalid sample count");
        var samples=jdbc.query("SELECT observed_at,value_json::text FROM edgeai.sensor_sample WHERE device_uid=? AND property_name=? AND configuration_sha256=? AND observed_at>=? AND observed_at<=? ORDER BY observed_at DESC LIMIT ?",
            (r,n)->new Sample(r.getTimestamp(1).toInstant(),r.getString(2)),uid,property,configuration,Timestamp.from(earliest),Timestamp.from(latest),limit);
        return samples.reversed();
    }
    public List<VDSourceBinding> watchedSources() {
        var ids=jdbc.query("SELECT vd.id FROM edgeai.virtual_device vd JOIN edgeai.profile_version p ON p.id=vd.profile_version_id WHERE vd.state='REGISTERED' AND p.spec->'processing'->>'sampleCount' ~ '^[0-9]+$' AND (p.spec->'processing'->>'sampleCount')::numeric>1",(r,n)->r.getObject(1,UUID.class));
        return ids.stream().flatMap(id->vds.activeSources(id).stream()).filter(b->b.managedDeviceId()!=null).toList();
    }
}
