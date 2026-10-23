package io.edgeai.app.integration;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public Hawkeye scenario API on real PostgreSQL against a TEST-ONLY fake adapter; real model inference is verified separately. */
@SpringBootTest(properties = {"edgeai.hawkeye-safety.enabled=true", "edgeai.hawkeye-safety.timeout=3s",
    "edgeai.hawkeye-safety.lease=3s", "edgeai.hawkeye-safety.heartbeat=200ms"})
@AutoConfigureMockMvc
class HawkeyeSafetyScenarioIntegrationTest {
    static final String BASE = "/api/v1/scenarios/hawkeye-safety";
    static final FakeHawkeyeRuntime RUNTIME;
    static {
        try { RUNTIME = new FakeHawkeyeRuntime(); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("edgeai.hawkeye-safety.url", RUNTIME::url);
        registry.add("edgeai.hawkeye-safety.model.ollama-name", () -> FakeHawkeyeRuntime.MODEL);
        registry.add("edgeai.hawkeye-safety.model.manifest-digest", () -> FakeHawkeyeRuntime.DIGEST);
        registry.add("edgeai.hawkeye-safety.dataset.samples-sha256", () -> FakeHawkeyeRuntime.SAMPLES_SHA);
        // A list is replaced as a whole by a higher-priority source, so both samples are restated.
        registry.add("edgeai.hawkeye-safety.samples[0].id", () -> "forklift_0004");
        registry.add("edgeai.hawkeye-safety.samples[0].file", () -> "test/forklift_violation/forklift_0004.jpg");
        registry.add("edgeai.hawkeye-safety.samples[0].reference-label", () -> "forklift_violation");
        registry.add("edgeai.hawkeye-safety.samples[0].sha256", () -> FakeHawkeyeRuntime.sha(FakeHawkeyeRuntime.IMAGES.get("forklift_0004")));
        registry.add("edgeai.hawkeye-safety.samples[1].id", () -> "forklift_0024");
        registry.add("edgeai.hawkeye-safety.samples[1].file", () -> "test/safe/forklift_0024.jpg");
        registry.add("edgeai.hawkeye-safety.samples[1].reference-label", () -> "safe");
        registry.add("edgeai.hawkeye-safety.samples[1].sha256", () -> FakeHawkeyeRuntime.sha(FakeHawkeyeRuntime.IMAGES.get("forklift_0024")));
    }
    @AfterAll static void close() { RUNTIME.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void reset() { RUNTIME.reset(); }
    @AfterEach void settle() throws Exception {
        // One run may be active globally; release the fake and wait so the next test starts clean.
        var held = RUNTIME.hold;
        if (held != null) held.countDown();
        for (var id : jdbc.queryForList("SELECT id FROM edgeai.hawkeye_safety_run WHERE state IN ('QUEUED','RUNNING')", UUID.class))
            awaitState(id, "SUCCEEDED", "FAILED", "INTERRUPTED");
    }

    JsonNode body(ResultActions actions) throws Exception { return json.readTree(actions.andReturn().getResponse().getContentAsString()); }
    ResultActions start(UUID key, String body) throws Exception {
        return mvc.perform(post(BASE + "/runs").with(csrf()).header("Idempotency-Key", key.toString()).contentType("application/json").content(body));
    }
    UUID started(String sample) throws Exception {
        return UUID.fromString(body(start(UUID.randomUUID(), "{\"sampleId\":\"" + sample + "\"}").andExpect(status().isAccepted())).get("id").asString());
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

    @Test void capabilityShowsModelProvenanceSamplesAndWhatTheRuntimeReports() throws Exception {
        var capability = body(mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")));
        assertThat(capability.get("enabled").asBoolean()).isTrue();
        assertThat(capability.at("/model/repository").asString()).isEqualTo("https://huggingface.co/Jsohal174/hawkeye-warehouse-safety-gguf");
        assertThat(capability.at("/model/manifestDigest").asString()).isEqualTo(FakeHawkeyeRuntime.DIGEST);
        assertThat(capability.at("/dataset/synthetic").asBoolean()).isTrue();
        assertThat(capability.at("/samples/0/id").asString()).isEqualTo("forklift_0004");
        assertThat(capability.at("/samples/1/referenceLabel").asString()).isEqualTo("safe");
        assertThat(capability.get("categories").toString()).isEqualTo("[\"spill\",\"forklift_violation\",\"improper_stacking\",\"obstacle\",\"safe\"]");
        assertThat(capability.at("/runtime/status").asString()).isEqualTo("READY");
        assertThat(capability.at("/runtime/reported/nodeName").asString()).isEqualTo("fake-gpu-node");
        assertThat(capability.at("/runtime/reported/processor").asString()).isEqualTo("GPU");
        assertThat(capability.get("activeRunId").isNull()).isTrue();

        // A ready runtime serving another model, a not-ready runtime and an unreachable one are distinct states.
        RUNTIME.healthBody = RUNTIME.health("READY", "e".repeat(64));
        assertThat(body(mvc.perform(get(BASE))).at("/runtime/status").asString()).isEqualTo("MISMATCH");
        RUNTIME.healthStatus = 503; RUNTIME.healthBody = RUNTIME.health("NOT_READY", FakeHawkeyeRuntime.DIGEST);
        assertThat(body(mvc.perform(get(BASE))).at("/runtime/status").asString()).isEqualTo("NOT_READY");
        RUNTIME.healthStatus = 500;
        capability = body(mvc.perform(get(BASE)));
        assertThat(capability.at("/runtime/status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(capability.at("/runtime/reported").isNull()).isTrue();
    }

    @Test void runPersistsTheModelCategoryExplanationTimeAndTargetAndReplaysByIdempotencyKey() throws Exception {
        var key = UUID.randomUUID();
        var id = UUID.fromString(body(start(key, "{\"sampleId\":\"forklift_0004\"}").andExpect(status().isAccepted())).get("id").asString());
        var finished = awaitState(id, "SUCCEEDED", "FAILED");
        assertThat(finished.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(RUNTIME.classified).containsExactly("forklift_0004");
        assertThat(finished.get("sampleId").asString()).isEqualTo("forklift_0004");
        assertThat(finished.get("referenceLabel").asString()).isEqualTo("forklift_violation");
        assertThat(finished.at("/result/category").asString()).isEqualTo("forklift_violation");
        assertThat(finished.at("/result/explanation").asString()).isEqualTo("A forklift carries a raised load while a worker stands in the aisle.");
        assertThat(finished.at("/result/matchesReference").asBoolean()).isTrue();
        assertThat(finished.at("/result/rawOutput").asString()).startsWith("**forklift_violation**");
        assertThat(finished.at("/result/inferenceMs").asDouble()).isEqualTo(0.75);
        assertThat(finished.at("/result/loadMs").asDouble()).isEqualTo(0.25);
        assertThat(finished.at("/result/roundTripMs").asDouble()).isPositive();
        assertThat(finished.at("/result/processor").asString()).isEqualTo("GPU");
        assertThat(finished.at("/result/inputSha256").asString()).isEqualTo(FakeHawkeyeRuntime.sha(FakeHawkeyeRuntime.IMAGES.get("forklift_0004")));
        assertThat(finished.at("/target/nodeName").asString()).isEqualTo("fake-gpu-node");
        assertThat(finished.at("/target/modelDigest").asString()).isEqualTo(FakeHawkeyeRuntime.DIGEST);
        assertThat(finished.at("/target/ollamaVersion").asString()).isEqualTo("0.0.0-fake");

        // Persisted history: listed newest first with its result; the same key and body returns the same run.
        var list = body(mvc.perform(get(BASE + "/runs?limit=5&offset=0")).andExpect(status().isOk()));
        assertThat(list.at("/items/0/id").asString()).isEqualTo(id.toString());
        assertThat(list.at("/items/0/result/category").asString()).isEqualTo("forklift_violation");
        var replay = body(start(key, "{\"sampleId\":\"forklift_0004\"}").andExpect(status().isOk()));
        assertThat(replay.get("id").asString()).isEqualTo(id.toString());
        assertThat(body(start(key, "{\"sampleId\":\"forklift_0024\"}").andExpect(status().isConflict())).get("code").asString())
            .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(RUNTIME.classified).containsExactly("forklift_0004");
    }

    @Test void misclassificationIsStoredAsTheModelAnsweredBesideTheReferenceLabel() throws Exception {
        var finished = awaitState(started("forklift_0024"), "SUCCEEDED", "FAILED");
        assertThat(finished.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(finished.get("referenceLabel").asString()).isEqualTo("safe");
        assertThat(finished.at("/result/category").asString()).isEqualTo("spill");
        assertThat(finished.at("/result/explanation").asString()).isEqualTo("A small dark puddle is visible near the rack.");
        assertThat(finished.at("/result/matchesReference").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "safe or spill", "The aisle looks fine.", "**Category: unsafe**\n\nSomething is wrong."})
    void unreadableOutputFailsTheRunAndIsNeverReportedAsSafe(String output) throws Exception {
        RUNTIME.content = sample -> output;
        var finished = awaitState(started("forklift_0024"), "SUCCEEDED", "FAILED");
        assertThat(finished.get("state").asString()).isEqualTo("FAILED");
        assertThat(finished.get("failureCode").asString()).isEqualTo("UNPARSEABLE_OUTPUT");
        assertThat(finished.at("/result/category").isNull()).isTrue();
        assertThat(finished.at("/result/rawOutput").asString()).isEqualTo(output);
        assertThat(finished.at("/result/inferenceMs").asDouble()).isEqualTo(0.75);
    }

    @Test void runtimeFailuresAndWrongIdentitiesFailTheRunWithoutAResult() throws Exception {
        RUNTIME.classifyStatus = 502;
        var failed = awaitState(started("forklift_0004"), "SUCCEEDED", "FAILED");
        assertThat(failed.get("failureCode").asString()).isEqualTo("RUNTIME_INVALID_RESPONSE");
        assertThat(failed.get("result").isNull()).isTrue();

        RUNTIME.reset();
        RUNTIME.classify = sample -> RUNTIME.result(sample).replace("\"loadedDigest\":\"" + FakeHawkeyeRuntime.DIGEST, "\"loadedDigest\":\"" + "e".repeat(64));
        assertThat(awaitState(started("forklift_0004"), "SUCCEEDED", "FAILED").get("failureCode").asString()).isEqualTo("IDENTITY_MISMATCH");

        RUNTIME.reset();
        RUNTIME.healthStatus = 503; RUNTIME.healthBody = RUNTIME.health("NOT_READY", FakeHawkeyeRuntime.DIGEST);
        var notReady = awaitState(started("forklift_0004"), "SUCCEEDED", "FAILED");
        assertThat(notReady.get("failureCode").asString()).isEqualTo("TARGET_NOT_READY");
        assertThat(RUNTIME.classified).isEmpty();
    }

    @Test void onlyOneRunIsActiveAtATime() throws Exception {
        RUNTIME.hold = new CountDownLatch(1);
        var id = started("forklift_0004");
        assertThat(RUNTIME.entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(run(id).get("state").asString()).isEqualTo("RUNNING");
        assertThat(body(mvc.perform(get(BASE))).get("activeRunId").asString()).isEqualTo(id.toString());
        assertThat(body(start(UUID.randomUUID(), "{\"sampleId\":\"forklift_0024\"}").andExpect(status().isConflict())).get("code").asString())
            .isEqualTo("SCENARIO_RUN_ACTIVE");
        RUNTIME.hold.countDown();
        assertThat(awaitState(id, "SUCCEEDED", "FAILED").get("state").asString()).isEqualTo("SUCCEEDED");
    }

    @Test void sampleImagesAreServedOnlyWhenTheyMatchThePinnedDigest() throws Exception {
        var image = mvc.perform(get(BASE + "/samples/forklift_0024/image")).andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg")).andReturn().getResponse().getContentAsByteArray();
        assertThat(image).isEqualTo(FakeHawkeyeRuntime.IMAGES.get("forklift_0024"));
        mvc.perform(get(BASE + "/samples/forklift_9999/image")).andExpect(status().isNotFound());
    }

    @Test void invalidStartRequestsAreRejectedBeforeAnyRunExists() throws Exception {
        for (var request : List.of("{\"sampleId\":\"forklift_9999\"}", "{\"sampleId\":\"forklift_0004\",\"x\":1}", "{}", "[]"))
            assertThat(body(start(UUID.randomUUID(), request).andExpect(status().isBadRequest())).get("code").asString()).isEqualTo("INVALID_SCENARIO_REQUEST");
        mvc.perform(post(BASE + "/runs").with(csrf()).header("Idempotency-Key", "not-a-uuid").contentType("application/json")
            .content("{\"sampleId\":\"forklift_0004\"}")).andExpect(status().isBadRequest());
        assertThat(RUNTIME.classified).isEmpty();
    }
}
