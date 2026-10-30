package io.edgeai.adapters.repository;

import io.edgeai.domain.aimodel.AiModelExecution;
import io.edgeai.domain.repository.AiModelRepository;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcAiModelRepository implements AiModelRepository {
    private final JdbcTemplate jdbc;
    public JdbcAiModelRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<AiModelExecution> ROW=(r,n)->new AiModelExecution(r.getObject("id",UUID.class),r.getString("model_key"),
        r.getString("model_version"),r.getString("config_name"),r.getString("display_name"),r.getString("registry_repository"),
        r.getString("image_digest"),r.getObject("resource_profile_id",UUID.class),r.getString("measurement_status"),
        r.getObject("idempotency_key",UUID.class),r.getString("request_fingerprint"),r.getString("digest"),r.getString("document"),
        r.getTimestamp("created_at").toInstant());
    public void lock(UUID idempotencyKey,String modelKey,String modelVersion,String configName) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","edgeai-ai-model-key:"+idempotencyKey);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","edgeai-ai-model:"+modelKey+"\n"+modelVersion+"\n"+configName);
    }
    public Optional<AiModelExecution> byIdempotencyKey(UUID key) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_execution WHERE idempotency_key=?",ROW,key).stream().findFirst();
    }
    public Optional<AiModelExecution> byIdentity(String modelKey,String modelVersion,String configName) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_execution WHERE model_key=? AND model_version=? AND config_name=?",ROW,modelKey,modelVersion,configName).stream().findFirst();
    }
    public void create(AiModelExecution e) {
        jdbc.update("INSERT INTO edgeai.ai_model_execution(id,model_key,model_version,config_name,display_name,registry_repository,image_digest,resource_profile_id,measurement_status,idempotency_key,request_fingerprint,digest,document,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?)",
            e.id(),e.modelKey(),e.modelVersion(),e.configName(),e.displayName(),e.repository(),e.imageDigest(),e.resourceProfileId(),e.measurementStatus(),
            e.idempotencyKey(),e.requestFingerprint(),e.digest(),e.documentJson(),Timestamp.from(e.createdAt()));
    }
    public Optional<AiModelExecution> find(UUID id) {
        return jdbc.query("SELECT * FROM edgeai.ai_model_execution WHERE id=?",ROW,id).stream().findFirst();
    }
    public List<AiModelExecution> list(String query,int limit,int offset) {
        if(query==null||query.isEmpty())
            return jdbc.query("SELECT * FROM edgeai.ai_model_execution ORDER BY created_at DESC,id LIMIT ? OFFSET ?",ROW,limit,offset);
        // LIKE metacharacters in the user's text are matched literally.
        String pattern="%"+query.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        return jdbc.query("SELECT * FROM edgeai.ai_model_execution WHERE lower(model_key) LIKE ? ESCAPE '\\' OR lower(display_name) LIKE ? ESCAPE '\\' OR lower(model_version) LIKE ? ESCAPE '\\' OR lower(registry_repository) LIKE ? ESCAPE '\\' ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            ROW,pattern,pattern,pattern,pattern,limit,offset);
    }
}
