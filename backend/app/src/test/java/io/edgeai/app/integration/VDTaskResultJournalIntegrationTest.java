package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.vd.*;
import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VDTaskResultJournalIntegrationTest {
    private static final String BUCKET="edgeai-vd-result-"+UUID.randomUUID();
    private static final Path KEY=key();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("edgeai.runtime.namespace",()->BUCKET);p.add("edgeai.runtime.key-file",KEY::toString);
        p.add("edgeai.storage.endpoint",()->required("EDGEAI_STORAGE_URL"));p.add("edgeai.storage.runner-endpoint",()->required("EDGEAI_STORAGE_URL"));
        p.add("edgeai.storage.access-key",()->required("EDGEAI_MINIO_USER"));p.add("edgeai.storage.secret-key",()->required("EDGEAI_MINIO_PASSWORD"));p.add("edgeai.storage.bucket",()->BUCKET);
    }
    @Autowired MockMvc mvc;@Autowired ProfileService profiles;@Autowired WorkflowService workflows;
    @Autowired VirtualDeviceService devices;@Autowired VDLifecycleService vdLifecycle;@Autowired VDRuntimeRepository supervisors;
    @Autowired VDTaskRepository allocations;@Autowired VDTokenService vdTokens;@Autowired RunnerTokenService tokens;
    @Autowired RuntimeRepository runtimes;@Autowired ExecutionService executions;
    @Autowired RuntimeLifecycleService lifecycle;@Autowired RuntimeResultPublisher publisher;
    @Autowired RuntimeResultPublicationRepository publications;@Autowired RuntimeSettings settings;
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;
    @MockitoBean VDGateway gateway;@MockitoBean RuntimeGateway jobs;
    @MockitoSpyBean S3ArtifactStore storage;
    private final JsonDocuments json=new JsonDocuments();
    private final Map<UUID,VDGateway.PodIdentity> pods=new ConcurrentHashMap<>();
    private MinioClient admin;private boolean created;
    private record Execution(UUID vd,VDRuntime supervisor,UUID session,UUID run,List<RuntimeInstance> work){}
    @BeforeEach void setup()throws Exception{
        admin=MinioClient.builder().endpoint(required("EDGEAI_STORAGE_URL")).credentials(required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD")).region("us-east-1").build();createBucket();
        when(gateway.authenticatePod(any(),any())).thenAnswer(call->{VDRuntime r=call.getArgument(0);
            if(!"vd-result-proof-fixture".equals(call.getArgument(1)) || !pods.containsKey(r.id()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return pods.get(r.id());});
        jdbc.update("UPDATE edgeai.runtime_result_publication SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id IN (SELECT id FROM edgeai.runtime_instance WHERE namespace=?)",BUCKET);
    }
    @AfterEach void cleanup()throws Exception{
        try{if(created){for(var item:versions())admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.getKey()).versionId(item.getValue()).build());
            assertThat(versions()).isEmpty();admin.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());}}
        finally{if(admin!=null)admin.close();}
    }
    @AfterAll static void cleanupKey()throws Exception{Files.deleteIfExists(KEY);}
    @Test void distinctChildrenKeepAcceptedResultAndOriginalAssignmentOnReplay()throws Exception{
        var e=execution(2);
        for(var r:e.work()){
            claim(e,r,200);var manifest=output(r);commit(e,r,manifest,201);
            var result=runtimes.result(r.taskId()).orElseThrow();var document=journal(r);var first=resultVersions(r);
            var a=allocations.byRuntime(r.id()).orElseThrow();
            assertThat(document).containsEntry("resultId",result.id().toString()).containsEntry("committedAt",result.createdAt().toString())
                .containsEntry("allocationId",a.id().toString()).containsEntry("vdRuntimeId",e.supervisor().id().toString())
                .containsEntry("sessionId",e.session().toString()).containsEntry("startKey","authority/vd-task-start/"+r.id()+".json");
            assertThat(((Number)document.get("slot")).intValue()).isEqualTo(a.slot());
            assertThat(((Map<?,?>)((List<?>)document.get("outputs")).getFirst()).get("versionId")).isEqualTo(result.outputs().getFirst().artifact().versionId());
            assertThat(json.canonical(document)).doesNotContain(r.claimNonce().toString(),"private-vd-work",vdTokens.issue(e.supervisor()),"X-Amz");
            commit(e,r,manifest,200);assertThat(journal(r)).isEqualTo(document);assertThat(resultVersions(r)).isEqualTo(first).hasSize(1);
            assertThat(worker(publisher,Instant.now()).publishOne()).isTrue();assertThat(completed(r)).isTrue();
            assertThat(executions.taskDetail(r.taskId()).attempts()).hasSize(1);
        }
        assertThat(journal(e.work().get(0)).get("allocationId")).isNotEqualTo(journal(e.work().get(1)).get("allocationId"));
        assertThat(journal(e.work().get(0)).get("resultId")).isNotEqualTo(journal(e.work().get(1)).get("resultId"));
        verify(storage,never()).retainResult(any());verifyNoInteractions(jobs);
    }
    @Test void lostS3ResponsePreservesAcceptedIdentityAndOriginalVersion()throws Exception{
        var e=execution(1);var r=e.work().getFirst();claim(e,r,200);var manifest=output(r);
        doAnswer(call->{call.callRealMethod();throw new ArtifactStoreUnavailableException();}).doCallRealMethod().when(storage).retainVDResult(any());
        assertThat(commit(e,r,manifest,503)).contains("RUNTIME_UNAVAILABLE");var result=runtimes.result(r.taskId()).orElseThrow();var first=resultVersions(r);
        assertThat(completed(r)).isFalse();commit(e,r,manifest,200);
        assertThat(runtimes.result(r.taskId()).orElseThrow()).isEqualTo(result);assertThat(resultVersions(r)).isEqualTo(first).hasSize(1);
        assertThat(journal(r).get("committedAt")).isEqualTo(result.createdAt().toString());
    }
    @Test void unavailableStorageAndTerminatedSupervisorRecoverThroughNewWorkerAndExpiredLease()throws Exception{
        var e=execution(1);var r=e.work().getFirst();claim(e,r,200);var manifest=output(r);
        int port;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        String origin="http://127.0.0.1:"+port;
        try(var unavailable=new S3ArtifactStore(origin,origin,required("EDGEAI_MINIO_USER"),required("EDGEAI_MINIO_PASSWORD"),BUCKET,Clock.systemUTC())){
            doAnswer(call->{unavailable.retainVDResult(call.getArgument(0));return null;}).when(storage).retainVDResult(any());
            commit(e,r,manifest,503);assertThat(resultVersions(r)).isEmpty();assertThat(completed(r)).isFalse();
            assertThat(worker(new RuntimeResultPublisher(runtimes,unavailable,allocations,supervisors,unavailable),Instant.now()).publishOne()).isTrue();
        }
        var result=runtimes.result(r.taskId()).orElseThrow();pods.remove(e.supervisor().id());vdLifecycle.fail(e.supervisor().id(),"RUNTIME_LOST");
        // Pod creation/absence are explicit fixtures; physical Kubernetes is covered by the external gate.
        jdbc.update("UPDATE edgeai.vd_runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id=? AND kind='CREATE'",e.supervisor().id());
        assertThat(vdLifecycle.confirmStopped(e.supervisor().id()).terminal()).isTrue();commit(e,r,manifest,401);
        assertThat(allocations.byRuntime(r.id()).orElseThrow().closedAt()).isNotNull();assertThat(completed(r)).isFalse();
        Instant now=Instant.now().plusSeconds(10);var old=publications.lease(BUCKET,UUID.randomUUID(),now,Duration.ofSeconds(1)).orElseThrow();
        assertThat(publications.lease(BUCKET,UUID.randomUUID(),now,Duration.ofSeconds(1))).isEmpty();
        doCallRealMethod().when(storage).retainVDResult(any());
        assertThat(worker(new RuntimeResultPublisher(runtimes,storage,allocations,supervisors,storage),now.plusSeconds(2)).publishOne()).isTrue();
        assertThat(completed(r)).isTrue();assertThat(publications.finish(old.resultId(),old.leaseOwner(),now.plusSeconds(2))).isFalse();
        assertThat(journal(r).get("resultId")).isEqualTo(result.id().toString());assertThat(resultVersions(r)).hasSize(1);
        assertThat(runtimes.result(r.taskId()).orElseThrow()).isEqualTo(result);assertThat(executions.taskDetail(r.taskId()).task().state()).isEqualTo("SUCCEEDED");
    }
    @Test void cancellationDuringArtifactVerificationNeverCreatesResultOrPublication()throws Exception{
        var e=execution(1);var r=e.work().getFirst();claim(e,r,200);var manifest=output(r);
        doAnswer(call->{var value=call.callRealMethod();executions.cancelTask(r.taskId(),"{}");return value;}).when(storage).verify(any(),any());
        commit(e,r,manifest,409);assertThat(runtimes.result(r.taskId())).isEmpty();assertThat(publicationCount(r)).isZero();assertThat(resultVersions(r)).isEmpty();
        verify(storage,never()).retainVDResult(any());
    }
    @Test void transactionRollbackRemovesResultAndOutboxAndForbidsEarlyPublication()throws Exception{
        var e=execution(1);var r=e.work().getFirst();claim(e,r,200);var manifest=output(r);var o=manifest.outputs().getFirst();
        var artifact=storage.verify(o.content(r.taskId(),r.attemptId()),o.versionId());
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            var permit=lifecycle.prepareCommit(r.attemptId(),r.epoch(),e.supervisor().podUid(),manifest);
            lifecycle.commitVerified(permit,List.of(new TaskResult.Output(o.port(),artifact)));assertThat(publicationCount(r)).isEqualTo(1);
            assertThatThrownBy(()->publisher.publish(r.id())).isInstanceOf(IllegalStateException.class);status.setRollbackOnly();
        });
        assertThat(runtimes.result(r.taskId())).isEmpty();assertThat(publicationCount(r)).isZero();assertThat(resultVersions(r)).isEmpty();
        verify(storage,never()).retainVDResult(any());assertThat(executions.taskDetail(r.taskId()).task().state()).isEqualTo("RUNNING");
    }
    @Test void conflictingStoredAssignmentIsNeverOverwrittenOrAcknowledged()throws Exception{
        var e=execution(1);var r=e.work().getFirst();claim(e,r,200);var manifest=output(r);commit(e,r,manifest,201);
        var result=runtimes.result(r.taskId()).orElseThrow();var original=journal(r);
        for(String field:List.of("allocationId","sessionId","vdRuntimeId","configurationDigest","manifestDigest","startKey")){
            var altered=new TreeMap<>(original);altered.put(field,"different");put(r,json.canonical(altered),"application/vnd.edgeai.vd-task-result+json");
            var before=resultVersions(r);assertThat(commit(e,r,manifest,400)).contains("ARTIFACT_INVALID");assertThat(resultVersions(r)).isEqualTo(before);
        }
        assertThat(worker(publisher,Instant.now()).publishOne()).isTrue();assertThat(completed(r)).isFalse();
        assertThat(runtimes.result(r.taskId()).orElseThrow()).isEqualTo(result);assertThat(executions.taskDetail(r.taskId()).task().state()).isEqualTo("SUCCEEDED");
    }
    @Test void concurrentPublishersWriteOnlyOneResultVersion()throws Exception{
        var e=execution(1);var r=e.work().getFirst();claim(e,r,200);var manifest=output(r);
        doThrow(new ArtifactStoreUnavailableException()).when(storage).retainVDResult(any());commit(e,r,manifest,503);doCallRealMethod().when(storage).retainVDResult(any());
        try(var pool=Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<Future<TaskResult>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->publisher.publish(r.id())));
            for(var future:futures)assertThat(future.get(20,TimeUnit.SECONDS).id()).isEqualTo(runtimes.result(r.taskId()).orElseThrow().id());
        }
        assertThat(resultVersions(r)).hasSize(1);
    }
    @Test void migrationBackfillsLegacyResultsWithoutInventingStartsOrChangingCommands()throws Exception{
        var e=execution(2);var r=e.work().getFirst();var peer=e.work().getLast();
        for(var runtime:e.work()){claim(e,runtime,200);commit(e,runtime,output(runtime),201);}
        while(worker(publisher,Instant.now()).publishOne()){}
        var original=runtimes.result(r.taskId()).orElseThrow();String commands=commands();
        String other=jdbc.queryForObject("SELECT to_jsonb(p)::text FROM edgeai.runtime_result_publication p WHERE runtime_id=?",String.class,peer.id());
        // Simulate an accepted pre-journal VD result. Never fabricate a historical start admission.
        for(var item:versions())if(item.getKey().equals(key(r)) || item.getKey().equals("authority/vd-task-start/"+r.id()+".json"))
            admin.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.getKey()).versionId(item.getValue()).build());
        jdbc.update("DELETE FROM edgeai.runtime_result_publication WHERE runtime_id=?",r.id());
        String migration=new org.springframework.core.io.ClassPathResource("db/migration/V36__vd_result_publication.sql").getContentAsString(StandardCharsets.UTF_8);
        new TransactionTemplate(transactions).executeWithoutResult(status->{jdbc.execute(migration);jdbc.execute(migration);
            assertThat(publicationCount(r)).isEqualTo(1);assertThat(completed(r)).isFalse();
            assertThat(jdbc.queryForObject("SELECT to_jsonb(p)::text FROM edgeai.runtime_result_publication p WHERE runtime_id=?",String.class,peer.id())).isEqualTo(other);
            assertThat(runtimes.result(r.taskId()).orElseThrow()).isEqualTo(original);assertThat(commands()).isEqualTo(commands);
        });
        assertThat(worker(publisher,Instant.now()).publishOne()).isTrue();assertThat(resultVersions(r)).hasSize(1);
        assertThat(versions().stream().filter(v->v.getKey().equals("authority/vd-task-start/"+r.id()+".json"))).isEmpty();
        assertThat(journal(r).get("resultId")).isEqualTo(original.id().toString());assertThat(commands()).isEqualTo(commands);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_result_publication p JOIN edgeai.task_result r ON r.id=p.result_id WHERE NOT r.committed OR r.remote_allocation_id IS NOT NULL",Integer.class)).isZero();
    }
    @Test void duplicateUnknownTrailingFieldsAndWrongMediaAreRejected()throws Exception{
        var e=execution(1);var r=e.work().getFirst();claim(e,r,200);var manifest=output(r);commit(e,r,manifest,201);String original=json.canonical(journal(r));
        for(String body:List.of(original.replaceFirst("\\{","{\"epoch\":1,"),original.substring(0,original.length()-1)+",\"unexpected\":true}",original+" {}")){
            put(r,body,"application/vnd.edgeai.vd-task-result+json");var before=resultVersions(r);commit(e,r,manifest,400);assertThat(resultVersions(r)).isEqualTo(before);
        }
        put(r,original,"application/json");var before=resultVersions(r);commit(e,r,manifest,400);assertThat(resultVersions(r)).isEqualTo(before);
    }
    private ResultManifest output(RuntimeInstance r)throws Exception{
        byte[] bytes="{\"score\":8}".getBytes(StandardCharsets.UTF_8);String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var content=new ArtifactContent(r.taskId(),r.attemptId(),"output",sha,bytes.length,"application/json");
        String version=admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(content.objectKey()).data(bytes,bytes.length).contentType(content.mediaType()).build()).versionId();
        return new ResultManifest(List.of(new ResultManifest.Output(content.port(),content.bytes(),sha,content.mediaType(),version)));
    }
    private String commit(Execution e,RuntimeInstance r,ResultManifest manifest,int expected)throws Exception{
        return mvc.perform(post("/internal/v1/attempts/"+r.attemptId()+"/commit").header("Authorization","Bearer "+tokens.issue(r))
            .header("X-EdgeAI-Pod-Token","vd-result-proof-fixture").contentType("application/json").content(json.canonical(Map.of("epoch",r.epoch(),"podUid",e.supervisor().podUid().toString(),
                "outputs",manifest.outputs().stream().map(o->Map.of("port",o.port(),"bytes",o.bytes(),"sha256",o.sha256(),"mediaType",o.mediaType(),"versionId",o.versionId())).toList()))))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private RuntimeResultPublicationWorker worker(RuntimeResultPublisher p,Instant now){return new RuntimeResultPublicationWorker(publications,p,settings,Clock.fixed(now,ZoneOffset.UTC));}
    private int publicationCount(RuntimeInstance r){return jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_result_publication WHERE runtime_id=?",Integer.class,r.id());}
    private boolean completed(RuntimeInstance r){return jdbc.queryForObject("SELECT completed FROM edgeai.runtime_result_publication WHERE runtime_id=?",Boolean.class,r.id());}
    private List<Map.Entry<String,String>> resultVersions(RuntimeInstance r)throws Exception{return versions().stream().filter(v->v.getKey().equals(key(r))).toList();}
    private String commands(){return jdbc.queryForObject("SELECT jsonb_build_object('runtime',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM edgeai.runtime_command c),'vd',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM edgeai.vd_runtime_command c))::text",String.class);}
    @SuppressWarnings("unchecked") private Execution execution(int count)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));spec.put("inputs",Map.of());
        UUID sp=publish(ProfileIdentity.Kind.SERVICE,spec),vp=publish(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","emulation",
            "serviceProfileVersionId",sp.toString(),"sources",Map.of(),"state",Map.of("mode","STATELESS"),
            "runtime",Map.of("maxConcurrentTasks",2,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30)));
        var vd=devices.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","VD accepted result","profileVersionId",vp.toString(),"sources",List.of(),"placement",Map.of("mode","AUTO")))).value();
        var operation=vdLifecycle.provision(vd.id(),0,"provision",new RuntimeSettings(BUCKET,"edgeai-runner",URI.create("http://fixture.invalid"),120),false);
        var vr=vdLifecycle.submitted(operation.targetRuntimeId(),UUID.randomUUID());var session=UUID.randomUUID();
        pods.put(vr.id(),new VDGateway.PodIdentity(vr.podUid(),UUID.randomUUID(),"vd-journal-node",true));
        var e=new Execution(vd.id(),vr,session,null,List.of());poll(e,0,List.of());
        var workflow=workflows.create(json.canonical(Map.of("key",BUCKET+UUID.randomUUID(),"displayName","VD result publication"))).value();
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
            .header("X-EdgeAI-Pod-Token","vd-result-proof-fixture").contentType("application/json").content(json.canonical(Map.of(
                "vdId",e.vd().toString(),"runtimeId",e.supervisor().id().toString(),"generation",e.supervisor().generation(),"podUid",e.supervisor().podUid().toString(),
                "sessionId",e.session().toString(),"sequence",sequence,"state","RUNNING","active",active.stream().map(r->Map.of("attemptId",r.attemptId().toString(),"epoch",r.epoch())).toList(),"completed",List.of()))))
            .andExpect(status().isOk());
    }
    private String claim(Execution e,RuntimeInstance r,int expected)throws Exception{
        return mvc.perform(post("/internal/v1/attempts/"+r.attemptId()+"/claim").header("Authorization","Bearer "+tokens.issue(r))
            .header("X-EdgeAI-Pod-Token","vd-result-proof-fixture").contentType("application/json").content(json.canonical(Map.of("epoch",r.epoch(),"podUid",e.supervisor().podUid().toString()))))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private String key(RuntimeInstance r){return "authority/vd-task-result/"+r.id()+".json";}
    @SuppressWarnings("unchecked") private Map<String,Object> journal(RuntimeInstance r){try{
        var stat=admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key(r)).build());
        try(var input=admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key(r)).versionId(stat.versionId()).build())){return (Map<String,Object>)json.decode(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
    }catch(Exception error){throw new AssertionError("Actual VD journal read failed",error);}}
    private void put(RuntimeInstance r,String body,String media)throws Exception{byte[] raw=body.getBytes(StandardCharsets.UTF_8);
        admin.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key(r)).data(raw,raw.length).contentType(media).build());}
    private List<Map.Entry<String,String>> versions()throws Exception{
        var result=new ArrayList<Map.Entry<String,String>>();for(var value:admin.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).includeVersions(true).build())){
            var item=value.get();result.add(Map.entry(item.objectName(),item.versionId()));}return result;
    }
    private void createBucket()throws Exception{admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());created=true;
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null,null,null)).build());}
    private static Path key(){try{var path=Files.createTempFile("edgeai-vd-result-",".key");byte[] value=new byte[32];new java.security.SecureRandom().nextBytes(value);Files.writeString(path,HexFormat.of().formatHex(value));return path;}catch(Exception error){throw new IllegalStateException("Cannot create VD journal test key");}}
    private static String required(String name){return Objects.requireNonNull(System.getenv(name),"Actual VD journal test requires "+name);}
}
