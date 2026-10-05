package io.edgeai.adapters.storage;

import io.edgeai.domain.storage.*;
import io.edgeai.domain.stream.StreamCompletionAuthority;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.VersioningConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class S3StreamCompletionJournal implements StreamCompletionJournal {
    static final String MEDIA_TYPE="application/vnd.edgeai.stream-completion+json";
    private final MinioClient client;private final String bucket;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    S3StreamCompletionJournal(MinioClient client,String bucket){this.client=client;this.bucket=bucket;}
    public void retainCompletion(StreamCompletionAuthority authority){
        Objects.requireNonNull(authority);
        byte[] body=authority.documentJson().getBytes(StandardCharsets.UTF_8);
        try{
            Map<?,?> document=json.readValue(body,Map.class);
            if(!document.keySet().equals(Set.of("apiVersion","id","runId","namespace","brokerDigest","routeDigest",
                    "taskIds","attemptIds","grantedAt","taskCompletions","deviceCompletions","checkpoints","generations","routes","producers"))
                    || !"edgeai.stream.completion/v1".equals(document.get("apiVersion"))
                    || !authority.id().toString().equals(document.get("id")) || !authority.runId().toString().equals(document.get("runId"))
                    || !authority.namespace().equals(document.get("namespace"))
                    || !authority.grantedAt().equals(Instant.parse((String)document.get("grantedAt"))))throw new IllegalArgumentException();
        }catch(Exception error){throw new ArtifactVerificationException("Invalid original stream completion authority");}
        try{
            if(client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build()).status()!=VersioningConfiguration.Status.ENABLED)
                throw new ArtifactVerificationException("Stream completion journal requires versioned storage");
            String key=authority.objectKey();byte[] saved=read(key);
            if(saved==null){
                try(var input=new ByteArrayInputStream(body)){
                    try{client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(input,(long)body.length,-1L)
                        .contentType(MEDIA_TYPE).extraHeaders(Map.of("If-None-Match","*")).build());}
                    catch(ErrorResponseException error){if(!Set.of("PreconditionFailed","ConditionalRequestConflict").contains(error.errorResponse().code()))throw error;}
                }
                saved=read(key);
            }
            if(saved==null)throw new ArtifactVerificationException("Stream completion publication is not observable");
            try{if(!json.readTree(saved).equals(json.readTree(body)))throw new IllegalArgumentException();}
            catch(Exception error){throw new ArtifactVerificationException("Stream completion journal conflicts with original grant");}
        }catch(ArtifactVerificationException error){throw error;}
        catch(Exception error){throw new ArtifactStoreUnavailableException();}
    }
    private byte[] read(String key)throws Exception{
        StatObjectResponse stat;
        try{stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());}
        catch(ErrorResponseException error){if(Set.of("NoSuchKey","NoSuchObject").contains(error.errorResponse().code()))return null;throw error;}
        if(stat.versionId()==null || stat.versionId().isBlank() || stat.versionId().equals("null") || !MEDIA_TYPE.equals(stat.contentType())
                || stat.size()<2 || stat.size()>StreamCompletionAuthority.MAX_BYTES || stat.headers().get("Content-Encoding")!=null)
            throw new ArtifactVerificationException("Invalid stream completion object metadata");
        try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).versionId(stat.versionId()).build())){
            byte[] body=input.readNBytes(StreamCompletionAuthority.MAX_BYTES+1);
            if(body.length!=stat.size())throw new ArtifactVerificationException("Stream completion object length differs");return body;
        }
    }
}
