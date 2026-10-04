package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.ArtifactStoreUnavailableException;
import io.edgeai.domain.vd.*;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL, MVC authentication and S3; supervisor Pod/Node attestation is an explicit fixture. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.vd.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.vd.lease-seconds=60"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(VDTaskStartJournalIntegrationTest.TimeConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VDTaskStartJournalIntegrationTest {
    private static final String BUCKET="edgeai-vd-start-"+UUID.randomUUID();
    private static final Path KEY=key();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("edgeai.runtime.namespace",()->BUCKET);p.add("edgeai.runtime.key-file",KEY::toString);
        p.add("edgeai.storage.endpoint",()->required("EDGEAI_STORAGE_URL"));p.add("edgeai.storage.runner-endpoint",()->required("EDGEAI_STORAGE_URL"));
        p.add("edgeai.storage.access-key",()->required("EDGEAI_MINIO_USER"));p.add("edgeai.storage.secret-key",()->required("EDGEAI_MINIO_PASSWORD"));p.add("edgeai.storage.bucket",()->BUCKET);
    }
    static class TestClock extends Clock {
        final AtomicLong offset=new AtomicLong();public Instant instant(){return Instant.now().plusSeconds(offset.get());}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
    }
    @TestConfiguration static class TimeConfiguration{@Bean @Primary TestClock vdStartClock(){return new TestClock();}}
    @Autowired TestClock clock;@Autowired MockMvc mvc;@Autowired ProfileService profiles;@Autowired WorkflowService workflows;
    @Autowired VirtualDeviceService devices;@Autowired VDLifecycleService vdLifecycle;@Autowired VDRuntimeRepository supervisors;
    @Autowired VDTaskRepository allocations;@Autowired VDTokenService vdTokens;@Autowired RunnerTokenService tokens;
    @Autowired RuntimeRepository runtimes;@Autowired ExecutionService executions;
    @MockitoBean VDGateway gateway;@MockitoBean RuntimeGateway jobs;
    @MockitoSpyBean S3ArtifactStore storage;
    private final JsonDocuments json=new JsonDocuments();
    private final Map<UUID,VDGateway.PodIdentity> pods=new ConcurrentHashMap<>();
    private MinioClient admin;private boolean created;
    private record Execution(UUID vd,VDRuntime supervisor,UUID session,UUID run,List<RuntimeInstance> work){}
    @BeforeEach void setup()throws Exception{
        clock.offset.set(0);
        admin=MinioClient.builder().endpoint(required("EDGEAI_STORAGE_URL")).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();createBucket();
        when(gateway.authenticatePod(any(),any())).thenAnswer(call->{VDRuntime r=call.getArgument(0);
            if(!"vd-start-proof-fixture".equals(call.getArgument(1)) || !pods.containsKey(r.id()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return pods.get(r.id());});
    }
    @AfterEach void cleanup()throws Exception{
        try{if(created){for(var item:versions())admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.getKey()).versionId(item.getValue()).build());
            assertThat(versions()).isEmpty();admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());}}
        finally{if(admin!=null)admin.close();}
    }
    @AfterAll static void cleanupKey()throws Exception{Files.deleteIfExists(KEY);}
    @Test void twoChildrenKeepDistinctOriginalAssignmentsAndVersionsAcrossHeartbeatAndDrain()throws Exception{
        var e=execution(2);for(var r:e.work())claim(e,r,200);
        var first=versions();var documents=e.work().stream().map(r->journal(r)).toList();assertThat(first).hasSize(2);
        assertThat(documents.get(0).get("allocationId")).isNotEqualTo(documents.get(1).get("allocationId"));
        assertThat(documents.get(0).get("slot")).isNotEqualTo(documents.get(1).get("slot"));
        clock.offset.set(5);poll(e,2,e.work());
        for(int i=0;i<e.work().size();i++){
            var r=e.work().get(i);claim(e,r,200);assertThat(journal(r)).isEqualTo(documents.get(i));
            var a=allocations.byRuntime(r.id()).orElseThrow();assertThat(journal(r)).containsEntry("allocationId",a.id().toString())
                .containsEntry("sessionId",e.session().toString()).containsEntry("vdRuntimeId",e.supervisor().id().toString());
            assertThat(json.canonical(journal(r))).doesNotContain(r.claimNonce().toString(),"private-vd-work",vdTokens.issue(e.supervisor()),"X-Amz");
        }
        vdLifecycle.drain(e.vd(),0,"drain-start-test");poll(e,3,e.work());
        assertThat(supervisors.runtime(e.supervisor().id()).orElseThrow().leaseUntil()).isBefore(Instant.parse((String)documents.getFirst().get("supervisorLeaseUntil")));
        for(int i=0;i<e.work().size();i++){claim(e,e.work().get(i),200);assertThat(journal(e.work().get(i))).isEqualTo(documents.get(i));}
        assertThat(versions()).isEqualTo(first);verifyNoInteractions(jobs);
    }
    @Test void firstClaimDuringDrainRetainsTheOriginalDrainDeadline()throws Exception{
        var e=execution(1);vdLifecycle.drain(e.vd(),0,"first-start-draining");poll(e,2,e.work());var r=e.work().getFirst();
        claim(e,r,200);var document=journal(r);assertThat(document.get("supervisorDrainDeadline"))
            .isEqualTo(supervisors.runtime(e.supervisor().id()).orElseThrow().drainDeadline().toString());
        clock.offset.set(2);poll(e,3,e.work());claim(e,r,200);assertThat(journal(r)).isEqualTo(document);assertThat(versions()).hasSize(1);
    }
    @Test void lostPublicationReplyKeepsFirstRecordAndOriginalChildAttempt()throws Exception{
        var e=execution(1);var r=e.work().getFirst();
        doAnswer(call->{call.callRealMethod();throw new ArtifactStoreUnavailableException();}).doCallRealMethod().when(storage).retainVDStart(any());
        assertThat(claim(e,r,503)).doesNotContain("command");var first=versions();var document=journal(r);
        claim(e,r,200);assertThat(versions()).isEqualTo(first);assertThat(journal(r)).isEqualTo(document);
        assertThat(executions.taskDetail(r.taskId()).attempts()).hasSize(1);assertThat(runtimes.result(r.taskId())).isEmpty();
    }
    @Test void actualStorageOutageDoesNotReturnExecutableWorkAndCanRecover()throws Exception{
        var e=execution(1);var r=e.work().getFirst();admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());created=false;
        assertThat(claim(e,r,503)).doesNotContain("command");createBucket();claim(e,r,200);assertThat(versions()).hasSize(1);
    }
    @Test void cancellationDuringStoragePersistencePreventsTheResponse()throws Exception{
        var e=execution(1);var r=e.work().getFirst();
        doAnswer(call->{call.callRealMethod();executions.cancelTask(r.taskId(),"{}");return null;}).when(storage).retainVDStart(any());
        assertThat(claim(e,r,409)).doesNotContain("command");assertThat(versions()).hasSize(1);assertThat(runtimes.result(r.taskId())).isEmpty();
    }
    @Test void leaseExpiryDuringStoragePersistencePreventsTheResponseAndCannotBeRenewed()throws Exception{
        var e=execution(1);var r=e.work().getFirst();
        doAnswer(call->{call.callRealMethod();clock.offset.set(61);return null;}).when(storage).retainVDStart(any());
        assertThat(claim(e,r,409)).doesNotContain("command");var first=versions();claim(e,r,409);assertThat(versions()).isEqualTo(first);
        assertThat(runtimes.result(r.taskId())).isEmpty();
    }
    @Test void concurrentFirstClaimsRetainOneActualVersion()throws Exception{
        var e=execution(1);var r=e.work().getFirst();
        try(var pool=Executors.newFixedThreadPool(4)){
            var latch=new CountDownLatch(1);var tasks=IntStream.range(0,4).mapToObj(i->pool.submit(()->{latch.await();return claim(e,r,200);})).toList();
            latch.countDown();for(var task:tasks)assertThat(task.get(20,TimeUnit.SECONDS)).contains("command");
        }
        assertThat(versions()).hasSize(1);assertThat(executions.taskDetail(r.taskId()).attempts()).hasSize(1);
    }
    @Test void malformedOrDifferentStoredAssignmentIsNeverOverwritten()throws Exception{
        var e=execution(1);var r=e.work().getFirst();claim(e,r,200);var original=journal(r);var originalVersion=versions().getFirst().getValue();
        for(String field:List.of("sessionId","allocationId","configurationDigest","workDigest","supervisorLeaseUntil")){
            var altered=new TreeMap<>(original);altered.put(field,field.equals("supervisorLeaseUntil")?"2100-01-01T00:00:00Z":"altered");
            put(r,json.canonical(altered),"application/vnd.edgeai.vd-task-start+json");var before=versions();
            assertThat(claim(e,r,400)).contains("ARTIFACT_INVALID").doesNotContain("command");assertThat(versions()).isEqualTo(before);removeNewVersions(r,originalVersion);
        }
        String canonical=json.canonical(original);
        for(String raw:List.of(canonical.substring(0,canonical.length()-1)+",\"epoch\":1}",canonical+" {}")){
            put(r,raw,"application/vnd.edgeai.vd-task-start+json");var before=versions();claim(e,r,400);assertThat(versions()).isEqualTo(before);removeNewVersions(r,originalVersion);
        }
        put(r,canonical,"text/plain");var before=versions();claim(e,r,400);assertThat(versions()).isEqualTo(before);removeNewVersions(r,originalVersion);
        claim(e,r,200);assertThat(journal(r)).isEqualTo(original);
    }
    @SuppressWarnings("unchecked") private Execution execution(int count)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));spec.put("inputs",Map.of());
        UUID sp=publish(ProfileIdentity.Kind.SERVICE,spec),vp=publish(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","emulation",
            "serviceProfileVersionId",sp.toString(),"sources",Map.of(),"state",Map.of("mode","STATELESS"),
            "runtime",Map.of("maxConcurrentTasks",2,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30)));
        var vd=devices.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","Original VD start","profileVersionId",vp.toString(),"sources",List.of(),"placement",Map.of("mode","AUTO")))).value();
        var operation=vdLifecycle.provision(vd.id(),0,"provision",new RuntimeSettings(BUCKET,"edgeai-runner",URI.create("http://fixture.invalid"),120),false);
        var vr=vdLifecycle.submitted(operation.targetRuntimeId(),UUID.randomUUID());var session=UUID.randomUUID();
        pods.put(vr.id(),new VDGateway.PodIdentity(vr.podUid(),UUID.randomUUID(),"vd-journal-node",true));
        var e=new Execution(vd.id(),vr,session,null,List.of());poll(e,0,List.of());
        var workflow=workflows.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","VD admission"))).value();
        var tasks=IntStream.range(0,count).mapToObj(i->Map.of("key","task"+i,"serviceProfileVersionId",sp.toString(),"parameters",Map.of("private-vd-work",9007199254740993L))).toList();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",tasks,"dependencies",List.of()))).value();
        var run=executions.create(UUID.randomUUID().toString(),json.canonical(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","VD","vdId",vd.id().toString()),"parameters",Map.of()))).value();
        poll(e,1,List.of());var work=executions.detail(run.id()).tasks().stream().map(t->runtimes.byAttempt(executions.taskDetail(t.id()).attempts().getFirst().id()).orElseThrow()).toList();
        assertThat(work).hasSize(count);for(var r:work)assertThat(allocations.byRuntime(r.id())).isPresent();
        return new Execution(vd.id(),vr,session,run.id(),work);
    }
    private UUID publish(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    private void poll(Execution e,long sequence,List<RuntimeInstance> active)throws Exception{
        mvc.perform(post("/internal/v1/vd-runtimes/"+e.supervisor().id()+"/poll").header("Authorization","Bearer "+vdTokens.issue(e.supervisor()))
            .header("X-EdgeAI-Pod-Token","vd-start-proof-fixture").contentType("application/json").content(json.canonical(Map.of(
                "vdId",e.vd().toString(),"runtimeId",e.supervisor().id().toString(),"generation",e.supervisor().generation(),"podUid",e.supervisor().podUid().toString(),
                "sessionId",e.session().toString(),"sequence",sequence,"state","RUNNING","active",active.stream().map(r->Map.of("attemptId",r.attemptId().toString(),"epoch",r.epoch())).toList(),"completed",List.of()))))
            .andExpect(status().isOk());
    }
    private String claim(Execution e,RuntimeInstance r,int expected)throws Exception{
        return mvc.perform(post("/internal/v1/attempts/"+r.attemptId()+"/claim").header("Authorization","Bearer "+tokens.issue(r))
            .header("X-EdgeAI-Pod-Token","vd-start-proof-fixture").contentType("application/json").content(json.canonical(Map.of("epoch",r.epoch(),"podUid",e.supervisor().podUid().toString()))))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private String key(RuntimeInstance r){return "authority/vd-task-start/"+r.id()+".json";}
    @SuppressWarnings("unchecked") private Map<String,Object> journal(RuntimeInstance r){try{
        var stat=admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key(r)).build());
        try(var input=admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key(r)).versionId(stat.versionId()).build())){return (Map<String,Object>)json.decode(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
    }catch(Exception error){throw new AssertionError("Actual VD journal read failed",error);}}
    private void put(RuntimeInstance r,String body,String media)throws Exception{byte[] raw=body.getBytes(StandardCharsets.UTF_8);
        admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key(r)).data(raw,raw.length).contentType(media).build());}
    private void removeNewVersions(RuntimeInstance r,String original)throws Exception{
        for(var item:versions())if(item.getKey().equals(key(r)) && !item.getValue().equals(original))admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.getKey()).versionId(item.getValue()).build());
    }
    private List<Map.Entry<String,String>> versions()throws Exception{
        var result=new ArrayList<Map.Entry<String,String>>();for(var value:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).includeVersions(true).build())){
            var item=value.get();result.add(Map.entry(item.objectName(),item.versionId()));}return result;
    }
    private void createBucket()throws Exception{admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());created=true;
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());}
    private static Path key(){try{var path=Files.createTempFile("edgeai-vd-start-",".key");byte[] value=new byte[32];new java.security.SecureRandom().nextBytes(value);Files.writeString(path,HexFormat.of().formatHex(value));return path;}catch(Exception error){throw new IllegalStateException("Cannot create VD journal test key");}}
    private static String required(String name){return Objects.requireNonNull(System.getenv(name),"Actual VD journal test requires "+name);}
}
