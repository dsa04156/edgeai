package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.ArtifactVerificationException;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual conditional S3 writes and pinned reads. Every object belongs to a unique test bucket. */
class RuntimeStartStorageIntegrationTest {
    private static final String TYPE="application/vnd.edgeai.runtime-start+json";
    private final JsonDocuments json=new JsonDocuments();
    private final String bucket="edgeai-start-"+UUID.randomUUID();
    private MinioClient admin;
    private S3ArtifactStore store;
    private boolean created;
    @BeforeEach void setup()throws Exception{
        String endpoint=required("EDGEAI_STORAGE_URL"),user=required("EDGEAI_MINIO_USER"),password=required("EDGEAI_MINIO_PASSWORD");
        admin=MinioClient.builder().endpoint(endpoint).credentials(user,password).region("us-east-1").build();
        admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());created=true;
        versioning(VersioningConfiguration.Status.ENABLED);
        store=new S3ArtifactStore(endpoint,endpoint,user,password,bucket,Clock.systemUTC());
    }
    @AfterEach void cleanup()throws Exception{
        try{if(created){
            var values=versions("");
            for(var value:values)admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(value.getKey()).versionId(value.getValue()).build());
            assertTrue(versions("").isEmpty());admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
        }}finally{try{if(store!=null)store.close();}finally{if(admin!=null)admin.close();}}
    }
    @Test void retryPreservesFirstVersionAndAdmissionEvenAfterOriginalStartDeadline()throws Exception{
        var first=authority();store.retainStart(first);var original=versions(first.objectKey());byte[] content=read(first.objectKey());
        var retry=new RuntimeStartAuthority(first.runtime(),first.workDigest(),first.offloadId(),first.startDeadline(),first.startDeadline().plusSeconds(1));
        store.retainStart(retry);
        assertEquals(original,versions(first.objectKey()));assertEquals(1,original.size());assertArrayEquals(content,read(first.objectKey()));
        var document=(Map<?,?>)json.decode(new String(content,StandardCharsets.UTF_8));
        assertEquals(first.admittedAt().toString(),document.get("admittedAt"));
        assertFalse(new String(content,StandardCharsets.UTF_8).contains(first.runtime().claimNonce().toString()));
        assertFalse(document.containsKey("parameters"));assertFalse(document.containsKey("token"));
    }
    @Test void concurrentMatchingClaimsCreateExactlyOneVersion()throws Exception{
        var first=authority();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(12)){
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<12;i++)tasks.add(pool.submit(()->{gate.await();store.retainStart(first);return null;}));
            gate.countDown();for(var task:tasks)task.get(30,TimeUnit.SECONDS);
        }
        assertEquals(1,versions(first.objectKey()).size());
        assertEquals(json.canonical(first.document()),json.canonical(json.decode(new String(read(first.objectKey()),StandardCharsets.UTF_8))));
    }
    @Test void competingWorkCannotBothAcquireTheSameRuntimeRecord()throws Exception{
        var first=authority();var other=new RuntimeStartAuthority(first.runtime(),"sha256:"+"b".repeat(64),first.offloadId(),first.startDeadline(),first.admittedAt());
        var gate=new CountDownLatch(1);var winners=new ArrayList<RuntimeStartAuthority>();
        try(var pool=Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<Future<RuntimeStartAuthority>>();
            for(var candidate:List.of(first,other))tasks.add(pool.submit(()->{gate.await();try{store.retainStart(candidate);return candidate;}
                catch(ArtifactVerificationException expected){return null;}}));
            gate.countDown();for(var task:tasks){var winner=task.get(30,TimeUnit.SECONDS);if(winner!=null)winners.add(winner);}
        }
        assertEquals(1,winners.size());assertEquals(1,versions(first.objectKey()).size());
        assertEquals(winners.getFirst().workDigest(),((Map<?,?>)json.decode(new String(read(first.objectKey()),StandardCharsets.UTF_8))).get("workDigest"));
    }
    @Test void alteredIdentityWorkDeadlineAndAdmissionAreRejectedWithoutRewrite()throws Exception{
        for(String field:List.of("apiVersion","runtimeId","runId","taskId","attemptId","epoch","namespace","jobName","jobUid","podUid","nodeUid","nodeName","workDigest","expiresAt","offloadId","startDeadline","admittedAt","extra")){
            var first=authority();var changed=new TreeMap<>(first.document());
            changed.put(field,field.equals("epoch")?2:field.equals("admittedAt")?first.admittedAt().plusSeconds(1).toString():"changed");
            put(first.objectKey(),json.canonical(changed),TYPE);var before=versions(first.objectKey());
            assertThrows(ArtifactVerificationException.class,()->store.retainStart(first),field);assertEquals(before,versions(first.objectKey()),field);
        }
        var first=authority();var changed=new TreeMap<>(first.document());changed.remove("offloadId");
        put(first.objectKey(),json.canonical(changed),TYPE);assertThrows(ArtifactVerificationException.class,()->store.retainStart(first));
    }
    @Test void malformedOrUnversionedJournalIsNotRepairedByAnotherClaim()throws Exception{
        for(int i=0;i<5;i++){
            var first=authority();String body=json.canonical(first.document()),type=TYPE;
            switch(i){case 0->body=body+" {}";case 1->body="{\"epoch\":1,"+body.substring(1);case 2->body="[]";case 3->type="application/json";case 4->body="x".repeat(8193);}
            put(first.objectKey(),body,type);var before=versions(first.objectKey());
            assertThrows(ArtifactVerificationException.class,()->store.retainStart(first));assertEquals(before,versions(first.objectKey()));
        }
        var first=authority();store.retainStart(first);versioning(VersioningConfiguration.Status.SUSPENDED);
        assertThrows(ArtifactVerificationException.class,()->store.retainStart(first));assertEquals(1,versions(first.objectKey()).size());
    }
    @Test void firstAdmissionOutsideOriginalLeaseOrStartDeadlineDoesNotPublish()throws Exception{
        for(int mode=0;mode<3;mode++){
            var first=authority();Instant time=switch(mode){case 0->first.runtime().createdAt().minusNanos(1);case 1->first.startDeadline();default->first.runtime().expiresAt();};
            var late=new RuntimeStartAuthority(first.runtime(),first.workDigest(),first.offloadId(),first.startDeadline(),time);
            assertThrows(ArtifactVerificationException.class,()->store.retainStart(late));assertTrue(versions(first.objectKey()).isEmpty());
        }
    }
    private RuntimeStartAuthority authority(){
        var now=Instant.now();var runtime=new RuntimeInstance(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,
            "start-test","job-"+UUID.randomUUID(),UUID.randomUUID(),"RUNNING","RUNNING",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"test-node",
            now.plusSeconds(300),null,now.minusSeconds(10),now);
        return new RuntimeStartAuthority(runtime,"sha256:"+"a".repeat(64),UUID.randomUUID(),now.plusSeconds(60),now);
    }
    private List<Map.Entry<String,String>> versions(String key)throws Exception{
        var values=new ArrayList<Map.Entry<String,String>>();
        for(var result:admin.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(key).recursive(true).includeVersions(true).build())){
            var value=result.get();assertNotNull(value.versionId());values.add(Map.entry(value.objectName(),value.versionId()));}
        return values;
    }
    private byte[] read(String key)throws Exception{
        String version=versions(key).getFirst().getValue();
        try(var input=admin.getObject(GetObjectArgs.builder().bucket(bucket).object(key).versionId(version).build())){return input.readAllBytes();}
    }
    private void put(String key,String content,String type)throws Exception{
        byte[] data=content.getBytes(StandardCharsets.UTF_8);admin.putObject(PutObjectArgs.builder().bucket(bucket).object(key).data(data,data.length).contentType(type).build());
    }
    private void versioning(VersioningConfiguration.Status status)throws Exception{
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket).config(new VersioningConfiguration(status,null,null,null)).build());
    }
    private static String required(String name){return Objects.requireNonNull(System.getenv(name),"Actual storage test requires "+name);}
}
