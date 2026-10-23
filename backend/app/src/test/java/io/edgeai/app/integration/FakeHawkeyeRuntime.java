package io.edgeai.app.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * TEST-ONLY boundary runtime speaking the Hawkeye adapter HTTP contract with fixed fake values.
 * It never runs a model; real Hawkeye inference is verified separately against Ollama.
 */
final class FakeHawkeyeRuntime implements AutoCloseable {
    static final String MODEL = "hf.co/fake/hawkeye:latest", DIGEST = "d".repeat(64), SAMPLES_SHA = "5".repeat(64);
    static final Map<String, byte[]> IMAGES = Map.of(
        "forklift_0004", "fake-jpeg-forklift".getBytes(StandardCharsets.UTF_8),
        "forklift_0024", "fake-jpeg-safe".getBytes(StandardCharsets.UTF_8));

    final HttpServer server;
    final List<String> classified = new CopyOnWriteArrayList<>();
    volatile String healthBody;
    volatile int healthStatus = 200;
    /** sampleId → model output text placed in the result document. */
    volatile Function<String, String> content;
    volatile Function<String, String> classify;
    volatile int classifyStatus = 200;
    volatile CountDownLatch hold;
    volatile CountDownLatch entered = new CountDownLatch(1);

    FakeHawkeyeRuntime() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/health", exchange -> reply(exchange, healthStatus, "application/json", healthBody.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/samples/", exchange -> {
            var image = IMAGES.get(exchange.getRequestURI().getPath().substring("/samples/".length()));
            if (image == null) reply(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            else reply(exchange, 200, "image/jpeg", image);
        });
        server.createContext("/classify", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var sample = body.replaceAll(".*\"sampleId\":\"([a-z0-9_]+)\".*", "$1");
            classified.add(sample);
            entered.countDown();
            if (hold != null) try { hold.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            reply(exchange, classifyStatus, "application/json", classify.apply(sample).getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        reset();
    }

    void reset() {
        healthStatus = 200; classifyStatus = 200; hold = null; entered = new CountDownLatch(1);
        classified.clear();
        healthBody = health("READY", DIGEST);
        content = sample -> sample.equals("forklift_0004")
            ? "**forklift_violation**\n\nA forklift carries a raised load while a worker stands in the aisle."
            : "Category: spill\n\nDescription: A small dark puddle is visible near the rack.";
        classify = this::result;
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    String health(String status, String digest) {
        return "{\"status\":\"" + status + "\",\"nodeName\":\"fake-gpu-node\",\"model\":\"" + MODEL + "\",\"modelDigest\":\"" + digest
            + "\",\"ollamaVersion\":\"0.0.0-fake\",\"samplesSha256\":\"" + SAMPLES_SHA + "\",\"promptSha256\":\"" + "7".repeat(64)
            + "\",\"placement\":{\"processor\":\"GPU\",\"sizeBytes\":1000,\"sizeVramBytes\":1000}}";
    }
    String result(String sample) {
        return "{\"sampleId\":\"" + sample + "\",\"inputSha256\":\"" + sha(IMAGES.get(sample)) + "\",\"nodeName\":\"fake-gpu-node\",\"model\":\""
            + MODEL + "\",\"modelDigest\":\"" + DIGEST + "\",\"loadedDigest\":\"" + DIGEST + "\",\"ollamaVersion\":\"0.0.0-fake\",\"samplesSha256\":\""
            + SAMPLES_SHA + "\",\"promptSha256\":\"" + "7".repeat(64) + "\",\"placement\":{\"processor\":\"GPU\",\"sizeBytes\":1000,\"sizeVramBytes\":1000},"
            + "\"content\":" + quote(content.apply(sample)) + ",\"doneReason\":\"stop\",\"totalMs\":0.75,\"loadMs\":0.25,"
            + "\"promptEvalCount\":627,\"evalCount\":71,\"adapterMs\":0.8}";
    }
    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void reply(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
    @Override public void close() { server.stop(0); }
}
