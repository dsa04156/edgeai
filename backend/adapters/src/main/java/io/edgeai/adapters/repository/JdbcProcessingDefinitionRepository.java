package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.ProcessingDefinitionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class JdbcProcessingDefinitionRepository implements ProcessingDefinitionRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=new JsonMapper();
    public JdbcProcessingDefinitionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void append(UUID id,long revision,UUID profile,String configuration,String sources,String rules,Instant at) {
        jdbc.update("INSERT INTO edgeai.continuous_definition_version(vd_id,revision,profile_version_id,configuration,sources,rules,recorded_at) VALUES(?,?,?,?::jsonb,?::jsonb,?::jsonb,?) ON CONFLICT(vd_id,revision) DO NOTHING",id,revision,profile,configuration,sources,rules,Timestamp.from(at));
    }
    public List<Map<String,Object>> versions(UUID id,int limit,int offset) {
        return jdbc.query("SELECT * FROM edgeai.continuous_definition_version WHERE vd_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",(r,row)->Map.of("revision",r.getLong("revision"),"profileVersionId",r.getObject("profile_version_id",UUID.class).toString(),"configuration",json.readValue(r.getString("configuration"),Object.class),"sources",json.readValue(r.getString("sources"),Object.class),"rules",json.readValue(r.getString("rules"),Object.class),"recordedAt",r.getTimestamp("recorded_at").toInstant().toString()),id,limit,offset);
    }
}
