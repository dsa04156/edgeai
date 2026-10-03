package io.edgeai.adapters.repository;

import io.edgeai.domain.repository.StreamCheckpointRepository;
import io.edgeai.domain.storage.VerifiedArtifact;
import io.edgeai.domain.stream.StreamCheckpoint;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.*;

public final class JdbcStreamCheckpointRepository implements StreamCheckpointRepository {
    private final JdbcTemplate jdbc;
    public JdbcStreamCheckpointRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final RowMapper<StreamCheckpoint> ROW=(r,n)->{
        var array=r.getArray("generation_ids");List<UUID> ids;
        try{ids=Arrays.stream((Object[])array.getArray()).map(v->UUID.fromString(v.toString())).toList();}finally{array.free();}
        var request=new StreamCheckpoint.Request(r.getObject("previous_id",UUID.class),r.getLong("serial"),r.getString("sha256"),r.getLong("bytes"),r.getString("execution_sha256"),ids);
        return new StreamCheckpoint(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getObject("task_id",UUID.class),r.getObject("attempt_id",UUID.class),
            r.getObject("runtime_id",UUID.class),r.getLong("epoch"),r.getObject("producer_pod_uid",UUID.class),r.getObject("service_profile_version_id",UUID.class),request,
            r.getLong("state_revision"),r.getString("summary_json"),new VerifiedArtifact(r.getString("bucket"),r.getString("object_key"),r.getString("object_version"),request.sha256(),request.bytes(),StreamCheckpoint.MEDIA_TYPE),r.getTimestamp("created_at").toInstant());
    };
    public Optional<StreamCheckpoint> latest(UUID task){return jdbc.query("SELECT * FROM edgeai.stream_checkpoint WHERE task_id=? ORDER BY serial DESC LIMIT 1",ROW,task).stream().findFirst();}
    public Optional<StreamCheckpoint> byAttemptSerial(UUID attempt,long serial){return jdbc.query("SELECT * FROM edgeai.stream_checkpoint WHERE attempt_id=? AND serial=?",ROW,attempt,serial).stream().findFirst();}
    public void insert(StreamCheckpoint value){
        var q=value.request();var a=value.artifact();
        jdbc.update(connection->{
            var p=connection.prepareStatement("""
                INSERT INTO edgeai.stream_checkpoint(id,run_id,task_id,attempt_id,runtime_id,epoch,producer_pod_uid,service_profile_version_id,
                    previous_id,serial,state_revision,sha256,execution_sha256,bytes,generation_ids,summary_json,bucket,object_key,object_version,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::uuid[],?::jsonb,?,?,?,?)
                """);
            Object[] args={value.id(),value.runId(),value.taskId(),value.attemptId(),value.runtimeId(),value.epoch(),value.producerPodUid(),value.serviceProfileVersionId(),
                q.previousId(),q.serial(),value.revision(),q.sha256(),q.executionSha256(),q.bytes(),"{"+String.join(",",q.generationIds().stream().map(UUID::toString).toList())+"}",
                value.summaryJson(),a.bucket(),a.objectKey(),a.versionId(),Timestamp.from(value.createdAt())};
            for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;
        });
    }
}
