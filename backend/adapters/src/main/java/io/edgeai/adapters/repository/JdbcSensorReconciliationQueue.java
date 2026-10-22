package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.SensorReconciliationQueue;
import io.edgeai.domain.repository.SensorTelemetryRepository.Observation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcSensorReconciliationQueue implements SensorReconciliationQueue {
    private final JdbcTemplate jdbc;
    public JdbcSensorReconciliationQueue(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean enqueue(Observation o,String series,long sequence,Instant now,long limit) {
        // A retried delivery finds its own row and is accepted even when the queue is full.
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM edgeai.sensor_archive_reconciliation WHERE device_uid=? AND property=? AND series=? AND sequence=?)",Boolean.class,o.deviceUid(),o.property(),series,sequence))return true;
        return jdbc.update("INSERT INTO edgeai.sensor_archive_reconciliation(device_uid,property,series,sequence,observed_at,document,next_attempt_at) SELECT ?,?,?,?,?,?,? WHERE (SELECT count(*) FROM edgeai.sensor_archive_reconciliation)<? ON CONFLICT DO NOTHING",
            o.deviceUid(),o.property(),series,sequence,Timestamp.from(o.observedAt()),o.documentJson(),Timestamp.from(now),limit)==1;
    }
    public List<Entry> due(Instant now,int limit) {
        return jdbc.query("SELECT * FROM edgeai.sensor_archive_reconciliation WHERE next_attempt_at<=? ORDER BY next_attempt_at LIMIT ?",(r,row)->new Entry(
            new Observation(r.getObject("device_uid",UUID.class),r.getString("property"),r.getTimestamp("observed_at").toInstant(),r.getString("document")),r.getString("series"),r.getLong("sequence"),r.getInt("attempts")),Timestamp.from(now),limit);
    }
    public void complete(Entry e){jdbc.update("DELETE FROM edgeai.sensor_archive_reconciliation WHERE device_uid=? AND property=? AND series=? AND sequence=?",e.observation().deviceUid(),e.observation().property(),e.series(),e.sequence());}
    public void retry(Entry e,Instant next){jdbc.update("UPDATE edgeai.sensor_archive_reconciliation SET attempts=attempts+1,next_attempt_at=? WHERE device_uid=? AND property=? AND series=? AND sequence=?",Timestamp.from(next),e.observation().deviceUid(),e.observation().property(),e.series(),e.sequence());}
    public int expire(Instant before){return jdbc.update("DELETE FROM edgeai.sensor_archive_reconciliation WHERE observed_at<?",Timestamp.from(before));}
    public long count(){return jdbc.queryForObject("SELECT count(*) FROM edgeai.sensor_archive_reconciliation",Long.class);}
}
