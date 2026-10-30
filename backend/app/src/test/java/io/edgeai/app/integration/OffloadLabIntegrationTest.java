package io.edgeai.app.integration;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Session/load ownership against actual PostgreSQL. Kubernetes is a recording fake (no Job or burner runs here); the Kubernetes
 * adapter's request shape is covered by KubernetesOffloadLabGatewayTest and the live roundtrip is verified separately.
 */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.runtime.workflow-keys="+OffloadLabIntegrationTest.KEY,
    "edgeai.offload-lab.workflow-key="+OffloadLabIntegrationTest.KEY})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import({RetryIntegrationTest.TimeConfiguration.class,OffloadLabIntegrationTest.Fake.class})
class OffloadLabIntegrationTest {
    static final String KEY="offload-lab-it";
    private static final Path RUNNER_KEY=key();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("edgeai.runtime.key-file",RUNNER_KEY::toString);registry.add("edgeai.runtime.namespace",()->"offload-lab-it");
    }
    private static Path key() {
        try{var path=Files.createTempFile("edgeai-runner-key-lab-",".key");byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
            Files.writeString(path,HexFormat.of().formatHex(key));return path;}catch(Exception e){throw new IllegalStateException(e);}
    }
    @AfterAll static void removeKey() throws Exception{Files.deleteIfExists(RUNNER_KEY);}

    /** Records calls; a deleted session's load Pod stays visible (terminating) for one more check, like a real grace period. */
    static final class FakeGateway implements OffloadLabGateway {
        final List<Load> ensured=Collections.synchronizedList(new ArrayList<>());
        final List<UUID> deleted=Collections.synchronizedList(new ArrayList<>());
        final Map<UUID,Set<Integer>> jobs=new ConcurrentHashMap<>();
        final Map<UUID,Integer> terminating=new ConcurrentHashMap<>();
        volatile boolean broken;
        public String loadImage(){return "registry.test/edgeai-runner-offload-lab@sha256:"+"0".repeat(64);}
        public UUID ensureLoad(Load load){if(broken)throw new RuntimeGatewayException(RuntimeGatewayException.Reason.UNAVAILABLE);
            ensured.add(load);jobs.computeIfAbsent(load.sessionId(),k->ConcurrentHashMap.newKeySet()).add(load.generation());return UUID.nameUUIDFromBytes((load.sessionId()+"/"+load.generation()).getBytes());}
        /** Runs once at the start of the next deleteLoad: a deterministic point inside a reconciliation step. */
        volatile Runnable beforeDelete;
        public boolean deleteLoad(UUID session,int through){if(broken)throw new RuntimeGatewayException(RuntimeGatewayException.Reason.UNAVAILABLE);
            var hook=beforeDelete;beforeDelete=null;if(hook!=null)hook.run();
            deleted.add(session);var gens=jobs.get(session);
            if(gens!=null && gens.removeIf(g->g<=through)){if(gens.isEmpty())jobs.remove(session);terminating.put(session,1);return false;}
            var grace=terminating.remove(session);if(grace!=null && grace>0){terminating.put(session,grace-1);return false;}return true;}
        public List<Pod> pods(UUID session,UUID run){if(broken)throw new RuntimeGatewayException(RuntimeGatewayException.Reason.UNAVAILABLE);
            var result=new ArrayList<Pod>();for(int g:jobs.getOrDefault(session,Set.of()))
                result.add(new Pod("LOAD","edgeai-lab-load-"+session+"-"+g+"-x",UUID.randomUUID(),"edge-node","Running",true,null,g,Instant.now(),Instant.now(),null,null));
            return result;}
        public Set<UUID> loadSessions(){return Set.copyOf(jobs.keySet());}
    }
    @TestConfiguration static class Fake { @Bean FakeGateway offloadLabGateway(){return new FakeGateway();} }

    @Autowired RetryIntegrationTest.TestClock clock;
    @Autowired FakeGateway gateway;
    @Autowired OffloadLabService lab;
    @Autowired ExecutionService executions;
    @Autowired ExecutionRepository runs;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired NodeService nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired OffloadLabRepository labSessions;
    @Autowired NodeRepository nodeRepository;
    @Autowired NodeMetricsService metrics;
    @Autowired OffloadRepository offloadRepository;
    @Autowired TelemetryRepository telemetry;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired ProfileRepository profileRepository;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired RuntimeScope scope;
    @Autowired RuntimeRepository runtimes;
    @MockitoBean RuntimeGateway runtimeGateway;
    @MockitoBean io.edgeai.adapters.storage.S3ArtifactStore storage;
    @MockitoBean io.edgeai.domain.device.DeviceResourceGateway devices;
    private final JsonDocuments json=new JsonDocuments();
    private ExecutionNode edge;

    @SuppressWarnings("unchecked") @BeforeEach void setUp() throws Exception {
        // Single open session per lab: earlier tests' sessions are closed directly, never through Kubernetes.
        jdbc.update("UPDATE edgeai.offload_lab_session SET load_desired=CASE WHEN load_desired='RUNNING' THEN 'STOPPED' ELSE load_desired END,state='STOPPED',stopped_at=now() WHERE state<>'STOPPED' OR load_desired='RUNNING'");
        gateway.broken=false;gateway.beforeDelete=null;gateway.jobs.clear();gateway.terminating.clear();gateway.ensured.clear();gateway.deleted.clear();
        var id=UUID.randomUUID();edge=new ExecutionNode(id,"lab-edge-"+id,"arm64","linux","READY","4","4Gi","{\"node-role.kubernetes.io/edge\":\"\"}",clock.instant(),"{\"cpu\":\"4\",\"memory\":\"4Gi\"}");
        var worker=new ExecutionNode(UUID.randomUUID(),"lab-worker-"+id,"arm64","linux","READY","8","8Gi","{\"node-role.kubernetes.io/worker\":\"\"}",clock.instant(),"{\"cpu\":\"8\",\"memory\":\"8Gi\"}");
        nodes.recordSnapshot(List.of(edge,worker),clock.instant());
        if(workflows.list(100,0).stream().noneMatch(w->w.key().equals(KEY))) {
            var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
            spec.put("inputs",Map.of());spec.put("timeoutSeconds",1800);spec.put("recovery",Map.of("mode","RESTART"));
            var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","offload-lab-it-model","version","1.0.0","spec",spec))).version();
            var workflow=workflows.create(json.canonical(Map.of("key",KEY,"displayName","Offload lab fixture"))).value();
            workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",List.of(Map.of("key","model","serviceProfileVersionId",profile.id().toString(),
                "parameters",Map.of("durationSeconds",1200))),"dependencies",List.of())));
        }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> offload() {
        return (Map<String,Object>)((Map<String,Object>)json.decode(json.canonical(lab.view().defaults()))).get("offload");
    }
    private String start(UUID edgeId){return json.canonical(Map.of("edgeNodeId",edgeId.toString(),"offload",offload()));}
    private static String load(int workers,int seconds){return "{\"workers\":"+workers+",\"durationSeconds\":"+seconds+"}";}
    /** The runtime worker's observation that the model Pod terminated (no Kubernetes here). */
    private void modelTerminated(UUID runId) {
        jdbc.update("UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE kind='CREATE' AND runtime_id IN (SELECT id FROM edgeai.runtime_instance WHERE run_id=?)",runId);
        for(var task:runs.tasks(runId))for(var attempt:runs.attempts(task.id()))
            runtimes.byAttempt(attempt.id()).filter(r->r.desiredState().equals("STOPPED") && !r.observedState().equals("TERMINATED")).ifPresent(r->lifecycle.confirmStopped(attempt.id()));
    }
    private String state(UUID session){return jdbc.queryForObject("SELECT state FROM edgeai.offload_lab_session WHERE id=?",String.class,session);}
    private static void rejected(Runnable action,String code){
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo(code));
    }

    @Test void sessionStartsOneOrdinaryRunOnTheChosenEdgeAndReplaysByKey() {
        var key=UUID.randomUUID().toString();var created=lab.start(key,start(edge.id()));
        assertThat(created.created()).isTrue();
        var run=runs.run(created.value().runId(),false).orElseThrow();
        assertThat(run.mode()).isEqualTo("NODE");assertThat(run.nodeId()).isEqualTo(edge.id());
        assertThat(json.canonical(json.decode(run.offloadPolicyJson()))).contains("returnToEdge","EDGE_TO_WORKER");
        var replay=lab.start(key,start(edge.id()));
        assertThat(replay.created()).isFalse();assertThat(replay.value().runId()).isEqualTo(run.id());
        var changed=new HashMap<>(offload());changed.put("cooldownSeconds",60);
        rejected(()->lab.start(key,json.canonical(Map.of("edgeNodeId",edge.id().toString(),"offload",changed))),"IDEMPOTENCY_CONFLICT");
        rejected(()->lab.start(UUID.randomUUID().toString(),start(edge.id())),"OFFLOAD_LAB_BUSY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_run WHERE id IN (SELECT run_id FROM edgeai.offload_lab_session WHERE state<>'STOPPED')",Long.class)).isEqualTo(1);
        // A one-way or task-telemetry policy, or a missing policy, is not a lab session.
        var legacy=new HashMap<>(offload());legacy.remove("nodePlacement");legacy.put("cpuPercent",90);
        assertThatThrownBy(()->lab.start(UUID.randomUUID().toString(),json.canonical(Map.of("edgeNodeId",edge.id().toString(),"offload",legacy)))).isInstanceOf(RuntimeException.class);
    }
    @Test void duplicateLoadStartsNeverCreateASecondBurner() {
        var session=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();var key=UUID.randomUUID().toString();
        var first=lab.startLoad(session.id(),key,load(3,300));
        assertThat(first.created()).isTrue();assertThat(first.value().loadGeneration()).isEqualTo(1);assertThat(first.value().loadJobUid()).isNotNull();
        assertThat(gateway.ensured).containsExactly(new OffloadLabGateway.Load(session.id(),1,edge.name(),3,300));
        assertThat(lab.startLoad(session.id(),key,load(3,300)).created()).isFalse(); // Reload/retry of the same click.
        rejected(()->lab.startLoad(session.id(),key,load(2,300)),"IDEMPOTENCY_CONFLICT");
        rejected(()->lab.startLoad(session.id(),UUID.randomUUID().toString(),load(3,300)),"OFFLOAD_LAB_LOAD_RUNNING");
        assertThat(gateway.ensured).hasSize(1);
        for(var invalid:List.of(load(0,300),load(17,300),load(3,29),load(3,1801),"{\"workers\":3}","{\"workers\":1.5,\"durationSeconds\":60}"))
            assertThatThrownBy(()->lab.startLoad(session.id(),UUID.randomUUID().toString(),invalid)).isInstanceOf(IllegalArgumentException.class);
        lab.stopLoad(session.id(),"{}");
        assertThat(gateway.deleted).contains(session.id());
        var second=lab.startLoad(session.id(),UUID.randomUUID().toString(),load(2,60));
        assertThat(second.value().loadGeneration()).isEqualTo(2);assertThat(gateway.ensured.getLast().generation()).isEqualTo(2);
    }
    @Test void submissionFailureIsRetriedAndExpiredLoadIsRemovedByTheReconciler() {
        var session=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();
        gateway.broken=true;var started=lab.startLoad(session.id(),UUID.randomUUID().toString(),load(2,30)).value();
        assertThat(started.loadDesired()).isEqualTo("RUNNING");assertThat(started.loadJobUid()).isNull();
        gateway.broken=false;lab.reconcile();
        assertThat(gateway.ensured).hasSize(1);assertThat(gateway.jobs).containsKey(session.id());
        lab.reconcile();assertThat(gateway.ensured).hasSize(1); // Submitted once; a finished, collected Job is never recreated.
        clock.advance(31);lab.reconcile();
        assertThat(jdbc.queryForObject("SELECT load_desired FROM edgeai.offload_lab_session WHERE id=?",String.class,session.id())).isEqualTo("STOPPED");
        assertThat(gateway.deleted).contains(session.id());assertThat(gateway.jobs).doesNotContainKey(session.id());
        assertThat(lab.view().session().load().desired()).isEqualTo("STOPPED");
    }
    @Test void userStopCancelsTheRunAndFinishesOnlyAfterLoadPodsArePhysicallyGone() {
        var session=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();
        lab.startLoad(session.id(),UUID.randomUUID().toString(),load(3,300));
        var stopping=lab.stop(session.id(),"{}");
        assertThat(stopping.state()).isEqualTo("STOPPING");assertThat(stopping.loadDesired()).isEqualTo("STOPPED");
        assertThat(runs.run(session.runId(),false).orElseThrow().state()).isIn("CANCELLED","CANCELLING");
        rejected(()->lab.startLoad(session.id(),UUID.randomUUID().toString(),load(1,60)),"OFFLOAD_LAB_STOPPED");
        lab.reconcile();assertThat(state(session.id())).isEqualTo("STOPPING"); // Model Pod not yet observed terminated.
        modelTerminated(session.runId());gateway.terminating.put(session.id(),1); // Model gone; the load Pod is still reported.
        lab.reconcile();assertThat(state(session.id())).isEqualTo("STOPPING"); // Load Pod still terminating.
        lab.reconcile();assertThat(state(session.id())).isEqualTo("STOPPED");
        assertThat(lab.stop(session.id(),"{}").state()).isEqualTo("STOPPED"); // Idempotent.
        assertThat(lab.start(UUID.randomUUID().toString(),start(edge.id())).created()).isTrue(); // Explicit repeat session.
    }
    /** Live repro 26ce1cbb: the model failed while load ran; the session stayed ACTIVE with a running burner on a terminal Run. */
    @Test void modelFailureDuringLoadStopsTheLoadAndFinishesTheSession() {
        var session=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();
        var attempt=runs.attempts(runs.tasks(session.runId()).getFirst().id()).getFirst().id();
        lifecycle.plan(attempt,"offload-lab-it");
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),edge.id(),edge.name());
        lifecycle.submitted(attempt,pod.jobUid());lifecycle.claim(attempt,1,pod);
        lab.startLoad(session.id(),UUID.randomUUID().toString(),load(4,300));
        lifecycle.fail(attempt,1,pod.podUid(),"WORKLOAD_FAILED");
        assertThat(runs.run(session.runId(),false).orElseThrow().state()).isEqualTo("FAILED");
        rejected(()->lab.startLoad(session.id(),UUID.randomUUID().toString(),load(1,60)),"OFFLOAD_LAB_RUN_FINISHED");
        lab.reconcile();
        var view=lab.view().session();
        assertThat(view.state()).isNotEqualTo("ACTIVE");assertThat(view.load().desired()).isEqualTo("STOPPED");
        assertThat(gateway.deleted).contains(session.id());
        modelTerminated(session.runId());
        for(int i=0;i<3;i++)lab.reconcile();
        assertThat(state(session.id())).isEqualTo("STOPPED");assertThat(gateway.jobs).doesNotContainKey(session.id());
    }
    @Test void stopAndViewWorkWhileKubernetesIsUnavailable() {
        var session=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();
        lab.startLoad(session.id(),UUID.randomUUID().toString(),load(2,120));
        gateway.broken=true;
        assertThat(lab.view().session().pods().status()).isEqualTo("UNAVAILABLE");
        assertThat(lab.stop(session.id(),"{}").state()).isEqualTo("STOPPING");
        modelTerminated(session.runId());lab.reconcile();assertThat(state(session.id())).isEqualTo("STOPPING"); // Load unobservable is not stopped.
        gateway.broken=false;for(int i=0;i<3;i++)lab.reconcile();
        assertThat(state(session.id())).isEqualTo("STOPPED");
    }
    @Test void reconcilerRemovesOnlyLoadJobsNoSessionStillWants() {
        var session=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();
        lab.startLoad(session.id(),UUID.randomUUID().toString(),load(2,300));
        var orphan=UUID.randomUUID();gateway.jobs.put(orphan,new HashSet<>(Set.of(4))); // Left by an earlier API instance.
        lab.reconcile();
        assertThat(gateway.deleted).contains(orphan).doesNotContain(session.id());assertThat(gateway.jobs).containsKey(session.id());
        var view=lab.view().session();
        assertThat(view.pods().status()).isEqualTo("OBSERVED");assertThat(view.pods().items()).allMatch(p->p.role().equals("LOAD") && p.generation()==1);
        assertThat(view.edgeNodeName()).isEqualTo(edge.name());assertThat(view.load().desired()).isEqualTo("RUNNING");
    }
    @Test void httpRequiresCsrfAndReportsLabState() throws Exception {
        mvc.perform(post("/api/v1/offload-lab/sessions").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(start(edge.id())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/offload-lab/sessions").with(csrf()).header("Idempotency-Key","not-a-uuid").contentType("application/json").content(start(edge.id())))
            .andExpect(status().isBadRequest());
        var key=UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/offload-lab/sessions").with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(start(edge.id())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("ACTIVE"));
        mvc.perform(get("/api/v1/offload-lab")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.model.workflowKey").value(KEY)).andExpect(jsonPath("$.session.edgeNodeName").value(edge.name()))
            .andExpect(jsonPath("$.defaults.offload.nodePlacement.returnToEdge.cpuPercent").value(20));
        mvc.perform(post("/api/v1/offload-lab/sessions/"+UUID.randomUUID()+"/stop").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isNotFound());
    }

    /** A load started while a reconciliation step is between reading the session and removing its old load survives that step. */
    @Test void reconciliationNeverRemovesOrStopsAGenerationStartedAfterItRead() {
        var session=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();
        lab.startLoad(session.id(),UUID.randomUUID().toString(),load(2,60));lab.stopLoad(session.id(),"{}");lab.reconcile();lab.reconcile();
        assertThat(gateway.jobs).doesNotContainKey(session.id());
        // Stopped generation 1: the step reads it, then a user starts generation 2 before the step's removal runs.
        gateway.beforeDelete=()->lab.startLoad(session.id(),UUID.randomUUID().toString(),load(3,300));
        lab.reconcile();
        assertThat(gateway.jobs.get(session.id())).containsExactly(2);
        var after=lab.view().session().load();
        assertThat(after.desired()).isEqualTo("RUNNING");assertThat(after.generation()).isEqualTo(2);assertThat(after.jobUid()).isNotNull();
        lab.reconcile();assertThat(gateway.jobs.get(session.id())).containsExactly(2); // Later steps and the orphan sweep keep it too.
        // Expiry: generation 2 expires, the step reads it, a user starts generation 3 before the step removes and expires 2.
        clock.advance(301);
        gateway.beforeDelete=()->lab.startLoad(session.id(),UUID.randomUUID().toString(),load(1,120));
        lab.reconcile();
        assertThat(gateway.jobs.get(session.id())).containsExactly(3);
        assertThat(lab.view().session().load().desired()).isEqualTo("RUNNING"); // The stale expiry did not stop generation 3.
        assertThat(lab.view().session().load().generation()).isEqualTo(3);
        lab.stopLoad(session.id(),"{}");lab.reconcile();
        assertThat(gateway.jobs).doesNotContainKey(session.id());
    }

    /** The same lab after a restart without the optional Kubernetes gateway (e.g. load image unset); no scheduler. */
    private OffloadLabService withoutGateway() {
        return new OffloadLabService(labSessions,executions,runs,runtimes,workflowRepository,profileRepository,nodeRepository,metrics,offloadRepository,telemetry,
            Optional.empty(),transactions,scope,clock,KEY,true,false,false,3000);
    }
    @Test void absentGatewayNeverReportsAPreviouslyRequestedLoadAsStopped() {
        var unobservable=withoutGateway();
        // A session that never requested load has nothing to observe: it can finish without Kubernetes.
        var quiet=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();
        assertThat(unobservable.stop(quiet.id(),"{}").state()).isEqualTo("STOPPING");
        modelTerminated(quiet.runId());unobservable.reconcile();
        assertThat(state(quiet.id())).isEqualTo("STOPPED");
        // A session that requested load stays STOPPING while its load Pods cannot be observed (absent, not just broken).
        var loaded=lab.start(UUID.randomUUID().toString(),start(edge.id())).value();
        lab.startLoad(loaded.id(),UUID.randomUUID().toString(),load(2,120));
        assertThat(unobservable.stop(loaded.id(),"{}").state()).isEqualTo("STOPPING");
        modelTerminated(loaded.runId());
        for(int i=0;i<3;i++)unobservable.reconcile();
        assertThat(state(loaded.id())).isEqualTo("STOPPING");
        assertThat(gateway.jobs).containsKey(loaded.id()); // Nothing was deleted or assumed deleted.
        // Once a gateway observes the load gone, the stop completes.
        for(int i=0;i<3;i++)lab.reconcile();
        assertThat(state(loaded.id())).isEqualTo("STOPPED");assertThat(gateway.jobs).doesNotContainKey(loaded.id());
    }
}
