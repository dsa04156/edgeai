package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class JdbcProcessingHistoryQueue implements ProcessingHistoryQueue {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=new JsonMapper();
    public JdbcProcessingHistoryQueue(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void enqueue(ProcessingHistoryRepository.Sample sample) {
        var r=sample.result();
        jdbc.update("INSERT INTO edgeai.continuous_history_outbox(vd_id,output_id,generation,sequence,evaluation_at,retention_days,value_type,unit,value,sources,document,expires_at) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?) ON CONFLICT DO NOTHING",r.vdId(),r.outputId(),r.generation(),r.sequence(),Timestamp.from(r.evaluationAt()),sample.retentionDays(),sample.type(),sample.unit(),json.writeValueAsString(sample.value()),sample.sourcesJson(),r.json(),Timestamp.from(r.evaluationAt().plusSeconds(sample.retentionDays()*86400L)));
    }
    public List<ProcessingHistoryRepository.Sample> pending(int limit) {
        return jdbc.query("SELECT * FROM edgeai.continuous_history_outbox ORDER BY evaluation_at,vd_id,output_id LIMIT ? FOR UPDATE SKIP LOCKED",(r,row)->{
            var id=r.getObject("vd_id",UUID.class);var generation=r.getObject("generation",UUID.class);long sequence=r.getLong("sequence");
            var result=new ContinuousRepository.Result(id,r.getString("output_id"),generation,generation+":"+sequence,sequence,r.getTimestamp("evaluation_at").toInstant(),r.getString("document"));
            return new ProcessingHistoryRepository.Sample(result,r.getInt("retention_days"),r.getString("value_type"),r.getString("unit"),json.readValue(r.getString("value"),Object.class),r.getString("sources"));
        },limit);
    }
    public void complete(ProcessingHistoryRepository.Sample sample){var r=sample.result();jdbc.update("DELETE FROM edgeai.continuous_history_outbox WHERE vd_id=? AND output_id=? AND generation=? AND sequence=?",r.vdId(),r.outputId(),r.generation(),r.sequence());}
    public int expire(Instant now){return jdbc.update("DELETE FROM edgeai.continuous_history_outbox WHERE expires_at<=?",Timestamp.from(now));}
    public long count(){return jdbc.queryForObject("SELECT count(*) FROM edgeai.continuous_history_outbox",Long.class);}
}
