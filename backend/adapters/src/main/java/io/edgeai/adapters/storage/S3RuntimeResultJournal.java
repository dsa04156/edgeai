package io.edgeai.adapters.storage;

import io.edgeai.domain.runtime.RuntimeResultAuthority;
import io.edgeai.domain.storage.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.VersioningConfiguration;
import java.io.ByteArrayInputStream;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class S3RuntimeResultJournal implements RuntimeResultJournal {
    static final String MEDIA_TYPE="application/vnd.edgeai.runtime-result+json";
    private static final int MAX_BYTES=1048576;
    private final MinioClient client;
    private final String bucket;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    S3RuntimeResultJournal(MinioClient client,String bucket){this.client=client;this.bucket=bucket;}
    @Override public void retainResult(RuntimeResultAuthority authority){
        Objects.requireNonNull(authority);
        try{
            if(client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build()).status()!=VersioningConfiguration.Status.ENABLED)
                throw new ArtifactVerificationException("Result journal requires versioned storage");
            byte[] body=json.writeValueAsBytes(authority.document());
            if(body.length>MAX_BYTES)throw new ArtifactVerificationException("Result journal exceeds size limit");
            byte[] saved=read(authority.objectKey());
            if(saved==null){
                try(var input=new ByteArrayInputStream(body)){
                    try{
                        client.putObject(PutObjectArgs.builder().bucket(bucket).object(authority.objectKey()).stream(input,(long)body.length,-1L)
                            .contentType(MEDIA_TYPE).extraHeaders(Map.of("If-None-Match","*")).build());
                    }catch(ErrorResponseException error){
                        if(!Set.of("PreconditionFailed","ConditionalRequestConflict").contains(error.errorResponse().code()))throw error;
                    }
                }
                saved=read(authority.objectKey());
            }
            if(saved==null)throw new ArtifactVerificationException("Result journal publication is not observable");
            try{
                if(!json.readTree(saved).equals(json.readTree(body)))throw new IllegalArgumentException();
            }catch(Exception error){throw new ArtifactVerificationException("Result journal conflicts with committed Result");}
        }catch(ArtifactVerificationException error){throw error;}
        catch(Exception error){throw new ArtifactStoreUnavailableException();}
    }
    private byte[] read(String key)throws Exception{
        StatObjectResponse stat;
        try{stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());}
        catch(ErrorResponseException error){
            if(Set.of("NoSuchKey","NoSuchObject").contains(error.errorResponse().code()))return null;
            throw error;
        }
        if(stat.versionId()==null || stat.versionId().equals("null") || stat.versionId().isBlank() ||
                !MEDIA_TYPE.equals(stat.contentType()) || stat.size()<2 || stat.size()>MAX_BYTES ||
                stat.headers().get("Content-Encoding")!=null)
            throw new ArtifactVerificationException("Invalid Result journal object metadata");
        try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).versionId(stat.versionId()).build())){
            byte[] body=input.readNBytes(MAX_BYTES+1);
            if(body.length!=stat.size())throw new ArtifactVerificationException("Result journal object length differs");
            return body;
        }
    }
}
