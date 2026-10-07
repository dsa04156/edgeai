package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.node.InfrastructureSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed node GET path only. Sensor inventory belongs exclusively to EdgeX. */
public final class KubernetesInfrastructureInventory implements InfrastructureSource, AutoCloseable {
    private final KubernetesRuntimeHttp http;
    private final String context;
    private final JsonMapper json = new JsonMapper();
    public KubernetesInfrastructureInventory(String url, String token, String ca) {
        http = new KubernetesRuntimeHttp(url, token, ca); context = null;
    }
    public KubernetesInfrastructureInventory(String context) {
        if (context.isBlank()) throw new IllegalArgumentException("A fixed kubectl context is required");
        this.context = context; http = null;
    }
    private byte[] read(String path) {
        if (http != null) {
            var reply = http.request("GET", path, null, Duration.ofSeconds(4));
            if (reply.status() != 200) throw new IllegalStateException("Inventory unavailable");
            return reply.body();
        }
        Process process = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            process = new ProcessBuilder("kubectl", "--context", context, "--request-timeout=4s", "get", "--raw", path)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final Process running = process;
            var result = executor.submit(() -> {
                byte[] body = running.getInputStream().readNBytes(8 * 1024 * 1024 + 1);
                if (body.length > 8 * 1024 * 1024) throw new IllegalStateException("Inventory too large");
                return body;
            });
            try {
                byte[] body = result.get(5, TimeUnit.SECONDS);
                if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("Inventory unavailable");
                return body;
            } finally { process.destroyForcibly(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Inventory interrupted");
        } catch (Exception e) { throw new IllegalStateException("Inventory unavailable"); }
        finally { if (process != null) process.destroyForcibly(); }
    }
    private List<JsonNode> list(String path, String kind) {
        var items = new ArrayList<JsonNode>();
        var continuations = new HashSet<String>();
        String continuation = "", version = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            if (System.nanoTime() > deadline || continuations.size() > 20) throw new IllegalStateException("Inventory exceeded budget");
            var root = json.readTree(read(path + "?limit=500" + (continuation.isEmpty() ? "" : "&continue=" + URLEncoder.encode(continuation, StandardCharsets.UTF_8))));
            if (!root.path("kind").asText().equals(kind) || !root.path("items").isArray()) throw new IllegalStateException("Invalid inventory");
            String current = root.path("metadata").path("resourceVersion").asText();
            if (current.isBlank() || (version != null && !version.equals(current))) throw new IllegalStateException("Inconsistent inventory");
            version = current;
            root.path("items").forEach(items::add);
            continuation = root.path("metadata").path("continue").asText("");
            if (!continuation.isEmpty() && !continuations.add(continuation)) throw new IllegalStateException("Repeated continuation");
        } while (!continuation.isEmpty());
        return items;
    }
    @Override public List<Node> nodes() { return decodeNodes(list("/api/v1/nodes", "NodeList")); }

    public static List<Node> decodeNodes(List<JsonNode> items) {
        var result = new ArrayList<Node>(); var names = new HashSet<String>();
        for (var item : items) {
            var metadata = item.path("metadata"); var labels = metadata.path("labels"); var status = item.path("status");
            String name = metadata.path("name").asText();
            if (name.isBlank() || !names.add(name)) throw new IllegalStateException("Invalid node identity");
            boolean edge = labels.has("node-role.kubernetes.io/edge") || labels.path("environment").asText().equals("edge") || labels.has("edge.device/class");
            boolean server = labels.path("environment").asText().equals("cloud") || labels.path("gpu.platform").asText().equals("server");
            String kind = edge == server ? "UNKNOWN" : edge ? "EDGE_DEVICE" : "EDGE_AI_SERVER";
            String ready = "UNKNOWN";
            for (var condition : status.path("conditions")) if (condition.path("type").asText().equals("Ready"))
                ready = switch (condition.path("status").asText()) { case "True" -> "READY"; case "False" -> "NOT_READY"; default -> "UNKNOWN"; };
            result.add(new Node(name, kind, status.path("nodeInfo").path("architecture").asText(), status.path("nodeInfo").path("operatingSystem").asText(), ready));
        }
        return result.stream().sorted(Comparator.comparing(Node::name)).toList();
    }
    @Override public void close() { if (http != null) http.close(); }
}
