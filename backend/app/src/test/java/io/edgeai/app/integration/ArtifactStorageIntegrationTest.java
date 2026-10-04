package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.domain.storage.*;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real S3 bytes and object versions, in a unique test-owned bucket. No implicit skips. */
class ArtifactStorageIntegrationTest {
    private MinioClient admin;
    private S3ArtifactStore store;
    private String bucket;
    private boolean created;
    private final List<Map.Entry<String,String>> versions = new ArrayList<>();
    @BeforeEach void start() {
        String endpoint = required("EDGEAI_STORAGE_URL"), user = required("EDGEAI_MINIO_USER"), password = required("EDGEAI_MINIO_PASSWORD");
        bucket = "edgeai-runtime-test-" + UUID.randomUUID();
        admin = MinioClient.builder().endpoint(endpoint).credentials(user,password).region("us-east-1").build();
        checked(() -> { admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); return null; }); created = true;
        checked(() -> { admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
            .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null, null, null)).build()); return null; });
        store = new S3ArtifactStore(endpoint,endpoint,user,password,bucket,Clock.systemUTC());
    }
    @AfterEach void cleanup() {
        try {
            if (created) {
                for (var version : versions) checked(() -> { admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(version.getKey()).versionId(version.getValue()).build()); return null; });
                checked(() -> { admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build()); return null; });
            }
        } finally {
            try { if (store != null) store.close(); }
            finally { if (admin != null) checked(() -> { admin.close(); return null; }); }
        }
    }
    @Test void presignedPutAndPinnedDownloadUseVerifiedBytesAndImmutableVersion() {
        byte[] content = new byte[262144]; new Random(73).nextBytes(content);
        var expected = content(content); var grant = store.upload(expected);
        assertFalse(grant.toString().contains("X-Amz"));
        String version = upload(grant, expected.objectKey(), content);
        var verified = store.verify(expected, version);
        assertEquals(expected.bytes(), verified.bytes()); assertEquals(expected.sha256(), verified.sha256());
        var second = put(expected.objectKey(), new byte[]{1,2,3}, expected.mediaType(), expected.sha256());
        assertNotEquals(version, second);
        assertThrows(ArtifactVerificationException.class, () -> store.verify(expected, second));
        var download = store.download(verified);
        assertArrayEquals(content, transfer(HttpRequest.newBuilder(download.url()).GET().build()).body());
        assertEquals(verified, store.verify(expected, version));
        assertThrows(ArtifactVerificationException.class, () -> store.verify(expected, "null"));
        URI anonymous = URI.create(required("EDGEAI_STORAGE_URL") + "/" + bucket + "/" + expected.objectKey());
        assertEquals(403, transfer(HttpRequest.newBuilder(anonymous).GET().build()).statusCode());
    }
    @Test void forgedMetadataSameLengthCorruptionWrongTypeAndMissingVersionAreRejected() {
        byte[] data = "expected bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8); var expected = content(data);
        byte[] corrupted = data.clone(); corrupted[0] ^= 1;
        String bad = put(expected.objectKey(),corrupted,expected.mediaType(),expected.sha256());
        var error = assertThrows(ArtifactVerificationException.class, () -> store.verify(expected,bad));
        assertTrue(error.getMessage().contains("SHA-256")); assertNull(error.getCause());
        String wrongType = put(expected.objectKey(),data,"application/json",expected.sha256());
        assertThrows(ArtifactVerificationException.class, () -> store.verify(expected,wrongType));
        assertThrows(ArtifactVerificationException.class, () -> store.verify(expected,UUID.randomUUID().toString()));
        String right = put(expected.objectKey(),data,expected.mediaType(),"forged-metadata-is-ignored");
        assertEquals(expected.sha256(),store.verify(expected,right).sha256());
    }
    @Test void suspendedVersioningAndCrossBucketDownloadsFailClosed() {
        checked(() -> { admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
            .config(new VersioningConfiguration(VersioningConfiguration.Status.SUSPENDED,null,null,null)).build()); return null; });
        assertThrows(ArtifactVerificationException.class, () -> store.upload(content(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> store.download(new VerifiedArtifact("other-bucket","key","version","0".repeat(64),0,"application/json")));
    }
    @Test void signedUploadRejectsDifferentBytesBeforeResultVerification() {
        byte[] data = new byte[]{1,2,3}; var expected = content(data); var grant = store.upload(expected);
        var request = HttpRequest.newBuilder(grant.url()).timeout(Duration.ofSeconds(15)); grant.headers().forEach(request::header);
        var response = transfer(request.PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{3,2,1})).build());
        assertEquals(400,response.statusCode());
        var code = java.util.regex.Pattern.compile("<Code>([A-Za-z0-9]+)</Code>").matcher(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("XAmzContentChecksumMismatch", code.find() ? code.group(1) : "none");
    }
    @Test void streamCheckpointRestoresDeletedVolumeFromVerifiedPinnedS3Version(@org.junit.jupiter.api.io.TempDir Path directory) {
        checkpointProbe("export",directory,null);
        byte[] snapshot=checked(() -> Files.readAllBytes(directory.resolve("snapshot.json")));
        String sha=checked(() -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot)));
        var expected=new ArtifactContent(UUID.randomUUID(),UUID.randomUUID(),"stream-checkpoint",sha,snapshot.length,
                                        "application/vnd.edgeai.stream-checkpoint+json");
        String version=upload(store.upload(expected),expected.objectKey(),snapshot);
        var verified=store.verify(expected,version);
        // A later, equally long object with forged metadata cannot replace the pinned snapshot.
        byte[] corrupt=snapshot.clone();corrupt[corrupt.length/2]^=1;
        String wrong=put(expected.objectKey(),corrupt,expected.mediaType(),sha);
        assertThrows(ArtifactVerificationException.class,()->store.verify(expected,wrong));
        checked(()->{Files.delete(directory.resolve("snapshot.json"));return null;});
        var downloaded=transfer(HttpRequest.newBuilder(store.download(verified).url()).GET().build());
        assertEquals(200,downloaded.statusCode());assertArrayEquals(snapshot,downloaded.body());
        checked(()->{Files.write(directory.resolve("downloaded.json"),downloaded.body());return null;});
        checkpointProbe("restore",directory,sha);
        assertFalse(Files.exists(directory.resolve("volume")));
    }
    private void checkpointProbe(String phase,Path directory,String sha) {
        checked(()->{
            Path fixture=Path.of("src/test/fixtures/stream_checkpoint_probe.py").toAbsolutePath();
            var args=new ArrayList<>(List.of("python3",fixture.toString(),phase,directory.toString()));
            if(sha!=null)args.add(sha);
            var process=new ProcessBuilder(args).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS),"Checkpoint "+phase+" probe exceeded its 15-second limit");
                String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0,process.exitValue(),"Checkpoint probe failed; private output suppressed");
                assertEquals("STREAM_CHECKPOINT_"+phase.toUpperCase(java.util.Locale.ROOT)+"_PASS",result.strip());
            } finally { if(process.isAlive()){process.destroyForcibly();process.waitFor();} }
            return null;
        });
    }
    private ArtifactContent content(byte[] bytes) {
        String sha = checked(() -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return new ArtifactContent(UUID.randomUUID(),UUID.randomUUID(),"output",sha,bytes.length,"application/octet-stream");
    }
    private String put(String key, byte[] data, String type, String metadata) {
        String version = checked(() -> admin.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
            .data(data,data.length).contentType(type).userMetadata(Map.of("sha256",metadata)).build()).versionId());
        assertNotNull(version); versions.add(Map.entry(key,version)); return version;
    }
    private String upload(ArtifactGrant grant, String key, byte[] data) {
        var request = HttpRequest.newBuilder(grant.url()).timeout(Duration.ofSeconds(15)); grant.headers().forEach(request::header);
        var response = transfer(request.PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build());
        var code = java.util.regex.Pattern.compile("<Code>([A-Za-z0-9]+)</Code>").matcher(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200,response.statusCode(),"S3 error code: " + (code.find() ? code.group(1) : "none"));
        String version = response.headers().firstValue("x-amz-version-id").orElseThrow();
        versions.add(Map.entry(key,version)); return version;
    }
    private HttpResponse<byte[]> transfer(HttpRequest request) {
        return checked(() -> { try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            return http.send(request,HttpResponse.BodyHandlers.ofByteArray());
        } });
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Real S3 test requires " + name);
        return value;
    }
    private interface Checked<T> { T run() throws Exception; }
    private static <T> T checked(Checked<T> operation) {
        try { return operation.run(); }
        catch (Exception failure) { throw new IllegalStateException("S3 fixture operation failed: " + failure.getClass().getSimpleName()); }
    }
}
