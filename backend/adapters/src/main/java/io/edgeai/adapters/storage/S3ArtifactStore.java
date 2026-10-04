package io.edgeai.adapters.storage;

import io.edgeai.domain.storage.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.Http.Method;
import io.minio.messages.VersioningConfiguration;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import okhttp3.OkHttpClient;

/** Fixed-bucket, version-bound artifact verification; never trusts ETag or user SHA metadata. */
public final class S3ArtifactStore implements ArtifactStore, ArtifactFiles, RuntimeStartJournal, RuntimeResultJournal, AutoCloseable {
    private static final int EXPIRY_SECONDS = 600;
    private final MinioClient client, signer;
    private final String bucket;
    private final Clock clock;
    private final S3RuntimeStartJournal starts;
    private final S3RuntimeResultJournal results;
    public S3ArtifactStore(String endpoint, String runnerEndpoint, String accessKey, String secretKey, String bucket, Clock clock) {
        validateEndpoint(endpoint); validateEndpoint(runnerEndpoint);
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) throw new IllegalArgumentException("Invalid artifact bucket");
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) throw new IllegalArgumentException("Storage credentials required");
        this.bucket = bucket; this.clock = Objects.requireNonNull(clock);
        var http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(15))
            .writeTimeout(Duration.ofSeconds(15)).callTimeout(Duration.ofSeconds(60)).followRedirects(false).followSslRedirects(false).build();
        client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).region("us-east-1").httpClient(http, true).build();
        signer = MinioClient.builder().endpoint(runnerEndpoint).credentials(accessKey, secretKey).region("us-east-1").build();
        starts=new S3RuntimeStartJournal(client,bucket);
        results=new S3RuntimeResultJournal(client,bucket);
    }
    private static void validateEndpoint(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().isEmpty() || !(uri.getScheme().equals("https") || uri.getScheme().equals("http")))
            throw new IllegalArgumentException("Storage endpoint must be an HTTP(S) origin without credentials");
    }
    @Override public void retainStart(io.edgeai.domain.runtime.RuntimeStartAuthority authority){starts.retainStart(authority);}
    @Override public void retainResult(io.edgeai.domain.runtime.RuntimeResultAuthority authority){results.retainResult(authority);}
    @Override public ArtifactGrant upload(ArtifactContent expected) {
        try {
            if (client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build()).status() != VersioningConfiguration.Status.ENABLED)
                throw new ArtifactVerificationException("Artifact bucket versioning must be enabled");
            var headers = Map.of("Content-Type", expected.mediaType(), "x-amz-meta-sha256", expected.sha256(),
                "x-amz-checksum-sha256", Base64.getEncoder().encodeToString(HexFormat.of().parseHex(expected.sha256())));
            String url = signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT).bucket(bucket)
                .object(expected.objectKey()).extraHeaders(headers).expiry(EXPIRY_SECONDS).build());
            return new ArtifactGrant(URI.create(url), headers, clock.instant().plusSeconds(EXPIRY_SECONDS));
        } catch (ArtifactVerificationException e) { throw e; }
        catch (Exception e) { throw sanitized(e); }
    }
    @Override public VerifiedArtifact verify(ArtifactContent expected, String versionId) {
        validateVersion(versionId);
        try {
            var metadata = client.statObject(StatObjectArgs.builder().bucket(bucket).object(expected.objectKey()).versionId(versionId).build());
            if (!versionId.equals(metadata.versionId()) || metadata.size() != expected.bytes() || !expected.mediaType().equals(metadata.contentType()))
                throw new ArtifactVerificationException("Artifact version, length or media type differs from commit");
            var digest = MessageDigest.getInstance("SHA-256"); long length = 0;
            try (var body = client.getObject(GetObjectArgs.builder().bucket(bucket).object(expected.objectKey()).versionId(versionId).build())) {
                byte[] buffer = new byte[65536]; int size;
                while ((size = body.read(buffer)) != -1) {
                    length += size;
                    if (length > expected.bytes()) throw new ArtifactVerificationException("Artifact exceeds committed length");
                    digest.update(buffer, 0, size);
                }
            }
            if (length != expected.bytes() || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(expected.sha256())))
                throw new ArtifactVerificationException("Artifact content SHA-256 or length differs from commit");
            return new VerifiedArtifact(bucket, expected.objectKey(), versionId, expected.sha256(), length, expected.mediaType());
        } catch (ArtifactVerificationException e) { throw e; }
        catch (Exception e) { throw sanitized(e); }
    }
    @Override public ArtifactGrant download(VerifiedArtifact artifact) {
        if (!bucket.equals(artifact.bucket())) throw new IllegalArgumentException("Artifact belongs to another bucket");
        validateVersion(artifact.versionId());
        try {
            String url = signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(bucket)
                .object(artifact.objectKey()).versionId(artifact.versionId()).expiry(EXPIRY_SECONDS).build());
            return new ArtifactGrant(URI.create(url), Map.of(), clock.instant().plusSeconds(EXPIRY_SECONDS));
        } catch (Exception e) { throw sanitized(e); }
    }
    @Override public void downloadFile(VerifiedArtifact artifact,Path destination) {
        if(!bucket.equals(artifact.bucket()))throw new IllegalArgumentException("Artifact belongs to another bucket");
        validateVersion(artifact.versionId());Path temporary=null;
        try {
            var stat=client.statObject(StatObjectArgs.builder().bucket(bucket).object(artifact.objectKey()).versionId(artifact.versionId()).build());
            if(!artifact.versionId().equals(stat.versionId()) || artifact.bytes()!=stat.size() || !artifact.mediaType().equals(stat.contentType()))
                throw new ArtifactVerificationException("Pinned input metadata differs");
            temporary=Files.createTempFile(destination.toAbsolutePath().getParent(),".edgeai-input-",".part",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(artifact.objectKey()).versionId(artifact.versionId()).build());var output=Files.newOutputStream(temporary)) {
                transfer(input,output,artifact.bytes(),artifact.sha256());
            }
            Files.createLink(destination,temporary); // Atomic publication without overwriting an existing file.
        } catch(ArtifactVerificationException e){throw e;}
        catch(Exception e){throw sanitized(e);}
        finally { if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException e){throw new ArtifactStoreUnavailableException();} }
    }
    @Override public String uploadFile(ArtifactContent content,Path source) {
        try {
            if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS) || Files.size(source)!=content.bytes())throw new ArtifactVerificationException("Output must be a regular file of the declared size");
            try(var input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)){transfer(input,OutputStream.nullOutputStream(),content.bytes(),content.sha256());}
            if(client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build()).status()!=VersioningConfiguration.Status.ENABLED)
                throw new ArtifactVerificationException("Artifact bucket versioning must be enabled");
            try(var input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)) {
                String version=client.putObject(PutObjectArgs.builder().bucket(bucket).object(content.objectKey()).stream(input,content.bytes(),-1L)
                    .contentType(content.mediaType()).userMetadata(Map.of("sha256",content.sha256())).build()).versionId();
                validateVersion(version);return version;
            }
        } catch(ArtifactVerificationException e){throw e;}
        catch(Exception e){throw sanitized(e);}
    }
    private static void transfer(InputStream input,OutputStream output,long expected,String sha) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");long length=0;byte[] buffer=new byte[65536];int size;
        while((size=input.read(buffer))!=-1){length+=size;if(length>expected)throw new ArtifactVerificationException("File exceeds declared bytes");digest.update(buffer,0,size);output.write(buffer,0,size);}
        if(length!=expected || !MessageDigest.isEqual(digest.digest(),HexFormat.of().parseHex(sha)))throw new ArtifactVerificationException("File digest or length differs");
    }
    private static void validateVersion(String value) {
        if (value == null || value.isBlank() || value.equals("null") || value.length() > 1024 || value.chars().anyMatch(Character::isISOControl))
            throw new ArtifactVerificationException("An explicit immutable object version is required");
    }
    private static RuntimeException sanitized(Exception failure) {
        if (failure instanceof ErrorResponseException response && Set.of("NoSuchKey", "NoSuchVersion", "NoSuchObject").contains(response.errorResponse().code()))
            return new ArtifactVerificationException("Artifact version does not exist");
        // SDK errors can carry signed URLs and authorization headers. Do not retain their message/cause.
        return new ArtifactStoreUnavailableException();
    }
    @Override public void close() {
        boolean failed = false;
        try { client.close(); } catch (Exception e) { failed = true; }
        try { signer.close(); } catch (Exception e) { failed = true; }
        if (failed) throw new ArtifactStoreUnavailableException();
    }
}
