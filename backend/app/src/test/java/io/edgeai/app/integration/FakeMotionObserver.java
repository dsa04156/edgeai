package io.edgeai.app.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.edgeai.app.support.JsonDocuments;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * TEST-ONLY boundary speaking the motion observer HTTP contract (hawkeye-runtime/motion server.py) with fixed values.
 * It never reads a camera or runs a detector; those run in the real service.
 */
final class FakeMotionObserver implements AutoCloseable {
    private static final JsonDocuments JSON = new JsonDocuments();
    final HttpServer server;
    /** sessionId → source of every PUT, in order. */
    final List<Map<String, Object>> puts = new CopyOnWriteArrayList<>();
    final List<String> deletes = new CopyOnWriteArrayList<>();
    volatile boolean ready = true;
    volatile String session;
    volatile String epoch = UUID.randomUUID().toString();
    volatile String state = "RUNNING";
    volatile Map<String, Object> failure;
    volatile Object source;
    /** Epoch named by each frame request. */
    final List<String> frameEpochs = new CopyOnWriteArrayList<>();
    /** When set, a PUT waits for it after counting down putEntered. */
    volatile CountDownLatch holdPut;
    volatile CountDownLatch putEntered = new CountDownLatch(1);
    static final byte[] FRAME = HawkeyeVideoSessionIntegrationTest.jpeg(77);

    FakeMotionObserver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/health", exchange -> json(exchange, ready ? 200 : 503, Map.of("ready", ready, "nodeName", "fake-motion-node",
            "pod", "fake-motion-pod", "provider", "CPUExecutionProvider", "model", Map.of("sha256", "7".repeat(64), "version", "deployable_rn50_v1.0.2"))));
        server.createContext("/observers/", this::observers);
        server.start();
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    void reset() { puts.clear(); deletes.clear(); frameEpochs.clear(); holdPut = null; putEntered = new CountDownLatch(1); ready = true; session = null; epoch = UUID.randomUUID().toString(); state = "RUNNING"; failure = null; source = null; }

    private Map<String, Object> describe() {
        var body = new LinkedHashMap<String, Object>();
        body.put("sessionId", session); body.put("epoch", epoch); body.put("state", state); body.put("failure", failure);
        body.put("lastError", null); body.put("source", source);
        body.put("observer", Map.of("nodeName", "fake-motion-node", "pod", "fake-motion-pod", "provider", "CPUExecutionProvider"));
        body.put("stats", Map.of("processed", 12, "resets", 1, "lastDetectMs", 80.5));
        return body;
    }

    @SuppressWarnings("unchecked")
    private void observers(HttpExchange exchange) throws IOException {
        var parts = exchange.getRequestURI().getPath().substring("/observers/".length()).split("/");
        String id = parts[0], method = exchange.getRequestMethod();
        if (parts.length == 1 && method.equals("PUT")) {
            putEntered.countDown();
            var hold = holdPut;
            if (hold != null) try { hold.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            var body = (Map<String, Object>) JSON.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            puts.add(Map.of("sessionId", id, "source", body.get("source"), "leaseSeconds", ((Number) body.get("leaseSeconds")).intValue()));
            if (id.equals(session)) {
                if (!state.equals("RUNNING")) { json(exchange, 409, Map.of("error", "OBSERVER_" + state)); return; }
                json(exchange, 200, describe()); return;
            }
            session = id; source = body.get("source"); state = "RUNNING";
            json(exchange, 201, describe()); return;
        }
        if (!id.equals(session)) { json(exchange, 404, Map.of("error", "OBSERVER_NOT_FOUND")); return; }
        if (parts.length == 1 && method.equals("DELETE")) {
            deletes.add(id); if (state.equals("RUNNING")) state = "STOPPED"; json(exchange, 200, describe()); return;
        }
        if (parts.length == 2 && parts[1].equals("live")) {
            var body = describe();
            body.put("latest", Map.of("seq", 12, "frameTime", "2026-10-26T03:00:06.000000Z", "status", "NEAR_PATH", "tracks", List.of(), "pairs", List.of()));
            json(exchange, 200, body); return;
        }
        if (parts.length == 3 && parts[1].equals("frames")) {
            String epochQuery = Objects.toString(exchange.getRequestURI().getRawQuery(), "").replaceFirst("^epoch=", "");
            frameEpochs.add(epochQuery);
            if (!epochQuery.equals(epoch)) { json(exchange, 409, Map.of("error", "EPOCH_MISMATCH")); return; }
            if (!parts[2].equals("12")) { json(exchange, 404, Map.of("error", "FRAME_NOT_FOUND")); return; }
            reply(exchange, 200, "image/jpeg", FRAME); return;
        }
        json(exchange, 404, Map.of("error", "NOT_FOUND"));
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        reply(exchange, status, "application/json", JSON.canonical(body).getBytes(StandardCharsets.UTF_8));
    }
    private static void reply(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
    @Override public void close() { server.stop(0); }
}
