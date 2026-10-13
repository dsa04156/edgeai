package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.ContinuousRepository;
import io.edgeai.domain.vd.VDOperation;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcContinuousRepository implements ContinuousRepository {
    private final JdbcTemplate jdbc;
    public JdbcContinuousRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Timestamp time(Instant at){return Timestamp.from(at);}
    private static Execution execution(ResultSet r,int row)throws SQLException {
        return new Execution(r.getObject("vd_id",UUID.class),r.getObject("generation",UUID.class),r.getBoolean("running"),r.getLong("registry_revision"),r.getString("state"),r.getString("reason"),r.getTimestamp("updated_at").toInstant());
    }
    private static VDOperation operation(ResultSet r,int row)throws SQLException {
        var finished=r.getTimestamp("finished_at");
        return new VDOperation(r.getObject("id",UUID.class),r.getObject("vd_id",UUID.class),r.getString("request_key"),"","CONTINUOUS_"+r.getString("action"),r.getLong("requested_revision"),"{}","",null,null,r.getString("state"),"",r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),finished==null?null:finished.toInstant());
    }
    private static Session session(ResultSet r,int row)throws SQLException {
        return new Session(r.getString("engine_id"),r.getObject("pod_uid",UUID.class),r.getObject("session_id",UUID.class),r.getLong("sequence"),r.getString("poll_digest"),r.getTimestamp("lease_expires_at").toInstant());
    }
    public Optional<Execution> find(UUID id){return jdbc.query("SELECT * FROM edgeai.continuous_execution WHERE vd_id=?",JdbcContinuousRepository::execution,id).stream().findFirst();}
    public List<Execution> desired(){return jdbc.query("SELECT * FROM edgeai.continuous_execution WHERE running OR state='STOPPING' ORDER BY vd_id",JdbcContinuousRepository::execution);}
    public Optional<VDOperation> operation(UUID id){return jdbc.query("SELECT * FROM edgeai.continuous_operation WHERE id=?",JdbcContinuousRepository::operation,id).stream().findFirst();}
    public Optional<VDOperation> operationByKey(UUID id,UUID key){return jdbc.query("SELECT * FROM edgeai.continuous_operation WHERE vd_id=? AND request_key=?",JdbcContinuousRepository::operation,id,key).stream().findFirst();}
    public void lockCapacity(){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('edgeai-continuous-capacity',0))",Object.class);}
    public int activeDevices(){return jdbc.queryForObject("SELECT count(*) FROM edgeai.continuous_execution WHERE running OR state='STOPPING'",Integer.class);}
    public int activeOutputs(){return jdbc.queryForObject("SELECT coalesce(sum(jsonb_array_length(p.spec->'continuous'->'outputs')),0)::int FROM edgeai.continuous_execution e JOIN edgeai.virtual_device v ON v.id=e.vd_id JOIN edgeai.profile_version p ON p.id=v.profile_version_id WHERE e.running OR e.state='STOPPING'",Integer.class);}
    public long estimatedHistoryBytes(){return jdbc.queryForObject("SELECT coalesce(sum(ceil((256+384*(SELECT count(*) FROM edgeai.vd_source_binding b WHERE b.vd_id=v.id AND b.closed_revision IS NULL)+512*jsonb_array_length(p.spec->'continuous'->'outputs'))*86400.0*(p.spec->'continuous'->>'retentionDays')::int/(p.spec->'continuous'->>'intervalSeconds')::int*1.5)),0)::bigint FROM edgeai.continuous_execution e JOIN edgeai.virtual_device v ON v.id=e.vd_id JOIN edgeai.profile_version p ON p.id=v.profile_version_id WHERE e.running OR e.state='STOPPING'",Long.class);}
    public VDOperation request(UUID id,UUID key,long revision,String action,Instant now) {
        UUID generation=UUID.randomUUID(),operation=UUID.randomUUID();boolean running=!action.equals("STOP");
        var previous=find(id);
        boolean stopped=!running && (previous.isEmpty() || previous.get().state().equals("STOPPED"));
        jdbc.update("UPDATE edgeai.continuous_operation SET state='SUPERSEDED',updated_at=?,finished_at=? WHERE vd_id=? AND state='PENDING'",time(now),time(now),id);
        jdbc.update("INSERT INTO edgeai.continuous_execution(vd_id,generation,running,registry_revision,state,reason,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(vd_id) DO UPDATE SET generation=excluded.generation,running=excluded.running,registry_revision=excluded.registry_revision,state=excluded.state,reason=excluded.reason,updated_at=excluded.updated_at",id,generation,running,revision,running?"STARTING":stopped?"STOPPED":"STOPPING",stopped?"STOPPED":"AWAITING_ENGINE",time(now));
        jdbc.update("INSERT INTO edgeai.continuous_operation(id,vd_id,request_key,requested_revision,action,generation,state,created_at,updated_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?)",operation,id,key,revision,action,generation,stopped?"SUCCEEDED":"PENDING",time(now),time(now),stopped?time(now):null);
        return operation(operation).orElseThrow();
    }
    public Optional<Session> session(String engineId){return jdbc.query("SELECT * FROM edgeai.processing_engine_session WHERE engine_id=?",JdbcContinuousRepository::session,engineId).stream().findFirst();}
    public Optional<Session> sessionLocked(String engineId){return jdbc.query("SELECT * FROM edgeai.processing_engine_session WHERE engine_id=? FOR UPDATE",JdbcContinuousRepository::session,engineId).stream().findFirst();}
    public Session renew(String engineId,UUID podUid,UUID sessionId,long sequence,String digest,Instant now) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"processing-engine:"+engineId);
        var previous=session(engineId);
        if(jdbc.queryForObject("SELECT count(*) FROM edgeai.processing_retired_session WHERE session_id=?",Integer.class,sessionId)>0)throw new IllegalArgumentException("Retired engine session rejected");
        boolean same=previous.isPresent() && previous.get().sessionId().equals(sessionId) && previous.get().podUid().equals(podUid);
        if(same && !previous.get().expiresAt().isAfter(now))throw new IllegalArgumentException("Expired session must rotate");
        if(same && (sequence<previous.get().sequence() || sequence>previous.get().sequence()+1 || sequence==previous.get().sequence() && !digest.equals(previous.get().digest())))throw new IllegalArgumentException("Engine poll sequence rejected");
        if(same && sequence==previous.get().sequence())return previous.get();
        if(!same && sequence!=1)throw new IllegalArgumentException("New engine session must start at sequence one");
        if(!same) {
            if(previous.isPresent() && previous.get().podUid().equals(podUid) && previous.get().expiresAt().isAfter(now))throw new IllegalArgumentException("Previous engine process lease has not expired");
            previous.ifPresent(s->jdbc.update("INSERT INTO edgeai.processing_retired_session VALUES(?,?,?) ON CONFLICT DO NOTHING",s.sessionId(),s.podUid(),time(now)));
            if(previous.isPresent()) {
                var gapAt=previous.get().expiresAt().isBefore(now)?previous.get().expiresAt():now;
                for(var execution:desired())if(execution.running())gap(execution.vdId(),execution.generation(),"ENGINE_UNAVAILABLE",true,gapAt);
            }
            jdbc.update("UPDATE edgeai.continuous_execution SET generation=gen_random_uuid(),state='STARTING',reason='ENGINE_SESSION_CHANGED',updated_at=? WHERE running",time(now));
            jdbc.update("UPDATE edgeai.continuous_operation o SET generation=e.generation,updated_at=? FROM edgeai.continuous_execution e WHERE o.vd_id=e.vd_id AND o.state='PENDING' AND e.running",time(now));
        }
        jdbc.update("INSERT INTO edgeai.processing_engine_session(engine_id,pod_uid,session_id,sequence,poll_digest,lease_expires_at,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(engine_id) DO UPDATE SET pod_uid=excluded.pod_uid,session_id=excluded.session_id,sequence=excluded.sequence,poll_digest=excluded.poll_digest,lease_expires_at=excluded.lease_expires_at,updated_at=excluded.updated_at",engineId,podUid,sessionId,sequence,digest,time(now.plusSeconds(15)),time(now));
        return session(engineId).orElseThrow();
    }
    public Execution reset(UUID id,UUID generation,String reason,Instant now) {
        jdbc.update("UPDATE edgeai.continuous_execution SET generation=?,state='WAITING_INPUT',reason=?,updated_at=? WHERE vd_id=? AND generation=? AND running",UUID.randomUUID(),reason,time(now),id,generation);
        jdbc.update("UPDATE edgeai.continuous_operation o SET generation=e.generation,updated_at=? FROM edgeai.continuous_execution e WHERE o.vd_id=e.vd_id AND e.vd_id=? AND o.state='PENDING'",time(now),id);
        return find(id).orElseThrow();
    }
    public void observe(UUID id,UUID generation,String state,String reason,Instant now) {
        int changed=jdbc.update("UPDATE edgeai.continuous_execution SET state=?,reason=?,updated_at=? WHERE vd_id=? AND generation=?",state,reason,time(now),id,generation);
        if(changed>0 && Set.of("RUNNING","WAITING_INPUT","STOPPED").contains(state))jdbc.update("UPDATE edgeai.continuous_operation SET state='SUCCEEDED',updated_at=?,finished_at=? WHERE vd_id=? AND generation=? AND state='PENDING'",time(now),time(now),id,generation);
    }
    public boolean appendLatest(Result r) {
        return jdbc.update("INSERT INTO edgeai.continuous_latest(vd_id,output_id,generation,evaluation_id,sequence,evaluation_at,result) SELECT ?,?,?,?,?,?,?::jsonb FROM edgeai.continuous_execution e WHERE e.vd_id=? AND e.generation=? AND e.running ON CONFLICT(vd_id,output_id) DO UPDATE SET generation=excluded.generation,evaluation_id=excluded.evaluation_id,sequence=excluded.sequence,evaluation_at=excluded.evaluation_at,result=excluded.result WHERE edgeai.continuous_latest.generation<>excluded.generation OR edgeai.continuous_latest.sequence<excluded.sequence",r.vdId(),r.outputId(),r.generation(),r.evaluationId(),r.sequence(),time(r.evaluationAt()),r.json(),r.vdId(),r.generation())>0;
    }
    public boolean accept(Result r,Instant now) {
        return jdbc.update("INSERT INTO edgeai.continuous_result_receipt(vd_id,output_id,generation,sequence,received_at) SELECT ?,?,?,?,? FROM edgeai.continuous_execution WHERE vd_id=? AND generation=? AND running ON CONFLICT DO NOTHING",r.vdId(),r.outputId(),r.generation(),r.sequence(),time(now),r.vdId(),r.generation())>0;
    }
    public int maintainReceipts(Instant now){return jdbc.update("WITH expired AS (SELECT ctid FROM edgeai.continuous_result_receipt WHERE received_at<? LIMIT 10000) DELETE FROM edgeai.continuous_result_receipt r USING expired e WHERE r.ctid=e.ctid",time(now.minusSeconds(60)));}
    public void markHistoryStored(UUID id,String output,UUID generation,long sequence,String json){jdbc.update("UPDATE edgeai.continuous_latest SET result=?::jsonb WHERE vd_id=? AND output_id=? AND generation=? AND sequence=?",json,id,output,generation,sequence);}
    public List<String> latest(UUID id){return jdbc.query("SELECT result::text FROM edgeai.continuous_latest WHERE vd_id=? ORDER BY output_id",(r,row)->r.getString(1),id);}
    public void gap(UUID id,UUID generation,String reason,boolean open,Instant at) {
        if(open)jdbc.update("INSERT INTO edgeai.continuous_gap(vd_id,generation,reason,started_at) VALUES(?,?,?,?) ON CONFLICT(vd_id,reason) WHERE ended_at IS NULL DO NOTHING",id,generation,reason,time(at));
        else jdbc.update("UPDATE edgeai.continuous_gap SET ended_at=greatest(started_at,?) WHERE vd_id=? AND reason=? AND ended_at IS NULL",time(at),id,reason);
    }
    public List<Map<String,Object>> gaps(UUID id,Instant from,Instant to) {
        return jdbc.query("SELECT generation,reason,started_at,ended_at FROM edgeai.continuous_gap WHERE vd_id=? AND started_at<=? AND (ended_at IS NULL OR ended_at>=?) ORDER BY started_at DESC LIMIT 1000",(r,row)->{
            var result=new TreeMap<String,Object>();result.put("generation",r.getString(1));result.put("reason",r.getString(2));result.put("from",r.getTimestamp(3).toInstant().toString());var end=r.getTimestamp(4);result.put("to",end==null?null:end.toInstant().toString());return result;
        },id,time(to),time(from));
    }
}
