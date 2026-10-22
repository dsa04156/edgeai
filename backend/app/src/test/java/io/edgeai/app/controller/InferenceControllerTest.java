package io.edgeai.app.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.edgeai.app.config.InferenceConfiguration;
import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.InferenceService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public contract against a boundary runtime; the GPU runtime itself is verified separately. */
@WebMvcTest(controllers = InferenceController.class, properties = {
    "edgeai.inference.enabled=true", "edgeai.inference.model-id=detector-v1", "edgeai.inference.model-name=Detector",
    "edgeai.inference.node-name=gpu-node", "edgeai.inference.device-name=GPU 0", "edgeai.inference.provider=pytorch-cuda",
    "edgeai.inference.timeout=2s"})
@Import({SecurityConfiguration.class, InferenceConfiguration.class, InferenceService.class})
class InferenceControllerTest {
    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(int status, String body) { return new Reply(status, body, Map.of("Content-Type", "application/json")); }
    }
    static final HttpServer RUNTIME;
    static final AtomicInteger PREDICTS = new AtomicInteger(), REDIRECTED = new AtomicInteger();
    static volatile String lastPredictBody;
    static volatile Reply health, predict;
    static volatile CountDownLatch hold;
    static {
        try { RUNTIME = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
        RUNTIME.setExecutor(Executors.newCachedThreadPool());
        RUNTIME.createContext("/health", exchange -> reply(exchange, health));
        RUNTIME.createContext("/predict", exchange -> {
            PREDICTS.incrementAndGet();
            lastPredictBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var latch = hold;
            if (latch != null) try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            reply(exchange, predict);
        });
        RUNTIME.createContext("/elsewhere", exchange -> { REDIRECTED.incrementAndGet(); reply(exchange, Reply.json(200, "{}")); });
        RUNTIME.start();
    }
    static void reply(HttpExchange exchange, Reply reply) throws IOException {
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        reply.headers().forEach(exchange.getResponseHeaders()::set);
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
    @DynamicPropertySource static void runtime(DynamicPropertyRegistry registry) {
        registry.add("edgeai.inference.runtime-url", () -> "http://127.0.0.1:" + RUNTIME.getAddress().getPort());
    }
    @AfterAll static void stop() { RUNTIME.stop(0); }

    @Autowired MockMvc mvc;
    static final String READY = "{\"status\":\"READY\",\"modelId\":\"detector-v1\",\"nodeName\":\"gpu-node\",\"deviceName\":\"GPU 0\",\"provider\":\"pytorch-cuda\"}";

    @BeforeEach void reset() {
        PREDICTS.set(0); REDIRECTED.set(0); hold = null; lastPredictBody = null;
        health = Reply.json(200, READY);
        predict = Reply.json(200, result(64, 48, "[{\"label\":\"person\",\"score\":0.91,\"x\":1.5,\"y\":2,\"width\":30,\"height\":40}]"));
    }
    static String result(int width, int height, String detections) {
        return result("detector-v1", width, height, detections);
    }
    static String result(String modelId, int width, int height, String detections) {
        return "{\"modelId\":\"" + modelId + "\",\"nodeName\":\"gpu-node\",\"deviceName\":\"GPU 0\",\"provider\":\"pytorch-cuda\","
            + "\"imageWidth\":" + width + ",\"imageHeight\":" + height + ",\"inferenceMs\":12.5,\"detections\":" + detections + "}";
    }
    static byte[] image(String format, int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
    static String body(String modelId, byte[] image) {
        return "{\"modelId\":\"" + modelId + "\",\"imageBase64\":\"" + Base64.getEncoder().encodeToString(image) + "\"}";
    }
    org.springframework.test.web.servlet.ResultActions predict(String body) throws Exception {
        return mvc.perform(post("/api/v1/inference/predict").with(csrf()).contentType("application/json").content(body));
    }

    @Test void listsOnlyTheConfiguredModelWithRuntimeReadiness() throws Exception {
        mvc.perform(get("/api/v1/inference/models")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items", hasSize(1)))
            .andExpect(jsonPath("$.items[0].id").value("detector-v1"))
            .andExpect(jsonPath("$.items[0].name").value("Detector"))
            .andExpect(jsonPath("$.items[0].nodeName").value("gpu-node"))
            .andExpect(jsonPath("$.items[0].deviceName").value("GPU 0"))
            .andExpect(jsonPath("$.items[0].available").value(true));
    }
    @Test void unreadyOrMismatchedRuntimeIsUnavailable() throws Exception {
        for (var reply : List.of(Reply.json(503, "{\"status\":\"LOADING\"}"),
                Reply.json(200, READY.replace("detector-v1", "other-model")),
                Reply.json(200, READY.replace("gpu-node", "other-node")),
                Reply.json(200, READY.replace("pytorch-cuda", "cpu")),
                Reply.json(200, READY.replace("READY", "LOADING")),
                Reply.json(200, "not-json"))) {
            health = reply;
            mvc.perform(get("/api/v1/inference/models")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("detector-v1"))
                .andExpect(jsonPath("$.items[0].available").value(false));
        }
    }
    @Test void predictsAndForwardsTheValidatedImageOnce() throws Exception {
        byte[] png = image("png", 64, 48);
        predict(body("detector-v1", png)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.modelId").value("detector-v1"))
            .andExpect(jsonPath("$.nodeName").value("gpu-node"))
            .andExpect(jsonPath("$.deviceName").value("GPU 0"))
            .andExpect(jsonPath("$.provider").value("pytorch-cuda"))
            .andExpect(jsonPath("$.imageWidth").value(64))
            .andExpect(jsonPath("$.imageHeight").value(48))
            .andExpect(jsonPath("$.inferenceMs").value(12.5))
            .andExpect(jsonPath("$.detections[0].label").value("person"))
            .andExpect(jsonPath("$.detections[0].score").value(0.91))
            .andExpect(jsonPath("$.detections[0].x").value(1.5))
            .andExpect(jsonPath("$.detections[0].height").value(40.0));
        assertThat(PREDICTS.get()).isEqualTo(1);
        assertThat(lastPredictBody).isEqualTo("{\"imageBase64\":\"" + Base64.getEncoder().encodeToString(png) + "\"}");
        predict = Reply.json(200, result(32, 16, "[]"));
        predict(body("detector-v1", image("jpeg", 32, 16))).andExpect(status().isOk()).andExpect(jsonPath("$.detections", hasSize(0)));
    }
    @Test void predictionRequiresCsrf() throws Exception {
        mvc.perform(post("/api/v1/inference/predict").contentType("application/json").content(body("detector-v1", image("png", 8, 8))))
            .andExpect(status().isForbidden());
        assertThat(PREDICTS.get()).isZero();
    }
    @Test void rejectsInvalidRequestsBeforeTheRuntime() throws Exception {
        byte[] png = image("png", 8, 8);
        String encoded = Base64.getEncoder().encodeToString(png);
        for (String invalid : List.of("not-json", "{}", "{\"modelId\":\"detector-v1\"}",
                "{\"modelId\":\"detector-v1\",\"imageBase64\":\"%%%\"}",
                "{\"modelId\":\"detector-v1\",\"imageBase64\":\"data:image/png;base64," + encoded + "\"}",
                "{\"modelId\":\"detector-v1\",\"imageBase64\":\"" + encoded + "\",\"url\":\"http://elsewhere\"}",
                "{\"modelId\":1,\"imageBase64\":\"" + encoded + "\"}",
                body("detector-v1", image("gif", 8, 8)),
                body("detector-v1", "plain text".getBytes(StandardCharsets.UTF_8)),
                body("detector-v1", image("png", 4097, 1)),
                body("detector-v1", image("png", 4096, 4097))))
            predict(invalid).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INFERENCE_REQUEST"));
        predict(body("unknown-model", png)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INFERENCE_MODEL_NOT_FOUND"));
        assertThat(PREDICTS.get()).isZero();
    }
    @Test void rejectsOversizeImagesAndBodies() throws Exception {
        byte[] large = new byte[4 * 1024 * 1024 + 1];
        large[0] = (byte) 0x89;
        predict(body("detector-v1", large)).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("INFERENCE_IMAGE_TOO_LARGE"));
        predict("{\"modelId\":\"detector-v1\",\"imageBase64\":\"" + "A".repeat(7 * 1024 * 1024) + "\"}")
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("INFERENCE_IMAGE_TOO_LARGE"));
        assertThat(PREDICTS.get()).isZero();
    }
    @Test void runtimeBusyFailureAndRedirectsAreExplicitAndNeverRetried() throws Exception {
        String request = body("detector-v1", image("png", 64, 48));
        predict = Reply.json(503, "{\"status\":\"BUSY\"}");
        predict(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INFERENCE_BUSY"));
        predict = Reply.json(500, "{}");
        predict(request).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("INFERENCE_RUNTIME_INVALID_RESPONSE"));
        predict = Reply.json(400, "{}");
        predict(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INFERENCE_IMAGE_REJECTED"));
        predict = Reply.json(415, "{\"error\":\"UNSUPPORTED_MEDIA_TYPE\",\"message\":\"private detail\"}");
        predict(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INFERENCE_IMAGE_REJECTED"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        predict = Reply.json(413, "{\"error\":\"PAYLOAD_TOO_LARGE\",\"message\":\"too large\"}");
        predict(request).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("INFERENCE_IMAGE_TOO_LARGE"));
        predict = new Reply(307, "", Map.of("Location", "/elsewhere"));
        predict(request).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("INFERENCE_RUNTIME_INVALID_RESPONSE"));
        assertThat(PREDICTS.get()).isEqualTo(6);
        assertThat(REDIRECTED.get()).isZero();
    }
    @Test void runtimeTimeoutIsUnavailable() throws Exception {
        hold = new CountDownLatch(1);
        try {
            predict(body("detector-v1", image("png", 64, 48))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INFERENCE_RUNTIME_UNAVAILABLE"));
        } finally { hold.countDown(); }
        assertThat(PREDICTS.get()).isEqualTo(1);
    }
    @Test void concurrentRequestIsBusyWhileOneIsInFlight() throws Exception {
        hold = new CountDownLatch(1);
        String request = body("detector-v1", image("png", 64, 48));
        var first = Executors.newSingleThreadExecutor().submit(() -> predict(request).andReturn().getResponse().getStatus());
        for (int i = 0; i < 100 && PREDICTS.get() == 0; i++) Thread.sleep(20);
        predict(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INFERENCE_BUSY"));
        hold.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(PREDICTS.get()).isEqualTo(1);
    }
    @Test void invalidRuntimeResultsNeverReachTheBrowser() throws Exception {
        String request = body("detector-v1", image("png", 64, 48));
        String person = "{\"label\":\"person\",\"score\":0.5,\"x\":0,\"y\":0,\"width\":10,\"height\":10}";
        for (String invalid : List.of(
                result("other-model", 64, 48, "[]"),
                result(64, 48, "[]").replace("gpu-node", "other-node"),
                result(64, 48, "[]").replace("pytorch-cuda", "cpu"),
                result(65, 48, "[]"),
                result(64, 48, "[" + person.replace("\"x\":0", "\"x\":60") + "]"),
                result(64, 48, "[" + person.replace("\"y\":0", "\"y\":-1") + "]"),
                result(64, 48, "[" + person.replace("\"width\":10", "\"width\":0") + "]"),
                result(64, 48, "[" + person.replace("0.5", "1.5") + "]"),
                result(64, 48, "[" + person.replace("\"person\"", "\"\"") + "]"),
                result(64, 48, "[" + String.join(",", Collections.nCopies(101, person)) + "]"),
                result(64, 48, "{}"),
                "{\"modelId\":\"detector-v1\"}",
                "not-json"))
        {
            predict = Reply.json(200, invalid);
            predict(request).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("INFERENCE_RUNTIME_INVALID_RESPONSE"));
        }
        predict = Reply.json(200, result(64, 48, "[" + person.replace("\"width\":10", "\"width\":64.4") + "]"));
        predict(request).andExpect(status().isOk());
    }
}
