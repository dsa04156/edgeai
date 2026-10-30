package io.edgeai.app.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.edgeai.adapters.kubernetes.KubernetesProfilingGateway;
import io.edgeai.adapters.registry.FakeRegistry;
import io.edgeai.app.config.ModelProfilingSettings;
import io.edgeai.app.service.ModelProfilingReconciler;
import io.edgeai.app.service.NodeMetricsService;
import io.edgeai.domain.aimodel.InferenceLoadDriver;
import io.edgeai.domain.aimodel.ProfilingGateway;
import io.edgeai.domain.aimodel.ProfilingJob;
import io.edgeai.domain.repository.ModelProfilingRepository;
import io.edgeai.domain.resourceprofile.WorkloadMetricsSource;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.repository.NodeRepository;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Measurement lifecycle through the public API on an isolated PostgreSQL database. The real Kubernetes adapter talks to a
 * stateful loopback Kubernetes API fake (Jobs, owned Pods, UID-preconditioned deletes), the real Prometheus adapter to a
 * loopback query API, and the real HTTP load driver sends the sample request to a real local model endpoint. Nothing
 * here starts a Pod; the live cluster roundtrip is a separate, coordinator-run step.
 */
@SpringBootTest(properties={"edgeai.ai-models.enabled=true","edgeai.model-profiling.enabled=true","edgeai.model-profiling.poll-ms=0",
    "edgeai.model-profiling.max-active-runs=16","edgeai.model-profiling.collect-timeout-seconds=60","edgeai.model-profiling.scrape-grace-seconds=60",
    "edgeai.model-profiling.accelerator-resources=nvidia.com/gpu,hailo.ai/h8",
    "edgeai.resource-profiling.enabled=true","edgeai.resource-profiling.namespaces=edgeai"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import({RetryIntegrationTest.TimeConfiguration.class,ModelProfilingIntegrationTest.Gateway.class})
@Timeout(120)
class ModelProfilingIntegrationTest {
    static final String NS="edgeai-model-profiling";
    static final FakeRegistry REGISTRY;
    static final FakeKubernetes KUBE;
    static final FakePrometheus PROMETHEUS;
    static final ModelServer MODEL;
    /** The test clock as seen by the fakes, so Pod times and the server's window share one time base. */
    static volatile java.util.function.Supplier<Instant> NOW=Instant::now;
    static {
        try{REGISTRY=new FakeRegistry();KUBE=new FakeKubernetes();PROMETHEUS=new FakePrometheus();MODEL=new ModelServer();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("edgeai.ai-models.registry.url",REGISTRY::url);
        p.add("edgeai.resource-profiling.prometheus-url",PROMETHEUS::url);
    }
    @AfterAll static void close(){REGISTRY.close();KUBE.server.stop(0);PROMETHEUS.server.stop(0);MODEL.server.stop(0);}
    @TestConfiguration static class Gateway {
        @Bean(destroyMethod="close") KubernetesProfilingGateway testProfilingGateway(){return new KubernetesProfilingGateway(KUBE.url(),"","",NS,"edgeai-model-profiler");}
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RetryIntegrationTest.TestClock clock;
    @Autowired ModelProfilingReconciler reconciler;
    @Autowired NodeRepository nodes;
    @Autowired ModelProfilingRepository repository;
    @Autowired ModelProfilingSettings settings;
    @Autowired KubernetesProfilingGateway kubernetes;
    @Autowired ObjectProvider<InferenceLoadDriver> drivers;
    @Autowired ObjectProvider<WorkloadMetricsSource> metrics;
    @Autowired ObjectProvider<NodeMetricsService> nodeMetrics;
    private final JsonMapper json=new JsonMapper();
    private String node;

    @BeforeEach void reset() {
        REGISTRY.reset();MODEL.status=200;PROMETHEUS.empty=false;NOW=clock::instant;
        node="mp-node-"+UUID.randomUUID().toString().substring(0,8);
        seedNodes();
    }
    private void seedNodes() {
        var now=clock.instant();
        var list=new ArrayList<ExecutionNode>();
        for(String name:List.of(node,node+"-b"))
            list.add(new ExecutionNode(UUID.nameUUIDFromBytes(name.getBytes()),name,"amd64","linux","READY","8","16Gi","{}",now,"{\"cpu\":\"8\",\"memory\":\"16Gi\",\"nvidia.com/gpu\":\"1\",\"hailo.ai/h8\":\"1\",\"hami.io/vgpu\":\"0\"}"));
        list.add(new ExecutionNode(UUID.nameUUIDFromBytes((node+"-arm").getBytes()),node+"-arm","arm64","linux","READY","4","8Gi","{}",now,"{}"));
        nodes.replaceSnapshot(list,now);
    }

    // ---- HTTP helpers -------------------------------------------------------------------------------------------------

    private JsonNode perform(MockHttpServletRequestBuilder request,int status) throws Exception {
        String body=mvc.perform(request.with(user("test")).with(csrf())).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(body.isEmpty()?"{}":body);
    }
    private JsonNode post(String path,String key,Object body,int status) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(json.writeValueAsString(body));
        if(key!=null)request.header("Idempotency-Key",key);
        return perform(request,status);
    }
    private JsonNode get(String path) throws Exception { return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path),200); }
    private JsonNode registerModel() throws Exception {
        String repo="models/mp"+UUID.randomUUID().toString().substring(0,8);
        var image=REGISTRY.image(repo,"1.0","linux","amd64");
        var body=new LinkedHashMap<String,Object>();
        body.put("modelKey","digits-"+UUID.randomUUID().toString().substring(0,8));body.put("displayName","Digits");body.put("modelVersion","1.0.0");
        body.put("image",Map.of("repository",repo,"tag","1.0","digest",image.digest(),"platform",Map.of("os","linux","architecture","amd64")));
        body.put("execution",Map.of("command",List.of("python"),"args",List.of("/opt/demo/server.py")));
        return post("/api/v1/ai-models",UUID.randomUUID().toString(),body,201);
    }
    private Map<String,Object> httpRun(String nodeName) {
        var load=new LinkedHashMap<String,Object>();
        load.put("type","HTTP");load.put("port",MODEL.port());load.put("path","/v2/models/digits-centroid/infer");load.put("method","POST");
        load.put("contentType","application/json");load.put("body","{\"inputs\":[{\"name\":\"pixels\",\"datatype\":\"FP32\",\"shape\":[1,64],\"data\":[]}]}");
        load.put("concurrency",2);load.put("requestTimeoutSeconds",5);load.put("readinessTimeoutSeconds",60);
        return new LinkedHashMap<>(Map.of("nodeName",nodeName,"allocation",Map.of("cpuMillicores",1000,"memoryBytes",1L<<30),"durationSeconds",300,"load",load));
    }
    private Map<String,Object> commandRun(String nodeName) {
        return new LinkedHashMap<>(Map.of("nodeName",nodeName,"allocation",Map.of("cpuMillicores",1000,"memoryBytes",1L<<30),"durationSeconds",300,
            "load",Map.of("type","COMMAND","command",List.of("python"),"args",List.of("/opt/demo/bench.py","--seconds","300"))));
    }
    private String runs(String model){return "/api/v1/ai-models/"+model+"/profiling-runs";}
    private JsonNode run(String model,String run) throws Exception { return get(runs(model)+"/"+run); }
    private JsonNode step(String model,String run) throws Exception { reconciler.advance(UUID.fromString(run));return run(model,run); }
    private JsonNode until(String model,String run,String state) throws Exception {
        for(int i=0;i<10;i++){var view=step(model,run);if(view.path("state").asText().equals(state))return view;}
        throw new AssertionError("Run "+run+" did not reach "+state+": "+run(model,run));
    }
    private void cleanUp(String model,String run) throws Exception {
        for(int i=0;i<5 && !run(model,run).path("cleanup").path("state").asText().equals("DONE");i++)reconciler.advance(UUID.fromString(run));
        assertThat(run(model,run).path("cleanup").path("state").asText()).isEqualTo("DONE");
    }
    /** Starts an HTTP run and drives it into MEASURING with real requests flowing to the local model endpoint. */
    private JsonNode measuring(String model,String run) throws Exception {
        assertThat(step(model,run).path("state").asText()).isEqualTo("STARTING");
        KUBE.pod(run).running();
        assertThat(step(model,run).path("state").asText()).isEqualTo("WARMING_UP");
        return step(model,run);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------------

    @Test void registeredModelIsMeasuredUnderRealLoadConfirmedAndThenReady() throws Exception {
        var model=registerModel();String id=model.path("id").asText();
        assertThat(model.path("requirements").isNull()).isTrue();
        assertThat(model.path("availability").path("status").asText()).isEqualTo("NOT_READY");
        assertThat(model.path("availability").path("reason").asText()).isEqualTo("NO_MEASUREMENT");

        var options=get("/api/v1/ai-models/"+id+"/profiling/options");
        assertThat(options.path("enabled").asBoolean()).isTrue();
        assertThat(options.path("image").path("reference").asText()).isEqualTo(model.path("image").path("platformReference").asText());
        var candidate=find(options.path("nodes"),node);
        assertThat(candidate.path("compatible").asBoolean()).isTrue();
        assertThat(candidate.path("accelerators").toString()).contains("\"resourceName\":\"hailo.ai/h8\",\"allocatableCount\":1").doesNotContain("hami.io");
        assertThat(find(options.path("nodes"),node+"-arm").path("incompatibleReason").asText()).isEqualTo("ARCHITECTURE_MISMATCH");

        String key=UUID.randomUUID().toString();
        var started=post(runs(id),key,httpRun(node),201);
        String runId=started.path("id").asText();
        assertThat(started.path("state").asText()).isEqualTo("PENDING");
        assertThat(started.path("load").has("body")).isFalse();
        assertThat(started.path("load").path("bodyDigest").asText()).startsWith("sha256:");
        assertThat(post(runs(id),key,httpRun(node),200).path("id").asText()).isEqualTo(runId);
        var other=httpRun(node);other.put("durationSeconds",600);
        assertThat(post(runs(id),key,other,409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(post(runs(id),UUID.randomUUID().toString(),httpRun(node+"-b"),409).path("code").asText()).isEqualTo("PROFILING_RUN_ACTIVE");
        assertThat(get("/api/v1/ai-models/"+id).path("availability").path("reason").asText()).isEqualTo("MEASURING");

        var view=step(id,runId);
        assertThat(view.path("state").asText()).isEqualTo("STARTING");
        var job=KUBE.job(runId);
        assertThat(json.writeValueAsString(job)).contains(model.path("image").path("platformReference").asText()).doesNotContain("\"nodeName\"");
        assertThat(view.path("kubernetes").path("jobUid").asText()).isEqualTo(job.path("metadata").path("uid").asText());
        assertThat(step(id,runId).path("state").asText()).isEqualTo("STARTING"); // Pod still pending
        KUBE.pod(runId).running();
        view=step(id,runId);
        assertThat(view.path("state").asText()).isEqualTo("WARMING_UP");
        assertThat(view.path("kubernetes").path("podUid").asText()).isEqualTo(KUBE.pod(runId).uid.toString());
        view=step(id,runId);
        assertThat(view.path("state").asText()).isEqualTo("MEASURING");
        assertThat(view.path("window").path("start").asText()).isNotEmpty();
        while(MODEL.calls.get()<40)Thread.sleep(10);
        view=step(id,runId);
        assertThat(view.path("progress").path("succeeded").asLong()).isPositive();

        clock.advance(300);
        view=step(id,runId);
        assertThat(view.path("state").asText()).isEqualTo("COLLECTING");
        var end=view.path("window").path("end").asText();
        assertThat(Instant.parse(end)).isEqualTo(Instant.parse(view.path("window").path("start").asText()).plusSeconds(300));
        assertThat(step(id,runId).path("state").asText()).isEqualTo("COLLECTING"); // scrape grace not yet over
        clock.advance(61);
        view=step(id,runId);
        assertThat(view.path("state").asText()).as(view.toString()).isEqualTo("SUCCEEDED");
        var result=view.path("result");
        assertThat(result.path("loadEvidence").path("type").asText()).isEqualTo("HTTP");
        assertThat(result.path("loadEvidence").path("successRatio").asDouble()).isEqualTo(1.0);
        assertThat(result.path("target").path("podUid").asText()).isEqualTo(KUBE.pod(runId).uid.toString());
        assertThat(result.path("measurement").path("cpu").path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(result.path("measurement").path("memory").path("max").asDouble()).isEqualTo(FakePrometheus.MEMORY);
        assertThat(result.path("deviceStatus").path("status").asText()).isEqualTo("NOT_REQUESTED");
        var recommendation=result.path("recommendation");
        assertThat(recommendation.path("cpu").path("requestMillicores").asLong()).isEqualTo(280); // p95 240 × 1.15, 10m up
        assertThat(recommendation.path("cpu").path("maximumMillicores").asLong()).isEqualTo(450); // max 300 × 1.5
        assertThat(recommendation.has("minimum")).isFalse();
        assertThat(result.path("limitations").toString()).contains("throttling").contains("첫 2xx 응답 이후","최소 자원 증명이 아닙니다");
        // Prometheus was asked only about this run's Job and Pod in the run's window.
        assertThat(PROMETHEUS.queries).anyMatch(q->q.contains("container_cpu_usage_seconds_total") && q.contains(KUBE.pod(runId).name) && q.contains("container=\"model\""));
        assertThat(PROMETHEUS.queries).noneMatch(q->q.contains("container_cpu") && !q.contains(KUBE.pod(runId).name));

        // The slot is kept until Kubernetes confirms the Job and Pod are gone (UID precondition on the delete).
        assertThat(view.path("cleanup").path("state").asText()).isEqualTo("PENDING");
        assertThat(KUBE.requests).anyMatch(r->r.startsWith("DELETE /apis/batch/v1/namespaces/"+NS+"/jobs/edgeai-mp-"+runId) && r.contains(job.path("metadata").path("uid").asText()));
        cleanUp(id,runId);
        var waiting=get("/api/v1/ai-models/"+id).path("availability");
        assertThat(waiting.path("status").asText()).isEqualTo("NOT_READY");
        assertThat(waiting.path("reason").asText()).isEqualTo("AWAITING_CONFIRMATION");

        // Confirmation: untested accelerators and a "recommended" label on edited values are refused.
        var cpu=new LinkedHashMap<String,Object>(Map.of("requestMillicores",280,"maximumMillicores",450,"basis","RECOMMENDED"));
        var memory=new LinkedHashMap<String,Object>(Map.of("requestBytes",1L<<29,"basis","MANUAL"));
        var confirm=new LinkedHashMap<String,Object>(Map.of("runId",runId,"cpu",cpu,"memory",memory));
        confirm.put("accelerators",List.of(Map.of("resourceName","nvidia.com/gpu","count",1,"basis","TEST_ALLOCATION")));
        assertThat(post("/api/v1/ai-models/"+id+"/resource-profiles",UUID.randomUUID().toString(),confirm,409).path("code").asText()).isEqualTo("ACCELERATOR_NOT_TESTED");
        confirm.put("accelerators",List.of());
        cpu.put("requestMillicores",300);
        assertThat(post("/api/v1/ai-models/"+id+"/resource-profiles",UUID.randomUUID().toString(),confirm,400).path("code").asText()).isEqualTo("INVALID_RESOURCE_PROFILE");
        cpu.put("requestMillicores",280);
        String confirmKey=UUID.randomUUID().toString();
        var profile=post("/api/v1/ai-models/"+id+"/resource-profiles",confirmKey,confirm,201);
        assertThat(profile.path("revision").asInt()).isEqualTo(1);
        assertThat(profile.path("requirements").path("cpu").path("minimumMillicores").isNull()).isTrue();
        assertThat(profile.path("requirements").path("memory").path("maximumBytes").isNull()).isTrue();
        assertThat(profile.path("requirements").path("memory").path("basis").asText()).isEqualTo("MANUAL");
        assertThat(profile.path("scope").path("nodeName").asText()).isEqualTo(node);
        assertThat(profile.path("scope").path("image").asText()).isEqualTo(model.path("image").path("platformReference").asText());
        assertThat(profile.path("evidence").path("runId").asText()).isEqualTo(runId);
        assertThat(post("/api/v1/ai-models/"+id+"/resource-profiles",confirmKey,confirm,200).path("id")).isEqualTo(profile.path("id"));
        var ready=get("/api/v1/ai-models/"+id).path("availability");
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.path("profile").path("id")).isEqualTo(profile.path("id"));

        // A failed re-measurement keeps its history and never replaces the confirmed profile.
        seedNodes();
        String second=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        step(id,second);
        KUBE.pod(second).oom();
        var failed=step(id,second);
        assertThat(failed.path("state").asText()).isEqualTo("FAILED");
        assertThat(failed.path("failure").path("code").asText()).isEqualTo("OOM_KILLED");
        assertThat(post("/api/v1/ai-models/"+id+"/resource-profiles",UUID.randomUUID().toString(),Map.of("runId",second,"cpu",cpu,"memory",memory),409)
            .path("code").asText()).isEqualTo("PROFILING_RUN_NOT_SUCCEEDED");
        cleanUp(id,second);
        ready=get("/api/v1/ai-models/"+id).path("availability");
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.path("latestRunId").asText()).isEqualTo(second);
        assertThat(get(runs(id)).path("items")).hasSize(2);
        assertThat(get("/api/v1/ai-models/"+id+"/resource-profiles").path("items")).hasSize(1);
    }
    private static JsonNode find(JsonNode items,String name){for(var item:items)if(item.path("name").asText().equals(name))return item;throw new AssertionError(name);}

    @Test void idleOrFailingServersShortCommandsAndThinMetricsNeverSucceed() throws Exception {
        String id=registerModel().path("id").asText();

        // Every inference request fails: the readiness probe never passes, and a server failing during load is not a load.
        MODEL.status=500;
        String run=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        assertThat(measuring(id,run).path("state").asText()).isEqualTo("WARMING_UP");
        clock.advance(61);
        var failed=step(id,run);
        assertThat(failed.path("failure").path("code").asText()).isEqualTo("READINESS_TIMEOUT");
        cleanUp(id,run);

        seedNodes();MODEL.status=200;
        run=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        assertThat(measuring(id,run).path("state").asText()).isEqualTo("MEASURING");
        MODEL.status=503;MODEL.calls.set(0);
        while(MODEL.calls.get()<200)Thread.sleep(10);
        clock.advance(300);
        failed=step(id,run);
        assertThat(failed.path("state").asText()).isEqualTo("FAILED");
        assertThat(failed.path("failure").path("code").asText()).isEqualTo("LOAD_FAILED");
        assertThat(failed.path("result").path("loadEvidence").path("statusCounts").has("503")).isTrue();
        assertThat(failed.path("result").has("recommendation")).isFalse();
        cleanUp(id,run);

        // A benchmark command that ends before the requested duration is too short, even with exit 0.
        seedNodes();MODEL.status=200;
        run=post(runs(id),UUID.randomUUID().toString(),commandRun(node),201).path("id").asText();
        step(id,run);
        assertThat(KUBE.job(run).toString()).contains("/opt/demo/bench.py","EDGEAI_PROFILING_DURATION_SECONDS");
        KUBE.pod(run).running();
        assertThat(step(id,run).path("state").asText()).isEqualTo("MEASURING");
        KUBE.pod(run).completed(0,120);
        failed=step(id,run);
        assertThat(failed.path("failure").path("code").asText()).isEqualTo("TOO_SHORT");
        assertThat(failed.path("execution").path("args").toString()).contains("bench.py");
        cleanUp(id,run);

        // A long enough benchmark whose usage Prometheus never saw is not measured.
        seedNodes();PROMETHEUS.empty=true;
        run=post(runs(id),UUID.randomUUID().toString(),commandRun(node),201).path("id").asText();
        step(id,run);KUBE.pod(run).running();step(id,run);KUBE.pod(run).completed(0,301);
        assertThat(step(id,run).path("state").asText()).isEqualTo("COLLECTING");
        clock.advance(362); // past the window end + scrape grace: queried, no data, retried within the collect timeout
        assertThat(step(id,run).path("state").asText()).isEqualTo("COLLECTING");
        String podName=KUBE.pod(run).name;
        assertThat(PROMETHEUS.queries).anyMatch(q->q.contains("container_memory_working_set_bytes") && q.contains(podName));
        clock.advance(61);
        failed=step(id,run);
        assertThat(failed.path("failure").path("code").asText()).isEqualTo("INSUFFICIENT_SAMPLES");
        cleanUp(id,run);

        // An image the node cannot pull fails without waiting for the readiness timeout.
        seedNodes();PROMETHEUS.empty=false;
        run=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        step(id,run);KUBE.pod(run).waiting="ImagePullBackOff";
        assertThat(step(id,run).path("failure").path("code").asText()).isEqualTo("IMAGE_PULL_FAILED");
        cleanUp(id,run);
        assertThat(get("/api/v1/ai-models/"+id).path("availability").path("reason").asText()).isEqualTo("MEASUREMENT_FAILED");
    }

    @Test void cancelStopsLoadAndKeepsTheNodeUntilThePodIsGone() throws Exception {
        String id=registerModel().path("id").asText();
        String run=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        assertThat(measuring(id,run).path("state").asText()).isEqualTo("MEASURING");
        var cancelled=perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(runs(id)+"/"+run+"/cancel"),200);
        assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("cancelRequestedAt").asText()).isNotEmpty();
        int after=MODEL.calls.get();Thread.sleep(150);
        assertThat(MODEL.calls.get()).isLessThanOrEqualTo(after+2); // the load stopped with the cancel
        assertThat(perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(runs(id)+"/"+run+"/cancel"),200).path("state").asText()).isEqualTo("CANCELLED");

        // Another model on the same node waits for the old Pod to disappear.
        String other=registerModel().path("id").asText();
        assertThat(post(runs(other),UUID.randomUUID().toString(),httpRun(node),409).path("code").asText()).isEqualTo("PROFILING_NODE_BUSY");
        KUBE.unavailable=true;
        reconciler.advance(UUID.fromString(run));
        assertThat(run(id,run).path("cleanup").path("state").asText()).isEqualTo("PENDING"); // unknown is not removed
        KUBE.unavailable=false;
        cleanUp(id,run);
        seedNodes();
        assertThat(post(runs(other),UUID.randomUUID().toString(),httpRun(node),201).path("state").asText()).isEqualTo("PENDING");
        var latest=get(runs(other)).path("items").get(0).path("id").asText();
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(runs(other)+"/"+latest+"/cancel").contentType("application/json").content("{}"),200);
        cleanUp(other,latest);
    }

    @Test void httpLoadOfAnotherProcessIsInterruptedOnlyAfterItsLeaseExpires() throws Exception {
        String id=registerModel().path("id").asText();
        String run=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        assertThat(measuring(id,run).path("state").asText()).isEqualTo("MEASURING");
        reconciler.stopDriver(UUID.fromString(run)); // as if this process had restarted
        jdbc.update("UPDATE edgeai.ai_model_profiling_run SET driver_owner=?,driver_lease_until=? WHERE id=?",UUID.randomUUID(),
            java.sql.Timestamp.from(clock.instant().plusSeconds(20)),UUID.fromString(run));
        assertThat(step(id,run).path("state").asText()).isEqualTo("MEASURING"); // a live lease of another process
        clock.advance(21);
        var failed=step(id,run);
        assertThat(failed.path("failure").path("code").asText()).isEqualTo("DRIVER_INTERRUPTED");
        cleanUp(id,run);
    }

    /**
     * Two API processes (two reconcilers on one database). Process A read a PENDING run and is about to create its Job when
     * process B records the cancel, finds nothing in Kubernetes, completes cleanup and releases the slots; a new run of the
     * same model on the same node is admitted and measured. A's create then lands: the Job exists but suspended, so no Pod
     * of the old run ever runs beside the new one, A's stale state write is refused and orphan cleanup deletes the Job.
     * Likewise a resume held past the cancel and the delete is refused and does not recreate the Job.
     */
    @Test void createOrResumeDelayedPastCancelAndCleanupNeverRunsBesideTheNextRun() throws Exception {
        String id=registerModel().path("id").asText();
        String old=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        var slow=new HeldCreate(kubernetes);
        var processA=new ModelProfilingReconciler(repository,settings,provider(slow),drivers,metrics,nodeMetrics,clock,0,true,false);
        var a=Thread.ofVirtual().start(()->processA.advance(UUID.fromString(old)));
        assertThat(slow.entered.await(10,TimeUnit.SECONDS)).isTrue(); // A read PENDING and is about to POST

        assertThat(cancel(id,old).path("state").asText()).isEqualTo("CANCELLED");
        cleanUp(id,old); // B: neither Job nor Pod exists yet, so the slots are released
        seedNodes();
        String next=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        assertThat(measuring(id,next).path("state").asText()).isEqualTo("MEASURING");

        slow.release.countDown(); // A's delayed create finally reaches Kubernetes
        a.join(java.time.Duration.ofSeconds(20));
        assertThat(a.isAlive()).isFalse();
        assertThat(KUBE.job(old)).as("the late create did land").isNotNull();
        assertThat(KUBE.job(old).path("spec").path("suspend").asBoolean()).isTrue();
        assertThat(KUBE.podNow(old)).as("a suspended Job has no Pod").isNull();
        assertThat(KUBE.requests).noneMatch(r->r.startsWith("PATCH") && r.contains(old));
        var stale=run(id,old);
        assertThat(stale.path("state").asText()).isEqualTo("CANCELLED");
        assertThat(stale.path("kubernetes").path("jobUid").isNull()).isTrue(); // A's write was refused
        processA.advance(UUID.fromString(old));
        assertThat(KUBE.podNow(old)).isNull();
        assertThat(run(id,next).path("state").asText()).isEqualTo("MEASURING");
        reconciler.reconcile(); // orphan cleanup: a Job of a cleaned run
        for(int i=0;i<100 && KUBE.job(old)!=null;i++)Thread.sleep(50);
        assertThat(KUBE.job(old)).isNull();
        cancel(id,next);
        cleanUp(id,next);

        // Resume: A recorded STARTING and read the suspended Job; its PATCH is held while B cancels and deletes the Job.
        seedNodes();
        String third=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        KUBE.patchArrived=new CountDownLatch(1);KUBE.holdPatch=new CountDownLatch(1);
        var b=Thread.ofVirtual().start(()->processA.advance(UUID.fromString(third)));
        try {
            assertThat(KUBE.patchArrived.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(run(id,third).path("state").asText()).isEqualTo("STARTING");
            cancel(id,third);
            cleanUp(id,third);
        } finally { KUBE.holdPatch.countDown(); }
        b.join(java.time.Duration.ofSeconds(20));
        assertThat(KUBE.job(third)).as("a PATCH never recreates a deleted Job").isNull();
        assertThat(KUBE.podNow(third)).isNull();
        assertThat(KUBE.rejectedPatches).contains("edgeai-mp-"+third);
        assertThat(run(id,third).path("cleanup").path("state").asText()).isEqualTo("DONE");
    }
    private JsonNode cancel(String model,String run) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(runs(model)+"/"+run+"/cancel").contentType("application/json").content("{}"),200);
    }
    static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(){return value;}
            @Override public T getObject(Object... args){return value;}
            @Override public T getIfAvailable(){return value;}
            @Override public T getIfUnique(){return value;}
        };
    }
    /** The gateway of another API process whose Job create is held (a paused process or a slow network) until released. */
    static final class HeldCreate implements ProfilingGateway {
        final ProfilingGateway delegate;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        HeldCreate(ProfilingGateway delegate){this.delegate=delegate;}
        @Override public String namespace(){return delegate.namespace();}
        @Override public UUID ensureJob(ProfilingJob job) {
            entered.countDown();
            try{if(!release.await(30,TimeUnit.SECONDS))throw new IllegalStateException("not released");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            return delegate.ensureJob(job);
        }
        @Override public boolean resume(UUID runId,UUID jobUid,java.util.function.BooleanSupplier admitted){return delegate.resume(runId,jobUid,admitted);}
        @Override public Optional<Observation> observe(UUID runId){return delegate.observe(runId);}
        @Override public boolean delete(UUID runId){return delegate.delete(runId);}
        @Override public Set<UUID> ownedRuns(){return delegate.ownedRuns();}
    }

    @Test void historyIsImmutableAndOnlySucceededRunsBackProfiles() throws Exception {
        String id=registerModel().path("id").asText();
        String run=post(runs(id),UUID.randomUUID().toString(),httpRun(node),201).path("id").asText();
        step(id,run);KUBE.pod(run).waiting="ErrImagePull";step(id,run);
        cleanUp(id,run);
        var runId=UUID.fromString(run);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.ai_model_profiling_run SET result='{}'::jsonb WHERE id=?",runId)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.ai_model_profiling_run SET state='SUCCEEDED' WHERE id=?",runId)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM edgeai.ai_model_profiling_run WHERE id=?",runId)).hasMessageContaining("history");
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.ai_model_resource_profile(id,model_id,revision,run_id,idempotency_key,request_fingerprint,digest,document,created_at) VALUES (?,?,1,?,?,?,?,'{}'::jsonb,now())",
            UUID.randomUUID(),UUID.fromString(id),runId,UUID.randomUUID(),"sha256:"+"0".repeat(64),"sha256:"+"0".repeat(64))).hasMessageContaining("succeeded run");
        assertThat(post(runs(id),UUID.randomUUID().toString(),Map.of("nodeName",node,"allocation",Map.of("cpuMillicores",1000,"memoryBytes",1L<<30),
            "load",Map.of("type","HTTP","port",8080,"path","/x","method","GET"),"volumes",List.of()),400).path("code").asText()).isEqualTo("INVALID_PROFILING_RUN");
        var tooBig=httpRun(node);tooBig.put("allocation",Map.of("cpuMillicores",8000,"memoryBytes",16L<<30,"accelerator",Map.of("resourceName","nvidia.com/gpu","count",2)));
        assertThat(post(runs(id),UUID.randomUUID().toString(),tooBig,409).path("code").asText()).isEqualTo("PROFILING_ACCELERATOR_UNAVAILABLE");
        var arm=httpRun(node+"-arm");
        assertThat(post(runs(id),UUID.randomUUID().toString(),arm,409).path("code").asText()).isEqualTo("PROFILING_NODE_INCOMPATIBLE");
    }

    // ---- fakes --------------------------------------------------------------------------------------------------------

    static Map<String,String> query(String raw) {
        var map=new HashMap<String,String>();
        if(raw==null)return map;
        for(String part:raw.split("&")){int i=part.indexOf('=');if(i>0)map.put(URLDecoder.decode(part.substring(0,i),StandardCharsets.UTF_8),URLDecoder.decode(part.substring(i+1),StandardCharsets.UTF_8));}
        return map;
    }
    static void reply(HttpExchange exchange,int status,Object body,JsonMapper json) throws java.io.IOException {
        byte[] bytes=body==null?new byte[0]:json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);
        if(bytes.length>0)exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * Stateful Kubernetes API subset: Jobs by name with resourceVersions, suspended Jobs that get their one Pod only when a
     * merge patch with the current UID and resourceVersion resumes them, terminating Pods after a delete.
     */
    static final class FakeKubernetes {
        final HttpServer server;
        final JsonMapper json=new JsonMapper();
        final Map<String,JsonNode> jobs=new ConcurrentHashMap<>();
        final Map<String,PodScenario> pods=new ConcurrentHashMap<>();
        final List<String> requests=new CopyOnWriteArrayList<>();
        final List<String> rejectedPatches=new CopyOnWriteArrayList<>();
        final AtomicInteger versions=new AtomicInteger();
        volatile boolean unavailable;
        /** When set, a PATCH is held after it arrives (signalled on patchArrived) until holdPatch opens. */
        volatile CountDownLatch holdPatch,patchArrived;
        FakeKubernetes() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/",this::handle);
            server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        JsonNode job(String run){return jobs.get("edgeai-mp-"+run);}
        /** The run's Pod, waiting briefly: the service's own wake after a start may resume the Job just after a test step. */
        PodScenario pod(String run) {
            for(int i=0;i<100 && !pods.containsKey("edgeai-mp-"+run);i++)try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            return pods.get("edgeai-mp-"+run);
        }
        /** The run's Pod as of now, for asserting that none exists. */
        PodScenario podNow(String run){return pods.get("edgeai-mp-"+run);}
        private void handle(HttpExchange exchange) throws java.io.IOException {
            String method=exchange.getRequestMethod(),path=exchange.getRequestURI().getPath();
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            requests.add(method+" "+path+" "+body);
            if(unavailable){reply(exchange,503,null,json);return;}
            String jobsPath="/apis/batch/v1/namespaces/"+NS+"/jobs",podsPath="/api/v1/namespaces/"+NS+"/pods";
            if(method.equals("POST") && path.equals(jobsPath)) {
                var job=(tools.jackson.databind.node.ObjectNode)json.readTree(body);
                String name=job.path("metadata").path("name").asText();
                if(jobs.containsKey(name)){reply(exchange,409,null,json);return;}
                ((tools.jackson.databind.node.ObjectNode)job.path("metadata")).put("uid",UUID.randomUUID().toString())
                    .put("resourceVersion",Integer.toString(versions.incrementAndGet()));
                jobs.put(name,job);
                if(!job.path("spec").path("suspend").asBoolean(false))pods.put(name,new PodScenario(name,job));
                reply(exchange,201,job,json);return;
            }
            if(method.equals("PATCH") && path.startsWith(jobsPath+"/")) {
                var arrived=patchArrived;var hold=holdPatch;
                if(arrived!=null)arrived.countDown();
                if(hold!=null)try{hold.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                String name=path.substring(jobsPath.length()+1);
                if(!"application/merge-patch+json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))){reply(exchange,415,null,json);return;}
                synchronized(this) {
                    var job=jobs.get(name);var patch=json.readTree(body).path("metadata");
                    if(job==null){rejectedPatches.add(name);reply(exchange,404,null,json);return;}
                    if(!patch.path("uid").asText().equals(job.path("metadata").path("uid").asText())
                        || !patch.path("resourceVersion").asText().equals(job.path("metadata").path("resourceVersion").asText())){rejectedPatches.add(name);reply(exchange,409,null,json);return;}
                    var resumed=(tools.jackson.databind.node.ObjectNode)job.deepCopy();
                    ((tools.jackson.databind.node.ObjectNode)resumed.path("spec")).put("suspend",false);
                    ((tools.jackson.databind.node.ObjectNode)resumed.path("metadata")).put("resourceVersion",Integer.toString(versions.incrementAndGet()));
                    jobs.put(name,resumed);
                    pods.putIfAbsent(name,new PodScenario(name,resumed));
                    reply(exchange,200,resumed,json);return;
                }
            }
            if(method.equals("GET") && path.startsWith(jobsPath+"/")) {
                var job=jobs.get(path.substring(jobsPath.length()+1));
                if(job==null){reply(exchange,404,null,json);return;}
                var copy=job.deepCopy();
                var pod=pods.get(job.path("metadata").path("name").asText());
                if(pod!=null && pod.jobFailed!=null)((tools.jackson.databind.node.ObjectNode)copy).set("status",json.valueToTree(Map.of("conditions",List.of(Map.of("type","Failed","status","True","reason",pod.jobFailed)))));
                reply(exchange,200,copy,json);return;
            }
            if(method.equals("GET") && (path.equals(jobsPath) || path.equals(podsPath))) {
                var selector=selector(query(exchange.getRequestURI().getRawQuery()).get("labelSelector"));
                var items=new ArrayList<Object>();
                if(path.equals(jobsPath)){for(var job:jobs.values())if(matches(job.path("metadata").path("labels"),selector))items.add(job);}
                else for(var pod:pods.values())if(pod.visible() && matches(pod.labels,selector))items.add(pod.json());
                reply(exchange,200,Map.of("metadata",Map.of(),"items",items),json);return;
            }
            if(method.equals("DELETE") && path.startsWith(jobsPath+"/")) {
                String name=path.substring(jobsPath.length()+1);var job=jobs.get(name);
                if(job==null){reply(exchange,404,null,json);return;}
                if(!json.readTree(body).path("preconditions").path("uid").asText().equals(job.path("metadata").path("uid").asText())){reply(exchange,409,null,json);return;}
                synchronized(this){jobs.remove(name);}
                var pod=pods.get(name);if(pod!=null)pod.terminating=1; // still listed once, like a grace period
                reply(exchange,200,Map.of(),json);return;
            }
            reply(exchange,404,null,json);
        }
        private static Map<String,String> selector(String value) {
            var map=new HashMap<String,String>();
            for(String part:value.split(",")){var kv=part.split("=",2);map.put(kv[0],kv[1]);}
            return map;
        }
        private static boolean matches(JsonNode labels,Map<String,String> selector) {
            return selector.entrySet().stream().allMatch(e->e.getValue().equals(labels.path(e.getKey()).asText(null)));
        }
    }
    static final class PodScenario {
        final String name,node;final UUID uid=UUID.randomUUID();final JsonNode labels;final String jobUid,image;
        volatile String phase="Pending",waiting="ContainerCreating",terminated,jobFailed,startedAt,finishedAt;
        volatile Integer exit;volatile Integer terminating;
        PodScenario(String job,JsonNode spec) {
            name=job+"-x7k2p";labels=spec.path("spec").path("template").path("metadata").path("labels");jobUid=spec.path("metadata").path("uid").asText();
            image=spec.path("spec").path("template").path("spec").path("containers").path(0).path("image").asText();
            node=spec.path("spec").path("template").path("spec").path("affinity").path("nodeAffinity").path("requiredDuringSchedulingIgnoredDuringExecution")
                .path("nodeSelectorTerms").path(0).path("matchFields").path(0).path("values").path(0).asText();
        }
        void running(){phase="Running";waiting=null;startedAt=NOW.get().toString();}
        void oom(){phase="Failed";waiting=null;terminated="OOMKilled";exit=137;startedAt=NOW.get().toString();finishedAt=startedAt;jobFailed="BackoffLimitExceeded";}
        void completed(int code,long seconds) {
            var start=Instant.parse(startedAt);phase=code==0?"Succeeded":"Failed";terminated=code==0?"Completed":"Error";exit=code;
            finishedAt=start.plusSeconds(seconds).toString();
        }
        boolean visible(){var t=terminating;if(t==null)return true;if(t>0){terminating=t-1;return true;}return false;}
        Map<String,Object> json() {
            var state=new LinkedHashMap<String,Object>();
            if(terminated!=null)state.put("terminated",Map.of("reason",terminated,"exitCode",exit,"startedAt",startedAt,"finishedAt",finishedAt));
            else if(waiting!=null)state.put("waiting",Map.of("reason",waiting));
            else state.put("running",Map.of("startedAt",startedAt));
            var status=new LinkedHashMap<String,Object>();
            status.put("phase",phase);
            if(!phase.equals("Pending"))status.put("podIP","127.0.0.1");
            status.put("containerStatuses",List.of(Map.of("name","model","restartCount",0,"imageID",image,"state",state)));
            return Map.of("metadata",Map.of("name",name,"uid",uid.toString(),"labels",labels,"ownerReferences",List.of(Map.of("kind","Job","uid",jobUid))),
                "spec",Map.of("nodeName",node),"status",status);
        }
    }

    /** Prometheus query API subset answering for the Pods the fake Kubernetes created: CPU 200–300 millicores (p95 240), 400 MiB. */
    static final class FakePrometheus {
        static final double MEMORY=400.0*1024*1024;
        final HttpServer server;final JsonMapper json=new JsonMapper();
        final List<String> queries=new CopyOnWriteArrayList<>();
        volatile boolean empty;
        FakePrometheus() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/",this::handle);
            server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        private void handle(HttpExchange exchange) throws java.io.IOException {
            var form=query(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String q=form.get("query");queries.add(q);
            var pods=KUBE.pods.values().stream().filter(p->q.contains(p.name) || q.contains(p.name.substring(0,p.name.length()-6))).toList();
            var result=new ArrayList<Object>();
            boolean range=exchange.getRequestURI().getPath().endsWith("query_range");
            if(!range) {
                for(var p:pods) {
                    if(q.contains("kube_pod_owner"))result.add(Map.of("metric",Map.of("pod",p.name,"owner_kind","Job","owner_name",p.name.substring(0,p.name.length()-6)),"value",List.of(0,"1")));
                    if(q.contains("kube_pod_info"))result.add(Map.of("metric",Map.of("pod",p.name,"uid",p.uid.toString(),"node","n"),"value",List.of(0,"1")));
                }
                reply(exchange,200,Map.of("status","success","data",Map.of("resultType","vector","result",result)),json);return;
            }
            long start=Long.parseLong(form.get("start")),end=Long.parseLong(form.get("end")),step=Long.parseLong(form.get("step"));
            if(!empty && (q.contains("container_cpu_usage_seconds_total") || q.contains("container_memory_working_set_bytes")))
                for(var p:pods) {
                    var values=new ArrayList<Object>();int i=0;
                    for(long t=start;t<=end;t+=step,i++) {
                        double v=q.contains("cpu")?(i==0?300:200+(i%5)*10):MEMORY;
                        values.add(List.of(t,Double.toString(v)));
                    }
                    result.add(Map.of("metric",Map.of("pod",p.name),"values",values));
                }
            reply(exchange,200,Map.of("status","success","data",Map.of("resultType","matrix","result",result)),json);
        }
    }

    /** A real local inference endpoint; the status is switchable to simulate a failing server. */
    static final class ModelServer {
        final HttpServer server;final AtomicInteger calls=new AtomicInteger();
        volatile int status=200;
        ModelServer() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/v2/models/digits-centroid/infer",exchange->{
                exchange.getRequestBody().readAllBytes();calls.incrementAndGet();
                byte[] body="{\"outputs\":[{\"name\":\"class\",\"data\":[3]}]}".getBytes();
                exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.start();
        }
        int port(){return server.getAddress().getPort();}
    }
}
