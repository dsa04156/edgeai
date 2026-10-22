package io.edgeai.app.integration;

import io.edgeai.app.EdgeAiApplication;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public scenario API on real PostgreSQL against TEST-ONLY fake runtimes; real GPU/NPU behavior is verified separately. */
@SpringBootTest(properties = {"edgeai.scenario.enabled=true", "edgeai.scenario.timeout=3s", "edgeai.scenario.lease=3s",
    "edgeai.scenario.heartbeat=200ms"})
@AutoConfigureMockMvc
class ImageComparisonScenarioIntegrationTest {
    static final String GPU_SHA = "a".repeat(64), NPU_SHA = "b".repeat(64), BASE = "/api/v1/scenarios/image-comparison";
    static final FakeClassificationRuntime GPU, NPU;
    static {
        try {
            GPU = new FakeClassificationRuntime("GPU", GPU_SHA, "CUDAExecutionProvider");
            NPU = new FakeClassificationRuntime("NPU", NPU_SHA, "HailoRT");
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static void runtimes(java.util.function.BiConsumer<String, Object> registry) {
        registry.accept("edgeai.scenario.gpu.url", GPU.url());
        registry.accept("edgeai.scenario.npu.url", NPU.url());
        registry.accept("edgeai.scenario.gpu.model-sha256", GPU_SHA);
        registry.accept("edgeai.scenario.npu.model-sha256", NPU_SHA);
        registry.accept("edgeai.scenario.dataset.manifest-sha256", FakeClassificationRuntime.MANIFEST_SHA);
        registry.accept("edgeai.scenario.dataset.inputs-sha256", FakeClassificationRuntime.INPUTS_SHA);
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { runtimes((k, v) -> registry.add(k, () -> v)); }
    @AfterAll static void close() { GPU.close(); NPU.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void reset() { GPU.reset(); NPU.reset(); }
    @AfterEach void settle() throws Exception {
        // One run may be active globally; release fakes and wait so the next test starts clean.
        GPU.reset(); NPU.reset();
        for (var id : jdbc.queryForList("SELECT id FROM edgeai.image_comparison_run WHERE state IN ('QUEUED','RUNNING','STOPPING')", UUID.class)) {
            mvc.perform(post(BASE + "/runs/" + id + "/stop").with(csrf()));
            awaitState(id, "STOPPED", "FAILED", "SUCCEEDED", "INTERRUPTED");
        }
    }

    JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
    org.springframework.test.web.servlet.ResultActions start(MockMvc mvc, UUID key, String body) throws Exception {
        return mvc.perform(post(BASE + "/runs").with(csrf()).header("Idempotency-Key", key.toString()).contentType("application/json").content(body));
    }
    UUID started(int count) throws Exception {
        return UUID.fromString(body(start(mvc, UUID.randomUUID(), "{\"imageCount\":" + count + "}").andExpect(status().isAccepted())).get("id").asString());
    }
    JsonNode run(UUID id) throws Exception { return body(mvc.perform(get(BASE + "/runs/" + id)).andExpect(status().isOk())); }
    JsonNode awaitState(UUID id, String... states) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        JsonNode value;
        do {
            value = run(id);
            if (Arrays.asList(states).contains(value.get("state").asString())) return value;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Run " + id + " stayed " + value.get("state") + ", expected " + Arrays.toString(states));
    }
    static void entered(FakeClassificationRuntime runtime, int index) throws InterruptedException {
        assertThat(runtime.entered(index).await(10, TimeUnit.SECONDS)).as(runtime.target + " received image " + index).isTrue();
    }

    @Test void capabilityShowsTheFixedDatasetAndWhatEachRuntimeReports() throws Exception {
        var capability = body(mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")));
        assertThat(capability.get("enabled").asBoolean()).isTrue();
        assertThat(capability.get("maxImageCount").asInt()).isEqualTo(100);
        assertThat(capability.at("/dataset/manifestSha256").asString()).isEqualTo(FakeClassificationRuntime.MANIFEST_SHA);
        assertThat(capability.at("/dataset/imageCount").asInt()).isEqualTo(100);
        assertThat(capability.at("/targets/0/id").asString()).isEqualTo("GPU");
        assertThat(capability.at("/targets/0/status").asString()).isEqualTo("READY");
        assertThat(capability.at("/targets/0/reported/deviceName").asString()).isEqualTo("Fake GPU 0");
        assertThat(capability.at("/targets/1/reported/nodeName").asString()).isEqualTo("fake-npu-node");
        assertThat(capability.at("/targets/1/expectedProvider").asString()).isEqualTo("HailoRT");
        assertThat(capability.get("activeRunId").isNull()).isTrue();

        // A ready runtime with another model, a not-ready runtime and an unreachable one are distinct states.
        GPU.healthBody = GPU.health("READY", "c".repeat(64));
        NPU.healthStatus = 503; NPU.healthBody = NPU.health("NOT_READY", NPU_SHA);
        capability = body(mvc.perform(get(BASE)).andExpect(status().isOk()));
        assertThat(capability.at("/targets/0/status").asString()).isEqualTo("MISMATCH");
        assertThat(capability.at("/targets/0/reported/modelSha256").asString()).isEqualTo("c".repeat(64));
        assertThat(capability.at("/targets/1/status").asString()).isEqualTo("NOT_READY");
        // A runtime whose device never loaded has no device identity yet but is still NOT_READY, not unreachable.
        NPU.healthBody = NPU.health("NOT_READY", NPU_SHA).replace("\"Fake NPU 0\"", "null");
        capability = body(mvc.perform(get(BASE)).andExpect(status().isOk()));
        assertThat(capability.at("/targets/1/status").asString()).isEqualTo("NOT_READY");
        assertThat(capability.at("/targets/1/reported").isNull()).isTrue();
        NPU.healthStatus = 500;
        assertThat(body(mvc.perform(get(BASE))).at("/targets/1/status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(body(mvc.perform(get(BASE))).at("/targets/1/reported").isNull()).isTrue();
    }

    @Test void runSendsTheSameOrderedImagesToBothTargetsAndPersistsTheComparison() throws Exception {
        var id = started(5);
        var finished = awaitState(id, "SUCCEEDED", "FAILED");
        assertThat(finished.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(GPU.classified).containsExactly(0, 1, 2, 3, 4);
        assertThat(NPU.classified).containsExactly(0, 1, 2, 3, 4);
        assertThat(finished.get("dispatchedCount").asInt()).isEqualTo(5);
        assertThat(finished.at("/snapshot/dataset/manifestSha256").asString()).isEqualTo(FakeClassificationRuntime.MANIFEST_SHA);
        assertThat(finished.at("/snapshot/targets/0/deviceName").asString()).isEqualTo("Fake GPU 0");
        assertThat(finished.at("/snapshot/targets/1/modelSha256").asString()).isEqualTo(NPU_SHA);
        var comparison = finished.get("comparison");
        assertThat(comparison.get("pairedCount").asInt()).isEqualTo(5);
        assertThat(comparison.get("top1AgreementCount").asInt()).isEqualTo(4);
        assertThat(comparison.get("labelsTrusted").asBoolean()).isTrue();
        assertThat(comparison.at("/targets/0/succeeded").asInt()).isEqualTo(5);
        assertThat(comparison.at("/targets/0/pairedLabeledCorrect").asInt()).isEqualTo(5);
        assertThat(comparison.at("/targets/1/pairedLabeledCorrect").asInt()).isEqualTo(4);
        assertThat(comparison.at("/targets/1/p50InferenceMs").asDouble()).isEqualTo(0.5);
        // Compute-only figures come from runtime-reported call time; wall figures from API-measured durations.
        assertThat(comparison.at("/targets/1/computeImagesPerSecond").asDouble()).isEqualTo(2000.0);
        var callWall = comparison.at("/targets/1/callWallSeconds").asDouble();
        assertThat(callWall).isPositive();
        assertThat(comparison.at("/targets/1/callWallImagesPerSecond").asDouble()).isCloseTo(5 / callWall, within(1e-6));
        assertThat(comparison.at("/targets/1/meanRoundTripMs").asDouble()).isCloseTo(callWall * 1000 / 5, within(1e-6));
        var runWall = comparison.get("runWallSeconds").asDouble();
        assertThat(runWall).isGreaterThanOrEqualTo(callWall);
        assertThat(comparison.get("pairedImagesPerRunWallSecond").asDouble()).isCloseTo(5 / runWall, within(1e-6));

        var page = body(mvc.perform(get(BASE + "/runs/" + id + "/results").param("limit", "3")).andExpect(status().isOk()));
        assertThat(page.get("items")).hasSize(3);
        assertThat(page.get("nextOffset").asInt()).isEqualTo(3);
        var rest = body(mvc.perform(get(BASE + "/runs/" + id + "/results").param("limit", "3").param("offset", "3")));
        assertThat(rest.get("items")).hasSize(2);
        assertThat(rest.get("nextOffset").isNull()).isTrue();
        // An exactly full last page and a page past the end both report no next page.
        var full = body(mvc.perform(get(BASE + "/runs/" + id + "/results").param("limit", "5")));
        assertThat(full.get("items")).hasSize(5);
        assertThat(full.get("nextOffset").isNull()).isTrue();
        var past = body(mvc.perform(get(BASE + "/runs/" + id + "/results").param("limit", "5").param("offset", "5")).andExpect(status().isOk()));
        assertThat(past.get("items")).isEmpty();
        assertThat(past.get("nextOffset").isNull()).isTrue();
        var third = rest.get("items").get(0);
        assertThat(third.get("index").asInt()).isEqualTo(3);
        assertThat(third.get("tensorSha256").asString()).isEqualTo(FakeClassificationRuntime.tensorSha(3));
        assertThat(third.at("/gpu/top1").asInt()).isEqualTo(3);
        assertThat(third.at("/npu/top1").asInt()).isEqualTo(998);
        assertThat(third.at("/npu/topK/1/score").asDouble()).isEqualTo(1.25);
        assertThat(third.get("top1Agreement").asBoolean()).isFalse();

        var list = body(mvc.perform(get(BASE + "/runs").param("limit", "1")).andExpect(status().isOk()));
        assertThat(list.at("/items/0/id").asString()).isEqualTo(id.toString());
        assertThat(list.at("/items/0/comparison").isNull()).isTrue();

        // Stored outcomes and the snapshot cannot be rewritten.
        assertThatThrownBy(() -> jdbc.update("UPDATE edgeai.image_comparison_result SET failure_code='X' WHERE run_id=?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE edgeai.image_comparison_run SET snapshot='{}' WHERE id=?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void startIsIdempotentPerKeyAndRejectsInvalidInput() throws Exception {
        var key = UUID.randomUUID();
        var first = body(start(mvc, key, "{\"imageCount\":2}").andExpect(status().isAccepted())
            .andExpect(header().exists("Location")));
        var replay = body(start(mvc, key, "{\"imageCount\":2}").andExpect(status().isOk()));
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());
        start(mvc, key, "{\"imageCount\":3}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        awaitState(UUID.fromString(first.get("id").asString()), "SUCCEEDED");
        // A replay after completion still returns the same run instead of starting another.
        assertThat(body(start(mvc, key, "{\"imageCount\":2}").andExpect(status().isOk())).get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(GPU.classified).containsExactly(0, 1);

        for (var invalid : List.of("{\"imageCount\":0}", "{\"imageCount\":101}", "{\"imageCount\":\"5\"}", "{\"imageCount\":5,\"x\":1}", "{}", "[]", "{\"imageCount\":2.0}"))
            start(mvc, UUID.randomUUID(), invalid).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCENARIO_REQUEST"));
        mvc.perform(post(BASE + "/runs").with(csrf()).header("Idempotency-Key", "not-a-uuid").contentType("application/json").content("{\"imageCount\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/runs").with(csrf()).contentType("application/json").content("{\"imageCount\":1}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/runs").header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
            .content("{\"imageCount\":1}")).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/runs/" + UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SCENARIO_RUN_NOT_FOUND"));
        mvc.perform(post(BASE + "/runs/" + UUID.randomUUID() + "/stop").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/runs/" + first.get("id").asString() + "/results").param("limit", "101")).andExpect(status().isBadRequest());
        assertThat(GPU.classified).containsExactly(0, 1);
    }

    @Test void concurrentStartsAdmitExactlyOneActiveRun() throws Exception {
        var before = jdbc.queryForObject("SELECT count(*) FROM edgeai.image_comparison_run", Integer.class);
        var held = new CountDownLatch(1);
        GPU.hold = index -> held;
        var ready = new CyclicBarrier(8);
        var calls = new ArrayList<Future<Integer>>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++)
                calls.add(pool.submit(() -> { ready.await(); return start(mvc, UUID.randomUUID(), "{\"imageCount\":3}").andReturn().getResponse().getStatus(); }));
            var statuses = new ArrayList<Integer>();
            for (var call : calls) statuses.add(call.get(15, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(202, 409);
            assertThat(statuses.stream().filter(s -> s == 202)).hasSize(1);
        }
        var active = body(mvc.perform(get(BASE))).get("activeRunId").asString();
        start(mvc, UUID.randomUUID(), "{\"imageCount\":1}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCENARIO_RUN_ACTIVE"));
        held.countDown();
        assertThat(awaitState(UUID.fromString(active), "SUCCEEDED").get("dispatchedCount").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.image_comparison_run", Integer.class)).isEqualTo(before + 1);
    }

    @Test void acceptedStopPreventsFurtherDispatchWhileTheImageInFlightFinishes() throws Exception {
        var held = new CountDownLatch(1);
        NPU.hold = index -> index == 2 ? held : null;
        var id = started(10);
        entered(NPU, 2);
        var stopping = body(mvc.perform(post(BASE + "/runs/" + id + "/stop").with(csrf())).andExpect(status().isOk()));
        assertThat(stopping.get("state").asString()).isEqualTo("STOPPING");
        assertThat(stopping.get("stopRequestedAt").isNull()).isFalse();
        // Repeated Stop is idempotent and keeps waiting for the image in flight.
        assertThat(body(mvc.perform(post(BASE + "/runs/" + id + "/stop").with(csrf()))).get("state").asString()).isEqualTo("STOPPING");
        assertThat(run(id).get("state").asString()).isEqualTo("STOPPING");
        held.countDown();
        var stopped = awaitState(id, "STOPPED");
        assertThat(stopped.get("dispatchedCount").asInt()).isEqualTo(3);
        assertThat(stopped.get("finishedAt").isNull()).isFalse();
        assertThat(stopped.at("/comparison/pairedCount").asInt()).isEqualTo(3);
        Thread.sleep(300);
        assertThat(GPU.classified).containsExactly(0, 1, 2);
        assertThat(NPU.classified).containsExactly(0, 1, 2);
        assertThat(body(mvc.perform(post(BASE + "/runs/" + id + "/stop").with(csrf())).andExpect(status().isOk())).get("state").asString()).isEqualTo("STOPPED");
        assertThat(body(mvc.perform(get(BASE + "/runs/" + id + "/results"))).get("items")).hasSize(3);
    }

    @Test void anAcceptedStopEndsStoppedEvenWhenTheImageInFlightFails() throws Exception {
        var held = new CountDownLatch(1);
        NPU.hold = index -> index == 2 ? held : null;
        var id = started(10);
        entered(NPU, 2);
        assertThat(body(mvc.perform(post(BASE + "/runs/" + id + "/stop").with(csrf()))).get("state").asString()).isEqualTo("STOPPING");
        NPU.classifyStatus = 503;
        held.countDown();
        var stopped = awaitState(id, "STOPPED", "FAILED");
        assertThat(stopped.get("state").asString()).isEqualTo("STOPPED");
        assertThat(stopped.get("dispatchedCount").asInt()).isEqualTo(3);
        // The failure of the image in flight stays recorded on the run and the image.
        assertThat(stopped.get("failureCode").asString()).isEqualTo("TARGET_FAILED");
        assertThat(stopped.get("failureMessage").asString()).contains("NPU").contains("RUNTIME_BUSY");
        var image = body(mvc.perform(get(BASE + "/runs/" + id + "/results").param("offset", "2"))).at("/items/0");
        assertThat(image.at("/npu/failureCode").asString()).isEqualTo("RUNTIME_BUSY");
        assertThat(image.at("/gpu/status").asString()).isEqualTo("SUCCEEDED");
        assertThat(NPU.classified).containsExactly(0, 1, 2);
    }

    @Test void callWallThroughputCountsFailedCallTimeWhileComputeFiguresUseSuccessesOnly() throws Exception {
        // Image 0 succeeds; image 1 never answers and fails after the 3 s call timeout.
        NPU.hold = index -> index == 1 ? new CountDownLatch(1) : null;
        var id = started(2);
        var failed = awaitState(id, "FAILED");
        var npu = failed.at("/comparison/targets/1");
        assertThat(npu.get("target").asString()).isEqualTo("NPU");
        assertThat(npu.get("succeeded").asInt()).isEqualTo(1);
        assertThat(npu.get("failed").asInt()).isEqualTo(1);
        var items = body(mvc.perform(get(BASE + "/runs/" + id + "/results"))).get("items");
        double success = items.get(0).at("/npu/roundTripMs").asDouble(), timeout = items.get(1).at("/npu/roundTripMs").asDouble();
        assertThat(timeout).isGreaterThanOrEqualTo(3000);
        double expectedWall = (success + timeout) / 1000;
        assertThat(npu.get("callWallSeconds").asDouble()).isCloseTo(expectedWall, within(1e-9));
        assertThat(npu.get("callWallImagesPerSecond").asDouble()).isCloseTo(1 / expectedWall, within(1e-9)).isLessThan(0.34);
        // Latency and compute figures stay success-only.
        assertThat(npu.get("meanRoundTripMs").asDouble()).isCloseTo(success, within(1e-9));
        assertThat(npu.get("computeImagesPerSecond").asDouble()).isEqualTo(2000.0);
    }

    @Test void stopBeforeVerificationEndsTheRunWithoutAnyDispatch() throws Exception {
        var held = new CountDownLatch(1);
        GPU.manifestHold = held;
        var id = started(5);
        assertThat(GPU.manifestEntered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(run(id).get("state").asString()).isEqualTo("QUEUED");
        assertThat(body(mvc.perform(post(BASE + "/runs/" + id + "/stop").with(csrf()))).get("state").asString()).isEqualTo("STOPPED");
        held.countDown();
        Thread.sleep(300);
        var stopped = run(id);
        assertThat(stopped.get("state").asString()).isEqualTo("STOPPED");
        assertThat(stopped.get("snapshot").isNull()).isTrue();
        assertThat(GPU.classified).isEmpty();
        assertThat(NPU.classified).isEmpty();
    }

    @Test void invalidRuntimeResponsesAreRecordedAndFailTheRun() throws Exception {
        record Case(Runnable setup, String target, String code) {}
        var cases = List.of(
            new Case(() -> NPU.classify = i -> NPU.result(i, "f".repeat(64), "[{\"classIndex\":1,\"score\":1}]"), "NPU", "INPUT_MISMATCH"),
            new Case(() -> GPU.classify = i -> "not json", "GPU", "RUNTIME_INVALID_RESPONSE"),
            new Case(() -> GPU.classify = i -> GPU.result(i, FakeClassificationRuntime.tensorSha(i), "[{\"classIndex\":1,\"score\":1},{\"classIndex\":2,\"score\":3}]"), "GPU", "INVALID_RESPONSE"),
            new Case(() -> NPU.classify = i -> NPU.result(i, FakeClassificationRuntime.tensorSha(i), "[{\"classIndex\":1000,\"score\":1}]"), "NPU", "INVALID_RESPONSE"),
            new Case(() -> NPU.classify = i -> NPU.result(i, FakeClassificationRuntime.tensorSha(i), "[{\"classIndex\":1,\"score\":1}]").replace("Fake NPU 0", "CPU"), "NPU", "IDENTITY_MISMATCH"),
            new Case(() -> GPU.classifyStatus = 503, "GPU", "RUNTIME_BUSY"),
            new Case(() -> NPU.hold = i -> new CountDownLatch(1), "NPU", "RUNTIME_UNAVAILABLE"));
        for (var c : cases) {
            GPU.reset(); NPU.reset();
            c.setup().run();
            var id = started(4);
            var failed = awaitState(id, "FAILED", "SUCCEEDED");
            assertThat(failed.get("state").asString()).as(c.code()).isEqualTo("FAILED");
            assertThat(failed.get("failureCode").asString()).isEqualTo("TARGET_FAILED");
            assertThat(failed.get("failureMessage").asString()).contains(c.target()).contains(c.code());
            assertThat(failed.get("dispatchedCount").asInt()).as(c.code()).isEqualTo(1);
            var image = body(mvc.perform(get(BASE + "/runs/" + id + "/results"))).at("/items/0");
            var outcome = image.get(c.target().toLowerCase());
            assertThat(outcome.get("status").asString()).isEqualTo("FAILED");
            assertThat(outcome.get("failureCode").asString()).isEqualTo(c.code());
            assertThat(outcome.get("topK")).isEmpty();
            assertThat(image.get("top1Agreement").isNull()).isTrue();
            assertThat(failed.at("/comparison/pairedCount").asInt()).isZero();
        }
    }

    @Test void runtimesThatAreNotReadyOrServeAnotherDatasetFailBeforeDispatch() throws Exception {
        NPU.healthBody = NPU.health("READY", "c".repeat(64));
        var mismatch = awaitState(started(3), "FAILED");
        assertThat(mismatch.get("failureCode").asString()).isEqualTo("TARGET_NOT_READY");
        assertThat(mismatch.get("failureMessage").asString()).contains("NPU").contains("MISMATCH");
        assertThat(mismatch.get("snapshot").isNull()).isTrue();
        NPU.reset();
        GPU.healthStatus = 500;
        assertThat(awaitState(started(3), "FAILED").get("failureMessage").asString()).contains("GPU").contains("UNAVAILABLE");
        assertThat(GPU.classified).isEmpty();
        assertThat(NPU.classified).isEmpty();
    }

    @Test void runsOfALostProcessBecomeInterruptedAndAreNeverReplayed() throws Exception {
        var earlier = started(1);
        awaitState(earlier, "SUCCEEDED");
        GPU.reset(); NPU.reset();
        // State a crashed API process leaves behind: RUNNING, owned by an instance that no longer renews its lease.
        var orphan = UUID.randomUUID();
        jdbc.update("INSERT INTO edgeai.image_comparison_run(id,idempotency_key,requested_count,state,dispatched_count,snapshot,owner_instance,lease_expires_at,started_at) " +
            "SELECT ?,?,5,'RUNNING',2,snapshot,?,now()-interval '1 second',now() FROM edgeai.image_comparison_run WHERE id=?",
            orphan, UUID.randomUUID(), UUID.randomUUID(), earlier);
        var interrupted = awaitState(orphan, "INTERRUPTED");
        assertThat(interrupted.get("failureCode").asString()).isEqualTo("OWNER_LOST");
        assertThat(interrupted.get("dispatchedCount").asInt()).isEqualTo(2);
        Thread.sleep(300);
        assertThat(GPU.classified).isEmpty();
        assertThat(NPU.classified).isEmpty();
        // The lost run no longer blocks a new one.
        awaitState(started(1), "SUCCEEDED");
    }

    @Test void stoppingTheApiProcessInterruptsItsRunInsteadOfLeavingItRunning() throws Exception {
        var held = new CountDownLatch(1);
        GPU.hold = index -> held;
        var properties = new HashMap<String, Object>(Map.of("edgeai.scenario.enabled", "true", "edgeai.scenario.timeout", "3s",
            "edgeai.scenario.lease", "3s", "edgeai.scenario.heartbeat", "200ms", "server.port", "0"));
        runtimes(properties::put);
        UUID id;
        // Arguments, unlike builder default properties, override application.yml as the deployed environment does.
        var arguments = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        try (var other = new SpringApplicationBuilder(EdgeAiApplication.class).run(arguments)) {
            var otherMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) other).apply(springSecurity()).build();
            id = UUID.fromString(body(start(otherMvc, UUID.randomUUID(), "{\"imageCount\":5}").andExpect(status().isAccepted())).get("id").asString());
            entered(GPU, 0);
        }
        var interrupted = run(id);
        assertThat(interrupted.get("state").asString()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.get("failureCode").asString()).isEqualTo("PROCESS_STOPPED");
        held.countDown();
        Thread.sleep(500);
        assertThat(run(id).get("state").asString()).isEqualTo("INTERRUPTED");
        assertThat(GPU.classified).containsExactly(0);
        assertThat(body(mvc.perform(get(BASE + "/runs/" + id + "/results"))).get("items").get(0).get("gpu").isNull()).isTrue();
    }
}
