package io.edgeai.app.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;

/**
 * TEST-ONLY boundary runtime speaking the scenario runtime HTTP contract with fixed fake values.
 * It never runs a model; it exists so the public API can be exercised without GPU/NPU hardware.
 */
final class FakeClassificationRuntime implements AutoCloseable {
    static final String INPUTS_SHA = "1".repeat(64);
    static final byte[] MANIFEST = manifest();
    static final String MANIFEST_SHA = sha(MANIFEST);

    final String target, modelSha, provider;
    final HttpServer server;
    final List<Integer> classified = new CopyOnWriteArrayList<>();
    volatile String healthBody;
    volatile int healthStatus = 200;
    /** Index → response; default is a valid result. */
    volatile IntFunction<String> classify;
    volatile int classifyStatus = 200;
    /** When set, /classify waits for it (index → latch) after recording the call. */
    volatile IntFunction<CountDownLatch> hold = index -> null;
    volatile CountDownLatch manifestHold;
    final CountDownLatch manifestEntered = new CountDownLatch(1);
    final ConcurrentHashMap<Integer, CountDownLatch> entered = new ConcurrentHashMap<>();

    FakeClassificationRuntime(String target, String modelSha, String provider) throws IOException {
        this.target = target; this.modelSha = modelSha; this.provider = provider;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/health", exchange -> reply(exchange, healthStatus, healthBody));
        server.createContext("/dataset/manifest", exchange -> {
            manifestEntered.countDown();
            await(manifestHold);
            reply(exchange, 200, new String(MANIFEST, StandardCharsets.UTF_8));
        });
        server.createContext("/classify", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int index = Integer.parseInt(body.replaceAll(".*\"index\":(\\d+).*", "$1"));
            classified.add(index);
            entered(index).countDown();
            await(hold.apply(index));
            reply(exchange, classifyStatus, classify.apply(index));
        });
        server.start();
        reset();
    }

    void reset() {
        healthStatus = 200; classifyStatus = 200; manifestHold = null; hold = index -> null;
        classified.clear(); entered.clear();
        healthBody = health("READY", modelSha);
        classify = this::result;
    }
    CountDownLatch entered(int index) { return entered.computeIfAbsent(index, k -> new CountDownLatch(1)); }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String node() { return target.equals("GPU") ? "fake-gpu-node" : "fake-npu-node"; }
    String device() { return target.equals("GPU") ? "Fake GPU 0" : "Fake NPU 0"; }

    String health(String status, String reportedModelSha) {
        return "{\"status\":\"" + status + "\",\"target\":\"" + target + "\",\"nodeName\":\"" + node() + "\",\"deviceName\":\"" + device()
            + "\",\"provider\":\"" + provider + "\",\"precision\":\"fake-precision\",\"runtime\":\"fake-runtime 0\",\"modelSha256\":\""
            + reportedModelSha + "\",\"manifestSha256\":\"" + MANIFEST_SHA + "\",\"inputsSha256\":\"" + INPUTS_SHA + "\"}";
    }
    String result(int index) { return result(index, tensorSha(index), "[{\"classIndex\":" + top1(index) + ",\"score\":9.5},{\"classIndex\":999,\"score\":1.25}]"); }
    String result(int index, String inputSha, String topK) {
        return "{\"index\":" + index + ",\"manifestSha256\":\"" + MANIFEST_SHA + "\",\"modelSha256\":\"" + modelSha + "\",\"nodeName\":\"" + node()
            + "\",\"deviceName\":\"" + device() + "\",\"provider\":\"" + provider + "\",\"inputSha256\":\"" + inputSha
            + "\",\"topK\":" + topK + ",\"inferenceMs\":0.5}";
    }
    /** Both targets agree except on image 3, where only the GPU matches the label. */
    int top1(int index) { return target.equals("NPU") && index == 3 ? 998 : label(index); }
    static int label(int index) { return index % 10; }
    static String tensorSha(int index) { return sha(("tensor-" + index).getBytes(StandardCharsets.UTF_8)); }

    private static byte[] manifest() {
        var rows = new StringJoiner(",");
        for (int i = 0; i < 100; i++)
            rows.add("{\"path\":\"fixture/" + i + ".JPEG\",\"tensor_sha256\":\"" + tensorSha(i) + "\",\"label\":" + label(i) + ",\"reference_top1\":" + label(i) + "}");
        return ("{\"kind\":\"fake-fixture\",\"preprocessing\":\"fake-preprocessing\",\"inputs_sha256\":\"" + INPUTS_SHA + "\",\"samples\":[" + rows + "]}")
            .getBytes(StandardCharsets.UTF_8);
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void await(CountDownLatch latch) {
        if (latch == null) return;
        try { latch.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
    @Override public void close() { server.stop(0); }
}
