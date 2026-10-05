package io.edgeai.adapters.storage;

import io.edgeai.domain.storage.*;
import io.edgeai.domain.stream.StreamCheckpointAuthority;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.VersioningConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Preserves receipt identity/time and ancestry separately from the potentially large checkpoint payload. */
final class S3StreamCheckpointJournal {
    static final String MEDIA_TYPE="application/vnd.edgeai.stream-checkpoint-authority+json";
    private static final Set<String> FIELDS=Set.of("id","run_id","task_id","attempt_id","runtime_id","epoch",
        "producer_pod_uid","service_profile_version_id","previous_id","serial","state_revision","sha256","execution_sha256",
        "bytes","generation_ids","summary_json","bucket","object_key","object_version","created_at","handover_from_id");
    private final MinioClient client;private final String bucket;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    S3StreamCheckpointJournal(MinioClient client,String bucket){this.client=client;this.bucket=bucket;}
    void retain(StreamCheckpointAuthority authority){
        Objects.requireNonNull(authority);byte[] body=authority.documentJson().getBytes(StandardCharsets.UTF_8);
        try{
            Map<?,?> document=json.readValue(body,Map.class);
            if(!document.keySet().equals(Set.of("apiVersion","id","runId","namespace","checkpoint"))
                    || !"edgeai.stream.checkpoint-authority/v1".equals(document.get("apiVersion"))
                    || !authority.id().toString().equals(document.get("id")) || !authority.runId().toString().equals(document.get("runId"))
                    || !authority.namespace().equals(document.get("namespace")) || !(document.get("checkpoint") instanceof Map<?,?> checkpoint)
                    || !checkpoint.keySet().equals(FIELDS) || !authority.id().toString().equals(checkpoint.get("id"))
                    || !authority.runId().toString().equals(checkpoint.get("run_id")))throw new IllegalArgumentException();
        }catch(Exception error){throw new ArtifactVerificationException("Invalid original checkpoint authority");}
        try{
            if(client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build()).status()!=VersioningConfiguration.Status.ENABLED)
                throw new ArtifactVerificationException("Checkpoint authority requires versioned storage");
            String key=authority.objectKey();byte[] saved=read(key);
            if(saved==null){
                try(var input=new ByteArrayInputStream(body)){
                    try{client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(input,(long)body.length,-1L)
                        .contentType(MEDIA_TYPE).extraHeaders(Map.of("If-None-Match","*")).build());}
                    catch(ErrorResponseException error){if(!Set.of("PreconditionFailed","ConditionalRequestConflict").contains(error.errorResponse().code()))throw error;}
                }
                saved=read(key);
            }
            if(saved==null)throw new ArtifactVerificationException("Checkpoint authority is not observable");
            try{if(!json.readTree(saved).equals(json.readTree(body)))throw new IllegalArgumentException();}
            catch(Exception error){throw new ArtifactVerificationException("Checkpoint authority conflicts with original receipt");}
        }catch(ArtifactVerificationException error){throw error;}
        catch(Exception error){throw new ArtifactStoreUnavailableException();}
    }
    private byte[] read(String key)throws Exception{
        StatObjectResponse stat;
        try{stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());}
        catch(ErrorResponseException error){if(Set.of("NoSuchKey","NoSuchObject").contains(error.errorResponse().code()))return null;throw error;}
        if(stat.versionId()==null || stat.versionId().isBlank() || stat.versionId().equals("null") || !MEDIA_TYPE.equals(stat.contentType())
                || stat.size()<2 || stat.size()>StreamCheckpointAuthority.MAX_BYTES || stat.headers().get("Content-Encoding")!=null)
            throw new ArtifactVerificationException("Invalid checkpoint authority metadata");
        try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).versionId(stat.versionId()).build())){
            byte[] body=input.readNBytes(StreamCheckpointAuthority.MAX_BYTES+1);
            if(body.length!=stat.size())throw new ArtifactVerificationException("Checkpoint authority length differs");return body;
        }
    }
}
