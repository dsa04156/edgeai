package io.edgeai.adapters.storage;

import io.edgeai.domain.vd.VDTaskStartAuthority;
import io.edgeai.domain.storage.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.VersioningConfiguration;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Preserve first admission despite heartbeat renewal; revalidate current authority in the API. */
final class S3VDTaskStartJournal implements VDTaskStartJournal {
    static final String MEDIA_TYPE="application/vnd.edgeai.vd-task-start+json";
    private final MinioClient client;
    private final String bucket;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    S3VDTaskStartJournal(MinioClient client,String bucket){this.client=client;this.bucket=bucket;}
    @Override public void retainVDStart(VDTaskStartAuthority authority){
        Objects.requireNonNull(authority);
        try{
            if(client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build()).status()!=VersioningConfiguration.Status.ENABLED)
                throw new ArtifactVerificationException("VD start journal requires versioned storage");
            byte[] existing=read(authority.objectKey());
            if(existing==null){
                byte[] body=json.writeValueAsBytes(authority.document());validate(body,authority);
                try(var input=new ByteArrayInputStream(body)){
                    try{client.putObject(PutObjectArgs.builder().bucket(bucket).object(authority.objectKey()).stream(input,(long)body.length,-1L)
                        .contentType(MEDIA_TYPE).extraHeaders(Map.of("If-None-Match","*")).build());}
                    catch(ErrorResponseException error){
                        if(!Set.of("PreconditionFailed","ConditionalRequestConflict").contains(error.errorResponse().code()))throw error;
                    }
                }
                existing=read(authority.objectKey());
            }
            if(existing==null)throw new ArtifactVerificationException("VD start publication is not observable");
            validate(existing,authority);
        }catch(ArtifactVerificationException error){throw error;}
        catch(Exception error){throw new ArtifactStoreUnavailableException();}
    }
    private byte[] read(String key)throws Exception{
        StatObjectResponse stat;
        try{stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());}
        catch(ErrorResponseException error){
            if(Set.of("NoSuchKey","NoSuchObject").contains(error.errorResponse().code()))return null;throw error;
        }
        if(stat.versionId()==null || stat.versionId().equals("null") || stat.versionId().isBlank() ||
                !MEDIA_TYPE.equals(stat.contentType()) || stat.size()<2 || stat.size()>8192)
            throw new ArtifactVerificationException("Invalid VD start journal metadata");
        try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).versionId(stat.versionId()).build())){
            byte[] body=input.readNBytes(8193);
            if(body.length!=stat.size())throw new ArtifactVerificationException("VD start journal length differs");return body;
        }
    }
    @SuppressWarnings("unchecked") private void validate(byte[] body,VDTaskStartAuthority authority){
        try{
            Map<String,Object> saved=json.readValue(body,Map.class);var expected=new TreeMap<>(authority.document());
            if(!saved.keySet().equals(expected.keySet()))throw new IllegalArgumentException();
            var admitted=Instant.parse((String)saved.get("admittedAt"));
            var lease=Instant.parse((String)saved.get("supervisorLeaseUntil"));
            var drain=saved.get("supervisorDrainDeadline")==null?null:Instant.parse((String)saved.get("supervisorDrainDeadline"));
            // First lease may be longer than a later, drain-clamped heartbeat lease.
            // It remains historical evidence, never a replacement for live authorization.
            var stored=new TreeMap<>(saved);
            for(String field:List.of("admittedAt","supervisorLeaseUntil","supervisorDrainDeadline")){expected.remove(field);stored.remove(field);}
            if(!Arrays.equals(json.writeValueAsBytes(expected),json.writeValueAsBytes(stored)) ||
                    admitted.isAfter(authority.admittedAt()) || admitted.isBefore(authority.allocation().assignedAt()) ||
                    admitted.isBefore(authority.runtime().createdAt()) || admitted.isBefore(authority.supervisor().readyAt()) ||
                    !admitted.isBefore(authority.runtime().expiresAt()) || !admitted.isBefore(lease) || lease.isAfter(admitted.plusSeconds(60)) ||
                    drain!=null && (!admitted.isBefore(drain) || !drain.equals(authority.supervisor().drainDeadline())) ||
                    authority.startDeadline()!=null && !admitted.isBefore(authority.startDeadline()))throw new IllegalArgumentException();
        }catch(Exception error){throw new ArtifactVerificationException("VD start journal conflicts with original assignment, work or deadline");}
    }
}
