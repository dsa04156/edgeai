package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.config.*;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.vd.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL, Spring security/HTTP and Python supervisor. Kubernetes identity/readiness is an explicit fixture. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"edgeai.runtime.enabled=true","edgeai.vd.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.vd.lease-seconds=3"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(VDPollIntegrationTest.TimeConfiguration.class)
class VDPollIntegrationTest {
    private static final Path KEY=key();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("edgeai.runtime.key-file",KEY::toString);}
    private static Path key(){try{var p=Files.createTempFile("edgeai-vd-poll-test-",".key");byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.writeString(p,HexFormat.of().formatHex(bytes));return p;}catch(Exception e){throw new IllegalStateException("Cannot create fixture key");}}
    @AfterAll static void removeKey()throws Exception{Files.deleteIfExists(KEY);}
    static class TestClock extends Clock {
        private final AtomicLong offset=new AtomicLong();
        public Instant instant(){return Instant.now().plusSeconds(offset.get());}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}
    }
    @TestConfiguration static class TimeConfiguration{@Bean @Primary TestClock pollClock(){return new TestClock();}}
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;
    @Autowired ProfileService profiles;
    @Autowired DeviceService devices;
    @Autowired VirtualDeviceService vds;
    @Autowired VDLifecycleService lifecycle;
    @Autowired VDTokenService tokens;
    @Autowired VDPollService pollService;
    @Autowired VDPollRepository polls;
    @Autowired VDRuntimeRepository runtimes;
    @Autowired VirtualDeviceRepository vdRepository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @MockitoBean VDGateway gateway;
    @MockitoBean RuntimeGateway taskGateway;
    @MockitoBean S3ArtifactStore storage;
    @org.junit.jupiter.api.io.TempDir Path directory;
    private final JsonDocuments json=new JsonDocuments();
    private final Map<UUID,VDGateway.PodIdentity> identities=new ConcurrentHashMap<>();
    private final Map<UUID,Path> workspaces=new ConcurrentHashMap<>();
    private final AtomicBoolean unavailable=new AtomicBoolean();
    @BeforeEach void boundary() {
        clock.offset.set(0);unavailable.set(false);identities.clear();workspaces.clear();
        when(gateway.authenticatePod(any(),any())).thenAnswer(call->{
            if(unavailable.get())throw new RuntimeGatewayException(RuntimeGatewayException.Reason.UNAVAILABLE);
            VDRuntime r=call.getArgument(0);String token=call.getArgument(1);var p=identities.get(r.id());
            if(!"pod-proof-fixture".equals(token)||p==null)throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            var work=workspaces.get(r.id());return work==null?p:new VDGateway.PodIdentity(p.podUid(),p.nodeUid(),p.nodeName(),Files.exists(work.resolve(".vd-ready")));
        });
    }
    private String encode(Object v){return json.canonical(v);}
    private String keyName(){return "vd-poll-"+UUID.randomUUID();}
    private UUID publish(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,encode(Map.of("key",keyName(),"version","1.0.0","spec",spec))).version().id();}
    private record Fixture(VirtualDevice vd,VDRuntime runtime,VDOperation operation,UUID session) {}
    private Fixture fixture(boolean submitted)throws Exception {
        UUID dp=publish(ProfileIdentity.Kind.DEVICE,Map.of("protocol","fixture"));
        UUID sp=publish(ProfileIdentity.Kind.SERVICE,json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        UUID vp=publish(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","processing","serviceProfileVersionId",sp.toString(),
            "sources",Map.of("input",Map.of("deviceProfileVersionId",dp.toString(),"required",true,"sourceModes",List.of("SYNTHETIC"))),
            "state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",2,"startupTimeoutSeconds",60,"drainTimeoutSeconds",5)));
        var d=devices.create(encode(Map.of("key",keyName(),"displayName","원본","profileVersionId",dp.toString(),"sourceMode","SYNTHETIC"))).value();
        var vd=vds.create(encode(Map.of("key",keyName(),"displayName","VD poll","profileVersionId",vp.toString(),"sources",List.of(Map.of("sourceKey","input","deviceId",d.id().toString())),"placement",Map.of("mode","AUTO")))).value();
        var settings=new RuntimeSettings("vd-poll-"+UUID.randomUUID().toString().substring(0,12),"edgeai-runner",URI.create("http://127.0.0.1:"+port),120);
        var op=lifecycle.provision(vd.id(),0,keyName(),settings,false);var r=lifecycle.get(op.targetRuntimeId());
        var pod=new VDGateway.PodIdentity(UUID.randomUUID(),UUID.randomUUID(),"fixture-node",false);identities.put(r.id(),pod);
        if(submitted)r=lifecycle.submitted(r.id(),pod.podUid());return new Fixture(vd,r,op,UUID.randomUUID());
    }
    private Map<String,Object> body(Fixture f,long sequence) {return new LinkedHashMap<>(Map.of("vdId",f.vd().id().toString(),"runtimeId",f.runtime().id().toString(),"generation",1,"podUid",identities.get(f.runtime().id()).podUid().toString(),
        "sessionId",f.session().toString(),"sequence",sequence,"state","RUNNING","active",List.of(),"completed",List.of()));}
    private String path(Fixture f){return "/internal/v1/vd-runtimes/"+f.runtime().id()+"/poll";}
    private String request(Fixture f,Object body,int status)throws Exception{return request(f,encode(body).getBytes(StandardCharsets.UTF_8),status);}
    private String request(Fixture f,byte[] body,int expected)throws Exception {
        return mvc.perform(post(path(f)).header("Authorization","Bearer "+tokens.issue(f.runtime())).header("X-EdgeAI-Pod-Token","pod-proof-fixture").contentType("application/json").content(body))
            .andExpect(status().is(expected)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
    }
    private void ready(Fixture f){var p=identities.get(f.runtime().id());identities.put(f.runtime().id(),new VDGateway.PodIdentity(p.podUid(),p.nodeUid(),p.nodeName(),true));}
    @Test void readinessRequiresPollAndObservedPodAndReplayStateSurvivesServiceRecreation()throws Exception {
        var f=fixture(true);var first=body(f,0);String reply=request(f,first,200);assertThat(reply).contains("\"command\":\"RUN\"");
        assertThat(lifecycle.get(f.runtime().id()).observedState()).isEqualTo("UNREADY");assertThat(runtimes.operation(f.operation().id()).orElseThrow().state()).isEqualTo("RUNNING");
        var recreated=new VDPollService(vdRepository,polls,lifecycle,clock,3);var p=identities.get(f.runtime().id());
        var replay=new TransactionTemplate(transactions).execute(s->recreated.poll(new VDPrincipal(f.runtime().id(),f.vd().id(),1,p),encode(first).getBytes(StandardCharsets.UTF_8)));
        assertThat(encode(replay)).isEqualTo(reply);assertThat(polls.find(f.runtime().id()).orElseThrow().sequence()).isZero();
        ready(f);request(f,body(f,1),200);assertThat(lifecycle.get(f.runtime().id()).ready(clock.instant())).isTrue();
        assertThat(runtimes.operation(f.operation().id()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        var changed=body(f,1);changed.put("state","DRAINING");request(f,changed,409);
        request(f,(encode(body(f,1))+" ").getBytes(StandardCharsets.UTF_8),409);request(f,body(f,0),409);request(f,body(f,3),409);
        changed=body(f,2);changed.put("sessionId",UUID.randomUUID().toString());request(f,changed,409);
        assertThat(polls.find(f.runtime().id()).orElseThrow().sequence()).isEqualTo(1);
    }
    @Test void authenticationBodyBoundsAndUnassignedReportsCannotChangeState()throws Exception {
        var f=fixture(true);byte[] body=encode(body(f,0)).getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(path(f)).with(user("fixture")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path(f)).header("Authorization","Basic Zml4dHVyZTpmaXh0dXJl").header("X-EdgeAI-Pod-Token","pod-proof-fixture").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        var other=fixture(true);
        mvc.perform(post(path(f)).header("Authorization","Bearer "+tokens.issue(other.runtime())).header("X-EdgeAI-Pod-Token","pod-proof-fixture").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path(f)).header("Authorization","Bearer "+tokens.issue(f.runtime())).header("X-EdgeAI-Pod-Token","wrong").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        request(f,new byte[262145],413);request(f,new byte[]{(byte)0xff},400);
        var invalid=body(f,0);invalid.put("unknown",true);request(f,invalid,400);
        request(f,encode(body(f,0)).replace("\"sequence\":0","\"sequence\":0,\"sequence\":0").getBytes(StandardCharsets.UTF_8),400);
        invalid=body(f,0);invalid.put("completed",List.of(Map.of("attemptId",UUID.randomUUID().toString(),"epoch",1,"exitCode",0)));request(f,invalid,409);
        invalid=body(f,0);invalid.put("generation",2);request(f,invalid,409);
        invalid=body(f,0);invalid.put("sequence",1.5);request(f,invalid,400);
        assertThat(polls.find(f.runtime().id())).isEmpty();assertThat(lifecycle.get(f.runtime().id()).sessionId()).isNull();
        mvc.perform(post("/api/v1/virtual-devices").with(user("fixture")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void submissionLagAndTemporaryOutageRetryWithoutLosingSequenceButExpiredLeaseIsFenced()throws Exception {
        var f=fixture(false);request(f,body(f,0),503);assertThat(polls.find(f.runtime().id())).isEmpty();
        lifecycle.submitted(f.runtime().id(),identities.get(f.runtime().id()).podUid());request(f,body(f,0),200);
        unavailable.set(true);request(f,body(f,1),503);assertThat(polls.find(f.runtime().id()).orElseThrow().sequence()).isZero();
        unavailable.set(false);request(f,body(f,1),200);clock.offset.addAndGet(4);request(f,body(f,2),409);
        lifecycle.reconcile(f.runtime().id());assertThat(lifecycle.get(f.runtime().id()).failureReason()).isEqualTo("LEASE_EXPIRED");
    }
    @Test void replayAfterDrainNeverRestoresRunAndSupervisorRetirementPersistsReplacement()throws Exception {
        var f=fixture(true);request(f,body(f,0),200);lifecycle.drain(f.vd().id(),0,keyName());
        assertThat(request(f,body(f,0),200)).contains("\"command\":\"STOP\"");assertThat(lifecycle.get(f.runtime().id()).desiredState()).isEqualTo("STOPPED");
        assertThat(polls.find(f.runtime().id()).orElseThrow().command()).isEqualTo("STOP");request(f,body(f,0),409);
        var g=fixture(true);request(g,body(g,0),200);var drain=body(g,1);drain.put("state","DRAINING");
        assertThat(request(g,drain,200)).contains("\"command\":\"STOP\"");
        var op=runtimes.pending(g.vd().id()).orElseThrow();assertThat(op.kind()).isEqualTo("REPLACE");assertThat(op.targetRuntimeId()).isNull();
        assertThat(runtimes.history(g.vd().id(),10)).hasSize(1);
    }
    @Test void concurrentSameRequestIsSerializedAndDbRejectsSequenceAndAuthorityTampering()throws Exception {
        var f=fixture(true);var body=body(f,0);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<String>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return request(f,body,200);}));start.countDown();
            var replies=new HashSet<String>();for(var future:futures)replies.add(future.get(20,TimeUnit.SECONDS));assertThat(replies).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.vd_runtime_poll WHERE runtime_id=?",Integer.class,f.runtime().id())).isEqualTo(1);
        for(String sql:List.of("UPDATE edgeai.vd_runtime_poll SET sequence=2 WHERE runtime_id=?","UPDATE edgeai.vd_runtime_poll SET request_digest='sha256:'||repeat('a',64) WHERE runtime_id=?",
            "UPDATE edgeai.vd_runtime_poll SET command='STOP' WHERE runtime_id=?","DELETE FROM edgeai.vd_runtime_poll WHERE runtime_id=?"))
            assertThatThrownBy(()->jdbc.update(sql,f.runtime().id())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void nodeIdentityCannotChangeAfterAttestationAndInitialLeaseIsBoundedByStartupDeadline()throws Exception {
        var f=fixture(true);request(f,body(f,0),200);var p=identities.get(f.runtime().id());
        identities.put(f.runtime().id(),new VDGateway.PodIdentity(p.podUid(),UUID.randomUUID(),p.nodeName(),true));request(f,body(f,1),409);
        assertThat(polls.find(f.runtime().id()).orElseThrow().sequence()).isZero();
        var g=fixture(true);clock.offset.addAndGet(57);
        var response=(Map<?,?>)json.decode(request(g,body(g,0),200));assertThat(((Number)response.get("leaseSeconds")).intValue()).isBetween(1,2);
        assertThat(lifecycle.get(g.runtime().id()).leaseUntil()).isBeforeOrEqualTo(g.runtime().startupDeadline().plusMillis(50));
    }
    @Test void concurrentSessionsCannotBothAcquireTheSameRuntime()throws Exception {
        var f=fixture(true);var first=body(f,0);var second=body(f,0);second.put("sessionId",UUID.randomUUID().toString());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();
            for(var body:List.of(first,second))futures.add(pool.submit(()->{start.await();return mvc.perform(post(path(f)).header("Authorization","Bearer "+tokens.issue(f.runtime()))
                .header("X-EdgeAI-Pod-Token","pod-proof-fixture").contentType("application/json").content(encode(body))).andReturn().getResponse().getStatus();}));
            start.countDown();var statuses=new ArrayList<Integer>();for(var result:futures)statuses.add(result.get(20,TimeUnit.SECONDS));assertThat(statuses).containsExactlyInAnyOrder(200,409);
        }
        assertThat(polls.find(f.runtime().id()).orElseThrow().sessionId()).isEqualTo(lifecycle.get(f.runtime().id()).sessionId());
    }
    private Process supervisor(Fixture f,Path work)throws Exception {
        Files.createDirectories(work);var claim=directory.resolve("claim-"+UUID.randomUUID());var proof=directory.resolve("proof-"+UUID.randomUUID());
        Files.writeString(claim,tokens.issue(f.runtime()));Files.writeString(proof,"pod-proof-fixture");workspaces.put(f.runtime().id(),work);
        var command=new ProcessBuilder("python3",Path.of("../../runner/vd.py").toAbsolutePath().normalize().toString());
        var env=command.environment();env.keySet().removeIf(k->k.startsWith("EDGEAI_"));
        env.putAll(Map.of("EDGEAI_VD_ID",f.vd().id().toString(),"EDGEAI_VD_RUNTIME_ID",f.runtime().id().toString(),"EDGEAI_VD_GENERATION","1","EDGEAI_POD_UID",identities.get(f.runtime().id()).podUid().toString(),
            "EDGEAI_CONTROL_PLANE_URL","http://127.0.0.1:"+port,"EDGEAI_VD_CLAIM_FILE",claim.toString(),"EDGEAI_POD_TOKEN_FILE",proof.toString(),"EDGEAI_WORK_DIR",work.toString(),"EDGEAI_VD_MAX_CONCURRENT_TASKS","2","EDGEAI_VD_STARTUP_SECONDS","60"));
        env.put("EDGEAI_VD_DRAIN_SECONDS","5");env.put("PYTHONDONTWRITEBYTECODE","1");
        return command.redirectOutput(directory.resolve("supervisor-"+UUID.randomUUID()+".log").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    private void await(BooleanSupplier predicate,int seconds,String message)throws Exception {long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();do{if(predicate.getAsBoolean())return;Thread.sleep(100);}while(System.nanoTime()<end);throw new AssertionError(message);}
    private void terminate(Process p)throws Exception{if(p.isAlive()){p.destroy();if(!p.waitFor(5,TimeUnit.SECONDS)){p.destroyForcibly();assertThat(p.waitFor(5,TimeUnit.SECONDS)).isTrue();}}}
    @Test void realPythonSupervisorAndActualHttpReachReadyThenPersistStopBeforeProcessExit()throws Exception {
        var f=fixture(true);var work=directory.resolve("work");var process=supervisor(f,work);
        try {
            await(()->lifecycle.get(f.runtime().id()).ready(clock.instant()),15,"Supervisor did not become Ready through the real HTTP endpoint");
            assertThat(Files.exists(work.resolve(".vd-ready"))).isTrue();assertThat(polls.find(f.runtime().id()).orElseThrow().sequence()).isGreaterThanOrEqualTo(1);
            lifecycle.drain(f.vd().id(),0,keyName());assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
            assertThat(Files.exists(work.resolve(".vd-ready"))).isFalse();assertThat(lifecycle.get(f.runtime().id()).desiredState()).isEqualTo("STOPPED");
            assertThat(polls.find(f.runtime().id()).orElseThrow().command()).isEqualTo("STOP");
            assertThat(runtimes.pending(f.vd().id()).orElseThrow().state()).isEqualTo("RUNNING"); // Actual Pod absence is still required.
        } finally {terminate(process);}
    }
    @Test void realSupervisorDropsReadinessAndExitsWhenServerCannotRenewLease()throws Exception {
        var f=fixture(true);var work=directory.resolve("work");var process=supervisor(f,work);
        try {
            await(()->lifecycle.get(f.runtime().id()).ready(clock.instant()),15,"Supervisor HTTP startup failed");
            unavailable.set(true);await(()->!Files.exists(work.resolve(".vd-ready")),5,"503 did not remove readiness");
            assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isNotZero();
            lifecycle.reconcile(f.runtime().id());assertThat(lifecycle.get(f.runtime().id()).failureReason()).isEqualTo("LEASE_EXPIRED");
        } finally {unavailable.set(false);terminate(process);}
    }
}
