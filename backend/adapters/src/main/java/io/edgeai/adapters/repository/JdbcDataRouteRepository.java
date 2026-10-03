package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.DataRouteRepository;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.RouteGeneration.Actor;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;

public final class JdbcDataRouteRepository implements DataRouteRepository {
    private final JdbcTemplate jdbc;
    public JdbcDataRouteRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Instant instant(ResultSet r,String key)throws SQLException{var value=r.getTimestamp(key);return value==null?null:value.toInstant();}
    private static Timestamp time(Instant value){return value==null?null:Timestamp.from(value);}
    private static final RowMapper<DataRoute> ROUTE=(r,n)->new DataRoute(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),
        r.getObject("source_task_id",UUID.class),r.getObject("source_device_id",UUID.class),r.getObject("source_profile_version_id",UUID.class),
        r.getString("source_mode"),r.getString("source_port"),r.getObject("consumer_task_id",UUID.class),r.getString("consumer_port"),
        r.getString("media_type"),r.getInt("max_payload_bytes"),instant(r,"created_at"));
    private static final RowMapper<RouteGeneration> GENERATION=(r,n)->new RouteGeneration(r.getObject("id",UUID.class),r.getObject("route_id",UUID.class),r.getLong("generation"),
        new Actor(r.getObject(r.getObject("producer_session_id")==null?"producer_attempt_id":"producer_session_id",UUID.class),r.getLong("producer_epoch")),
        new Actor(r.getObject("consumer_attempt_id",UUID.class),r.getLong("consumer_epoch")),r.getString("broker_digest"),r.getString("policy_digest"),r.getString("request_digest"),
        instant(r,"created_at"),instant(r,"updated_at"),instant(r,"lease_until"),instant(r,"activated_at"),instant(r,"fenced_at"),r.getString("fence_reason"),instant(r,"closed_at"));
    public Optional<DataRoute> route(UUID id,boolean lock){return jdbc.query("SELECT * FROM edgeai.data_route WHERE id=?"+(lock?" FOR UPDATE":""),ROUTE,id).stream().findFirst();}
    public Optional<DataRoute> input(UUID run,UUID task,String port){return jdbc.query("SELECT * FROM edgeai.data_route WHERE run_id=? AND consumer_task_id=? AND consumer_port=?",ROUTE,run,task,port).stream().findFirst();}
    public List<DataRoute> forRun(UUID run,int limit,int offset){return jdbc.query("SELECT * FROM edgeai.data_route WHERE run_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",ROUTE,run,limit,offset);}
    public void create(DataRoute r){jdbc.update("""
        INSERT INTO edgeai.data_route(id,run_id,source_task_id,source_device_id,source_profile_version_id,source_mode,source_port,consumer_task_id,consumer_port,media_type,max_payload_bytes,created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """,r.id(),r.runId(),r.sourceTaskId(),r.sourceDeviceId(),r.sourceProfileVersionId(),r.sourceMode(),r.sourcePort(),r.consumerTaskId(),r.consumerPort(),r.mediaType(),r.maxPayloadBytes(),time(r.createdAt()));}
    public Optional<RouteGeneration> generation(UUID id){return jdbc.query("SELECT * FROM edgeai.route_generation WHERE id=?",GENERATION,id).stream().findFirst();}
    public Optional<RouteGeneration> open(UUID route){return jdbc.query("SELECT * FROM edgeai.route_generation WHERE route_id=? AND closed_at IS NULL",GENERATION,route).stream().findFirst();}
    public long lastGeneration(UUID route){return jdbc.queryForObject("SELECT coalesce(max(generation),0) FROM edgeai.route_generation WHERE route_id=?",Long.class,route);}
    public List<RouteGeneration> history(UUID route,int limit,int offset){return jdbc.query("SELECT * FROM edgeai.route_generation WHERE route_id=? ORDER BY generation DESC LIMIT ? OFFSET ?",GENERATION,route,limit,offset);}
    public List<UUID> openGenerations(String digest,UUID after,int limit){
        return scan(digest,after,limit,false);
    }
    public List<UUID> pendingGenerations(String digest,UUID after,int limit){return scan(digest,after,limit,true);}
    private List<UUID> scan(String digest,UUID after,int limit,boolean pending){
        RouteGeneration.digest(digest);if(limit<1 || limit>256)throw new IllegalArgumentException("Invalid stream scan size");
        String condition=pending?" AND (activated_at IS NULL OR fenced_at IS NOT NULL)":"";
        return after==null
            ?jdbc.queryForList("SELECT id FROM edgeai.route_generation WHERE broker_digest=? AND closed_at IS NULL"+condition+" ORDER BY id LIMIT ?",UUID.class,digest,limit)
            :jdbc.queryForList("SELECT id FROM edgeai.route_generation WHERE broker_digest=? AND closed_at IS NULL"+condition+" AND id>? ORDER BY id LIMIT ?",UUID.class,digest,after,limit);
    }
    public void prepare(DataRoute r,RouteGeneration g){jdbc.update("""
        INSERT INTO edgeai.route_generation(id,route_id,run_id,generation,source_task_id,source_device_id,consumer_task_id,
          producer_attempt_id,producer_session_id,producer_epoch,consumer_attempt_id,consumer_epoch,broker_digest,policy_digest,request_digest,created_at,updated_at,lease_until)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,g.id(),g.routeId(),r.runId(),g.generation(),r.sourceTaskId(),r.sourceDeviceId(),r.consumerTaskId(),
        r.deviceSource()?null:g.producer().id(),r.deviceSource()?g.producer().id():null,g.producer().epoch(),g.consumer().id(),g.consumer().epoch(),
        g.brokerDigest(),g.policyDigest(),g.requestDigest(),time(g.createdAt()),time(g.updatedAt()),time(g.leaseUntil()));}
    public void activate(UUID id,Instant now){jdbc.update("UPDATE edgeai.route_generation SET activated_at=?,updated_at=? WHERE id=? AND activated_at IS NULL AND fenced_at IS NULL",time(now),time(now),id);}
    public void renew(UUID id,Instant until,Instant now){jdbc.update("UPDATE edgeai.route_generation SET lease_until=?,updated_at=? WHERE id=? AND fenced_at IS NULL",time(until),time(now),id);}
    public RouteHeartbeat heartbeat(UUID id){return jdbc.queryForObject("SELECT * FROM edgeai.route_heartbeat WHERE generation_id=?",
        (r,n)->new RouteHeartbeat(r.getObject("generation_id",UUID.class),r.getLong("window_micros"),r.getLong("producer_sequence"),r.getLong("consumer_sequence"),
            instant(r,"producer_seen"),instant(r,"consumer_seen")),id);}
    public void observe(RouteHeartbeat h){jdbc.update("UPDATE edgeai.route_heartbeat SET producer_sequence=?,consumer_sequence=?,producer_seen=?,consumer_seen=? WHERE generation_id=?",
        h.producerSequence(),h.consumerSequence(),time(h.producerSeen()),time(h.consumerSeen()),h.generationId());}
    public void fence(UUID id,String reason,Instant now){jdbc.update("UPDATE edgeai.route_generation SET fenced_at=?,fence_reason=?,updated_at=? WHERE id=? AND fenced_at IS NULL",time(now),reason,time(now),id);}
    public void closed(UUID id,Instant now){jdbc.update("UPDATE edgeai.route_generation SET closed_at=?,updated_at=? WHERE id=? AND fenced_at IS NOT NULL AND closed_at IS NULL",time(now),time(now),id);}
}
