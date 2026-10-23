package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.service.*;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.DeviceResourceGateway;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.ExecutionRepository;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Public video-session API on real PostgreSQL with the actual Spring security, workflow, runtime lifecycle and Runner
 * HTTP chains. TEST-ONLY boundaries: the KubeEdge CRD gateway (one camera), Kubernetes pod proof and S3 receipts.
 * No real Job, camera frame or model runs here; those are verified live.
 */
@SpringBootTest(properties = {"edgeai.runtime.enabled=true", "edgeai.runtime.worker-enabled=false",
    "edgeai.runtime.workflow-keys=" + HawkeyeVideoSessionIntegrationTest.KEY,
    "edgeai.hawkeye-video.enabled=true", "edgeai.hawkeye-video.workflow-key=" + HawkeyeVideoSessionIntegrationTest.KEY,
    "edgeai.hawkeye-video.max-windows=3", "edgeai.hawkeye-video.poll=100ms"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class HawkeyeVideoSessionIntegrationTest {
    static final String KEY = "hawkeye-video-it";
    static final String OTHER = "hawkeye-video-it-outside";
    static final String BASE = "/api/v1/scenarios/hawkeye-safety/video";
    private static final Path RUNNER_KEY = key();
    private static final String NAMESPACE = "video-test-" + UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("edgeai.runtime.key-file", RUNNER_KEY::toString);
        registry.add("edgeai.runtime.namespace", () -> NAMESPACE);
    }
    private static Path key() {
        try {
            var path = Files.createTempFile("edgeai-runner-key-video-", ".key"); byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key); Files.writeString(path, HexFormat.of().formatHex(key)); return path;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @AfterAll static void removeKey() throws Exception { Files.deleteIfExists(RUNNER_KEY); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProfileService profiles;
    @Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;
    @Autowired ExecutionRepository runs;
    @Autowired RuntimeRepository runtimes;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RunnerTokenService tokens;
    @MockitoBean RuntimeGateway gateway;
    @MockitoBean S3ArtifactStore storage;
    @MockitoBean DeviceResourceGateway devices;
    private final JsonDocuments json = new JsonDocuments();
    private final Map<UUID, RuntimePod> pods = new ConcurrentHashMap<>();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final UUID cameraUid = UUID.randomUUID();
    private String cameraState = "online";
    private Map<String, Object> cameraResource;
    private UUID version, otherVersion;

    @BeforeEach void boundaries() throws Exception {
        when(gateway.authenticatePod(any(), any())).thenAnswer(call -> {
            RuntimeInstance runtime = call.getArgument(0);
            if (!"pod-proof-fixture".equals(call.getArgument(1)) || !pods.containsKey(runtime.attemptId()))
                throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);
            return pods.get(runtime.attemptId());
        });
        when(storage.upload(any())).thenAnswer(call -> new ArtifactGrant(URI.create("http://storage.fixture/upload"), Map.of("Content-Type", "application/octet-stream"), Instant.now().plusSeconds(600)));
        when(storage.verify(any(), any())).thenAnswer(call -> { ArtifactContent c = call.getArgument(0);
            return new VerifiedArtifact("fixture-only", c.objectKey(), call.getArgument(1), c.sha256(), c.bytes(), c.mediaType()); });
        when(storage.download(any())).thenAnswer(call -> new ArtifactGrant(URI.create("http://storage.fixture/download"), Map.of(), Instant.now().plusSeconds(600)));
        doAnswer(call -> { VerifiedArtifact a = call.getArgument(0); Files.write(call.getArgument(1), objects.get(a.sha256())); return null; })
            .when(storage).downloadFile(any(), any());
        camera();
        version = publish(KEY);
        otherVersion = publish(OTHER);
    }
    @AfterEach void settle() {
        for (var id : jdbc.queryForList("SELECT id FROM edgeai.hawkeye_video_session WHERE state='RUNNING'", UUID.class))
            try { mvc.perform(post(BASE + "/sessions/" + id + "/stop").with(user("fixture")).with(csrf()).contentType("application/json").content("{}")); }
            catch (Exception e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private UUID publish(String workflowKey) throws Exception {
        var definition = (Map<String, Object>) json.decode(Files.readString(Path.of("../../hawkeye-runtime/workflow/hawkeye-video-safety.json")));
        var ids = new HashMap<String, String>();
        for (var profile : (List<Map<String, Object>>) definition.get("profiles")) {
            String key = workflowKey + "-" + profile.get("key");
            ids.put((String) profile.get("key"), profiles.publish(ProfileIdentity.Kind.SERVICE, json.canonical(Map.of("key", key, "version", "1.0.0", "spec", profile.get("spec")))).version().id().toString());
        }
        var w = workflows.create(json.canonical(Map.of("key", workflowKey, "displayName", "Hawkeye video IT"))).value();
        var v = (Map<String, Object>) definition.get("version");
        var tasks = ((List<Map<String, Object>>) v.get("tasks")).stream().map(t -> Map.of("key", t.get("key"), "serviceProfileVersionId",
            ids.get(t.get("profile")), "parameters", t.get("parameters"))).toList();
        return workflows.publish(w.id(), json.canonical(Map.of("version", "1.0.0", "tasks", tasks, "dependencies", v.get("dependencies")))).value().id();
    }

    /** One owned RTSP camera on an edge node; its state is what DeviceStatus reports. */
    private void camera() {
        var compiled = DeviceProfileCompiler.compile(Map.of("name", "forklift-camera", "targetNode", "edge-a", "protocol", "rtsp", "host", "192.0.2.10",
            "protocolConfig", Map.of("path", "/forklift"), "properties", List.of(Map.of("name", "connected", "dataType", "bool", "jsonPath", "connected"))),
            "edgeai-devices", "tcp://127.0.0.1:1883");
        var resource = new TreeMap<>(ObservationIdentity.qualify(compiled.deviceResource(), null));
        var metadata = new TreeMap<>(DeviceProfileCompiler.map(resource.get("metadata"))); metadata.put("uid", cameraUid.toString()); resource.put("metadata", metadata);
        cameraResource = resource;
        String base = "/apis/devices.kubeedge.io/v1beta1/namespaces/edgeai-devices/";
        when(devices.request(eq("GET"), anyString(), isNull())).thenAnswer(call -> {
            String path = call.getArgument(1);
            Object document = path.equals(base + "devices/forklift-camera") ? cameraResource
                : path.equals(base + "devicestatuses/forklift-camera") ? Map.of("status", Map.of("state", cameraState, "lastOnlineTime", Instant.now().toString()))
                : path.equals("/api/v1/nodes/edge-a") ? Map.of("metadata", Map.of("labels", Map.of("node-role.kubernetes.io/edge", "")),
                    "status", Map.of("addresses", List.of(Map.of("type", "InternalIP", "address", "192.0.2.77"))))
                : path.startsWith("/apis/apps/v1/namespaces/edgeai-devices/deployments/") ? Map.of("status", Map.of("readyReplicas", 1)) : null;
            return document == null ? new DeviceResourceGateway.Reply(404, "{}") : new DeviceResourceGateway.Reply(200, json.canonical(document));
        });
    }

    JsonNodeLike body(ResultActions actions) throws Exception { return new JsonNodeLike(json.decode(actions.andReturn().getResponse().getContentAsString())); }
    ResultActions start(UUID key, UUID workflowVersion, UUID uid) throws Exception {
        return mvc.perform(post(BASE + "/sessions").with(user("fixture")).with(csrf()).header("Idempotency-Key", key.toString()).contentType("application/json")
            .content(json.canonical(Map.of("workflowVersionId", workflowVersion.toString(), "camera", Map.of("id", "forklift-camera", "uid", uid.toString())))));
    }
    JsonNodeLike session(UUID id) throws Exception { return body(mvc.perform(get(BASE + "/sessions/" + id).with(user("fixture"))).andExpect(status().isOk())); }
    UUID task(UUID run, String key) { return executions.detail(run).tasks().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow().id(); }
    UUID attempt(UUID task) { return executions.taskDetail(task).attempts().getFirst().id(); }

    private String runner(UUID attempt, String operation, Object value, int expected) throws Exception {
        String credential = tokens.issue(runtimes.byAttempt(attempt).orElseThrow());
        return mvc.perform(post("/internal/v1/attempts/" + attempt + "/" + operation).header("Authorization", "Bearer " + credential)
            .header("X-EdgeAI-Pod-Token", "pod-proof-fixture").contentType("application/json").content(json.canonical(value)))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    /** Claims the Task's Attempt as a Runner Pod and commits one output file; returns the claim assignment. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> complete(UUID task, String port, String mediaType, byte[] content) throws Exception {
        UUID attempt = attempt(task); var pod = new RuntimePod(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "runner-node-" + port);
        pods.put(attempt, pod); var identity = Map.of("epoch", 1, "podUid", pod.podUid().toString());
        var assignment = (Map<String, Object>) json.decode(runner(attempt, "claim", identity, 200));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); objects.put(sha, content);
        var output = new LinkedHashMap<String, Object>(Map.of("port", port, "bytes", content.length, "sha256", sha, "mediaType", mediaType));
        var body = new LinkedHashMap<String, Object>(identity); body.put("outputs", List.of(output)); runner(attempt, "uploads", body, 200);
        output.put("versionId", "fixture-version-" + sha.substring(0, 8)); runner(attempt, "commit", body, 201);
        return assignment;
    }
    private void failTask(UUID task) throws Exception {
        UUID attempt = attempt(task); var pod = new RuntimePod(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "runner-node");
        pods.put(attempt, pod); var identity = Map.of("epoch", 1, "podUid", pod.podUid().toString());
        runner(attempt, "claim", identity, 200);
        var body = new LinkedHashMap<String, Object>(identity); body.put("reason", "WORKLOAD_FAILED"); runner(attempt, "fail", body, 200);
    }
    static byte[] jpeg(int seed) { var b = new byte[64]; Arrays.fill(b, (byte) seed); b[0] = (byte) 0xff; b[1] = (byte) 0xd8; b[62] = (byte) 0xff; b[63] = (byte) 0xd9; return b; }
    static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    /** The capture workload's ustar artifact: manifest.json + frame-NNNN.jpg. */
    static byte[] frames(Map<String, Object> source, byte[]... images) throws Exception {
        var frames = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < images.length; i++) frames.add(Map.of("seq", i + 1, "file", "frame-%04d.jpg".formatted(i + 1), "sha256", sha(images[i]), "frameTime", "t" + i));
        var out = new ByteArrayOutputStream();
        entry(out, "manifest.json", new JsonDocuments().canonical(Map.of("apiVersion", "edgeai.hawkeye.frames/v1", "source", source, "frames", frames)).getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < images.length; i++) entry(out, "frame-%04d.jpg".formatted(i + 1), images[i]);
        out.write(new byte[1024]); return out.toByteArray();
    }
    static void entry(ByteArrayOutputStream out, String name, byte[] data) {
        var header = new byte[512]; System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, header, 0, name.length());
        var size = "%011o".formatted(data.length).getBytes(StandardCharsets.US_ASCII); System.arraycopy(size, 0, header, 124, size.length);
        header[156] = '0'; System.arraycopy("ustar\u000000".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 8);
        out.writeBytes(header); out.writeBytes(data); out.writeBytes(new byte[(512 - data.length % 512) % 512]);
    }
    JsonNodeLike awaitSession(UUID id, java.util.function.Predicate<JsonNodeLike> done) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos(); JsonNodeLike value;
        do { value = session(id); if (done.test(value)) return value; Thread.sleep(50); } while (System.nanoTime() < deadline);
        throw new AssertionError("Session " + id + " stayed " + value.get("state") + " with " + value.get("windowCount") + " windows");
    }

    @Test @SuppressWarnings("unchecked")
    void windowsAreRealRunsOfThePublishedDagAndRollUntilTheBound() throws Exception {
        var capability = body(mvc.perform(get(BASE).with(user("fixture"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")));
        assertThat(capability.get("enabled")).isEqualTo(true); assertThat(capability.get("executes")).isEqualTo(true);
        assertThat(capability.path("workflow", "key")).isEqualTo(KEY);
        assertThat(((List<Map<String, Object>>) capability.get("versions")).stream().map(v -> v.get("id"))).contains(version.toString());

        var started = body(start(UUID.randomUUID(), version, cameraUid).andExpect(status().isAccepted()));
        UUID id = UUID.fromString((String) started.get("id"));
        assertThat(started.get("state")).isEqualTo("RUNNING"); assertThat(((Number) started.get("windowCount")).intValue()).isEqualTo(1);
        for (int seq = 1; seq <= 3; seq++) {
            int current = seq;
            var s = awaitSession(id, v -> ((Number) v.get("windowCount")).intValue() == current);
            UUID run = UUID.fromString((String) s.windows().getFirst().get("runId"));
            assertThat(((Number) s.windows().getFirst().get("seq")).intValue()).isEqualTo(seq);
            // The camera identity is pinned into the Run by the server; the capture Task is dispatched by the runtime.
            var parameters = (Map<String, Object>) json.decode(runs.run(run, false).orElseThrow().parametersJson());
            var source = (Map<String, Object>) parameters.get("cameraSource");
            assertThat(source).containsEntry("deviceId", "forklift-camera").containsEntry("deviceUid", cameraUid.toString())
                .containsEntry("mapperUrl", "http://192.0.2.77:7783").containsEntry("nodeName", "edge-a")
                .containsEntry("mapperDeployment", MapperDeployment.nameFor("edge-a", "rtsp"));
            assertThat((String) source.get("configurationSha256")).matches("sha256:[0-9a-f]{64}");
            UUID capture = task(run, "capture"), infer = task(run, "infer");
            assertThat(runtimes.byAttempt(attempt(capture))).isPresent();
            var claim = complete(capture, "frames", "application/x-tar", frames(source, jpeg(seq * 2), jpeg(seq * 2 + 1)));
            var claimed = (Map<String, Object>) claim.get("parameters");
            assertThat(((Number) claimed.get("frames")).intValue()).isEqualTo(10);
            assertThat(claimed.get("cameraSource")).isEqualTo(source);
            // The infer Task receives the capture output through the BATCH edge and is placed separately.
            var inferClaim = complete(infer, "detections", "application/json", "{\"frames\":[]}".getBytes(StandardCharsets.UTF_8));
            assertThat((Map<String, Object>) inferClaim.get("inputs")).containsKey("frames");
            assertThat(executions.detail(run).run().state()).isEqualTo("SUCCEEDED");
            var placements = body(mvc.perform(get("/api/v1/workflow-runs/" + run + "/placements").with(user("fixture"))).andExpect(status().isOk()));
            assertThat(placements.toString()).contains("runner-node-frames", "runner-node-detections");
            // Verified frames are served from the committed capture Result only.
            byte[] frame = mvc.perform(get(BASE + "/tasks/" + capture + "/frames/2").with(user("fixture"))).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg")).andReturn().getResponse().getContentAsByteArray();
            assertThat(frame).isEqualTo(jpeg(seq * 2 + 1));
            mvc.perform(get(BASE + "/tasks/" + infer + "/frames/1").with(user("fixture"))).andExpect(status().isNotFound());
        }
        var done = awaitSession(id, v -> !"RUNNING".equals(v.get("state")));
        assertThat(done.get("state")).isEqualTo("COMPLETED"); assertThat(done.get("endReason")).isEqualTo("MAX_WINDOWS");
        assertThat(done.windows()).hasSize(3);
        Thread.sleep(400);
        assertThat(session(id).windows()).hasSize(3);
        var list = body(mvc.perform(get(BASE + "/sessions").with(user("fixture"))).andExpect(status().isOk()));
        assertThat(list.toString()).contains(id.toString());
    }

    @Test
    void stopEndsTheSessionCancelsTheActiveWindowAndCreatesNoNextRun() throws Exception {
        UUID id = UUID.fromString((String) body(start(UUID.randomUUID(), version, cameraUid).andExpect(status().isAccepted())).get("id"));
        UUID run = UUID.fromString((String) session(id).windows().getFirst().get("runId"));
        var stopped = body(mvc.perform(post(BASE + "/sessions/" + id + "/stop").with(user("fixture")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isOk()));
        assertThat(stopped.get("state")).isEqualTo("STOPPED"); assertThat(stopped.get("endReason")).isEqualTo("USER_STOPPED");
        assertThat(executions.detail(run).run().state()).isIn("CANCELLED", "CANCELLING");
        Thread.sleep(400);
        assertThat(session(id).windows()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.hawkeye_video_window WHERE session_id=?", Integer.class, id)).isEqualTo(1);
        // A repeated stop is a no-op; an ended session is immutable.
        mvc.perform(post(BASE + "/sessions/" + id + "/stop").with(user("fixture")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("UPDATE edgeai.hawkeye_video_session SET state='RUNNING',ended_at=NULL,end_reason=NULL WHERE id=?", id))
            .hasMessageContaining("immutable");
    }

    @Test
    void aFailedWindowEndsTheSessionInsteadOfSubstitutingInput() throws Exception {
        UUID id = UUID.fromString((String) body(start(UUID.randomUUID(), version, cameraUid).andExpect(status().isAccepted())).get("id"));
        UUID run = UUID.fromString((String) session(id).windows().getFirst().get("runId"));
        failTask(task(run, "capture"));
        var failed = awaitSession(id, v -> !"RUNNING".equals(v.get("state")));
        assertThat(failed.get("state")).isEqualTo("FAILED"); assertThat(failed.get("endReason")).isEqualTo("WINDOW_FAILED");
        assertThat(failed.get("failureCode")).isEqualTo("WINDOW_FAILED");
        assertThat(failed.windows()).hasSize(1);
        assertThat(runtimes.result(task(run, "infer"))).isEmpty();
    }

    @Test @SuppressWarnings("unchecked")
    void aCameraReconfiguredUnderTheSameUidEndsTheSessionWithoutANextWindow() throws Exception {
        UUID id = UUID.fromString((String) body(start(UUID.randomUUID(), version, cameraUid).andExpect(status().isAccepted())).get("id"));
        UUID run = UUID.fromString((String) session(id).windows().getFirst().get("runId"));
        var source = (Map<String, Object>) ((Map<String, Object>) json.decode(runs.run(run, false).orElseThrow().parametersJson())).get("cameraSource");
        complete(task(run, "capture"), "frames", "application/x-tar", frames(source, jpeg(1)));
        // The device keeps its UID but its source generation changes before the next window is admitted.
        var metadata = new TreeMap<>(DeviceProfileCompiler.map(cameraResource.get("metadata")));
        var annotations = new TreeMap<>(DeviceProfileCompiler.map(metadata.get("annotations")));
        annotations.put(ObservationIdentity.ANNOTATION, UUID.randomUUID().toString()); metadata.put("annotations", annotations);
        var changed = new TreeMap<>(cameraResource); changed.put("metadata", metadata); cameraResource = changed;
        complete(task(run, "infer"), "detections", "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        var ended = awaitSession(id, v -> !"RUNNING".equals(v.get("state")));
        assertThat(ended.get("state")).isEqualTo("FAILED"); assertThat(ended.get("endReason")).isEqualTo("WINDOW_REJECTED");
        assertThat(ended.get("failureCode")).isEqualTo("CAMERA_SOURCE_CHANGED");
        assertThat(ended.windows()).hasSize(1);
        var second = UUID.nameUUIDFromBytes(("edgeai-hawkeye-video:" + id + ":2").getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_run WHERE idempotency_key=?", Integer.class, second)).isZero();
        assertThat(jdbc.queryForObject("SELECT camera_source->>'sourceGeneration' FROM edgeai.hawkeye_video_session WHERE id=?", String.class, id))
            .isEqualTo(source.get("sourceGeneration"));
    }

    @Test
    void anUnavailableCameraIsRejectedAndLeavesNoSession() throws Exception {
        cameraState = "offline";
        UUID key = UUID.randomUUID();
        start(key, version, cameraUid).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKFLOW_SOURCE_UNAVAILABLE"));
        start(UUID.randomUUID(), version, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_IDENTITY_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.hawkeye_video_session WHERE idempotency_key=?", Integer.class, key)).isZero();
    }

    @Test
    void startIsIdempotentSingleActiveAndLimitedToTheVideoWorkflow() throws Exception {
        UUID key = UUID.randomUUID();
        var first = body(start(key, version, cameraUid).andExpect(status().isAccepted()));
        assertThat(body(start(key, version, cameraUid).andExpect(status().isOk())).get("id")).isEqualTo(first.get("id"));
        start(key, otherVersion, cameraUid).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        start(UUID.randomUUID(), version, cameraUid).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCENARIO_SESSION_ACTIVE"));
        start(UUID.randomUUID(), otherVersion, cameraUid).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKFLOW_NOT_ALLOWED"));
        mvc.perform(post(BASE + "/sessions").with(user("fixture")).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json").content("{\"workflowVersionId\":\"" + version + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/sessions").with(user("fixture")).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void theRuntimeScopeNeverDispatchesOrTouchesRunsOfOtherWorkflows() throws Exception {
        // A Run of another workflow is stored exactly as with the runtime disabled: no runtime row, no command.
        String body = json.canonical(Map.of("workflowVersionId", otherVersion.toString(), "parameters", Map.of(), "execution", Map.of("mode", "AUTO"),
            "camera", Map.of("id", "forklift-camera", "uid", cameraUid.toString())));
        var created = (Map<?, ?>) json.decode(mvc.perform(post("/api/v1/workflow-runs").with(user("fixture")).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID outside = UUID.fromString((String) created.get("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_instance WHERE run_id=?", Integer.class, outside)).isZero();
        assertThat(runtimes.inScope(outside)).isFalse();
        // Earlier history of such a Run (a runtime row with a pending CREATE command) stays invisible to every worker query.
        lifecycle.startRun(outside, NAMESPACE);
        var old = jdbc.queryForMap("SELECT c.id,c.attempts,c.lease_owner,r.observed_state,r.updated_at FROM edgeai.runtime_command c JOIN edgeai.runtime_instance r ON r.id=c.runtime_id WHERE r.run_id=?", outside);
        for (int i = 0; i < 5; i++) {
            var leased = runtimes.leaseCommand(NAMESPACE, UUID.randomUUID(), Instant.now(), Duration.ofSeconds(30));
            leased.ifPresent(c -> assertThat(runtimes.runtime(c.runtimeId()).orElseThrow().runId()).isNotEqualTo(outside));
        }
        assertThat(runtimes.active(NAMESPACE, 10000)).noneMatch(r -> r.runId().equals(outside));
        assertThat(runs.dueRetries(NAMESPACE, Instant.now().plusSeconds(86400), 1000)).doesNotContainAnyElementsOf(
            executions.detail(outside).tasks().stream().map(t -> t.id()).toList());
        assertThat(jdbc.queryForMap("SELECT c.id,c.attempts,c.lease_owner,r.observed_state,r.updated_at FROM edgeai.runtime_command c JOIN edgeai.runtime_instance r ON r.id=c.runtime_id WHERE r.run_id=?", outside))
            .isEqualTo(old);
        // A Run of the video workflow is dispatched and its command is leasable.
        String inside = json.canonical(Map.of("workflowVersionId", version.toString(), "parameters", Map.of(), "execution", Map.of("mode", "AUTO"),
            "camera", Map.of("id", "forklift-camera", "uid", cameraUid.toString())));
        var run = (Map<?, ?>) json.decode(mvc.perform(post("/api/v1/workflow-runs").with(user("fixture")).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json").content(inside)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID scoped = UUID.fromString((String) run.get("id"));
        assertThat(runtimes.inScope(scoped)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_instance WHERE run_id=?", Integer.class, scoped)).isEqualTo(1);
        // The camera binding is server-owned.
        String forged = json.canonical(Map.of("workflowVersionId", version.toString(), "parameters", Map.of("cameraSource", Map.of()), "execution", Map.of("mode", "AUTO")));
        mvc.perform(post("/api/v1/workflow-runs").with(user("fixture")).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json").content(forged)).andExpect(status().isBadRequest());
        executions.cancelRun(scoped, "{}");
    }

    /** Minimal accessor over decoded JSON maps. */
    record JsonNodeLike(Object value) {
        Object get(String key) { return ((Map<?, ?>) value).get(key); }
        Object path(String... keys) { Object v = value; for (var k : keys) v = ((Map<?, ?>) v).get(k); return v; }
        @SuppressWarnings("unchecked") List<Map<String, Object>> windows() { return (List<Map<String, Object>>) get("windows"); }
        @Override public String toString() { return String.valueOf(value); }
    }
}
