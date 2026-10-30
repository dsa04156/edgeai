package io.edgeai.adapters.registry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * In-process OCI distribution endpoint over real HTTP: content-addressed manifests/blobs, tag lists with Link
 * pagination and programmable failures. Shared by the adapter tests and the API integration tests.
 */
public final class FakeRegistry implements AutoCloseable {
    public static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    public static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
    public static final String OCI_CONFIG = "application/vnd.oci.image.config.v1+json";
    public record Content(String mediaType, byte[] bytes, String digest) {}
    private final HttpServer server;
    public final Map<String, Content> manifests = new ConcurrentHashMap<>();   // repo@digest and repo:tag
    public final Map<String, Content> blobs = new ConcurrentHashMap<>();       // repo@digest
    public final Map<String, List<String>> tags = new ConcurrentHashMap<>();
    public final List<String> repositories = new CopyOnWriteArrayList<>();
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public final List<String> authorizations = new CopyOnWriteArrayList<>();
    /** Programmable failure: return true when the exchange was handled. */
    public interface Hook { boolean handle(HttpExchange exchange) throws Exception; }
    public volatile Hook override = e -> false;

    public FakeRegistry() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public String host() { return "127.0.0.1:" + server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }
    public void reset() {
        manifests.clear(); blobs.clear(); tags.clear(); repositories.clear(); requests.clear(); authorizations.clear(); override = e -> false;
    }

    public static String sha256(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public Content blob(String repository, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        var content = new Content("application/octet-stream", bytes, sha256(bytes));
        blobs.put(repository + "@" + content.digest(), content);
        return content;
    }
    public Content manifest(String repository, String tag, String mediaType, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        var content = new Content(mediaType, bytes, sha256(bytes));
        manifests.put(repository + "@" + content.digest(), content);
        if (tag != null) {
            manifests.put(repository + ":" + tag, content);
            tags.computeIfAbsent(repository, k -> new CopyOnWriteArrayList<>()).add(tag);
        }
        if (!repositories.contains(repository)) repositories.add(repository);
        return content;
    }
    /** A single-platform image: config blob + manifest. */
    public Content image(String repository, String tag, String os, String architecture) {
        var config = blob(repository, "{\"architecture\":\"" + architecture + "\",\"os\":\"" + os + "\",\"created\":\"2026-10-01T00:00:00Z\",\"config\":{},\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[]}}");
        return manifest(repository, tag, OCI_MANIFEST, "{\"schemaVersion\":2,\"mediaType\":\"" + OCI_MANIFEST + "\",\"config\":{\"mediaType\":\"" + OCI_CONFIG
            + "\",\"digest\":\"" + config.digest() + "\",\"size\":" + config.bytes().length + "},\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"digest\":\"sha256:"
            + "1".repeat(64) + "\",\"size\":1234}]}");
    }
    /** A multi-platform index of linux/amd64 + linux/arm64 plus a BuildKit attestation entry (unknown/unknown). */
    public Content multiPlatform(String repository, String tag) {
        var amd = image(repository, null, "linux", "amd64");
        var arm = image(repository, null, "linux", "arm64");
        var attestation = manifest(repository, null, OCI_MANIFEST, "{\"schemaVersion\":2,\"mediaType\":\"" + OCI_MANIFEST
            + "\",\"config\":{\"mediaType\":\"application/vnd.in-toto+json\",\"digest\":\"sha256:" + "2".repeat(64) + "\",\"size\":2},\"layers\":[]}");
        return manifest(repository, tag, OCI_INDEX, "{\"schemaVersion\":2,\"mediaType\":\"" + OCI_INDEX + "\",\"manifests\":["
            + entry(amd, "\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}") + ","
            + entry(arm, "\"platform\":{\"architecture\":\"arm64\",\"os\":\"linux\",\"variant\":\"v8\"}") + ","
            + entry(attestation, "\"platform\":{\"architecture\":\"unknown\",\"os\":\"unknown\"},\"annotations\":{\"vnd.docker.reference.type\":\"attestation-manifest\",\"vnd.docker.reference.digest\":\"" + amd.digest() + "\"}")
            + "]}");
    }
    private static String entry(Content c, String extra) {
        return "{\"mediaType\":\"" + c.mediaType() + "\",\"digest\":\"" + c.digest() + "\",\"size\":" + c.bytes().length + "," + extra + "}";
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath(), query = exchange.getRequestURI().getRawQuery();
            requests.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query) + " accept=" + exchange.getRequestHeaders().getFirst("Accept"));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) authorizations.add(auth);
            try { if (override.handle(exchange)) return; } catch (Exception e) { throw new IOException(e); }
            var params = new HashMap<String, String>();
            if (query != null) for (String pair : query.split("&")) { int i = pair.indexOf('='); params.put(pair.substring(0, i), java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8)); }
            if (path.equals("/v2/_catalog")) { page(exchange, "/v2/_catalog", "repositories", null, new ArrayList<>(repositories), params); return; }
            if (path.startsWith("/v2/") && path.endsWith("/tags/list")) {
                String repo = path.substring(4, path.length() - "/tags/list".length());
                var list = tags.get(repo);
                if (list == null) { send(exchange, 404, "application/json", "{\"errors\":[{\"code\":\"NAME_UNKNOWN\"}]}", null); return; }
                page(exchange, path, "tags", repo, new ArrayList<>(list), params); return;
            }
            int manifestAt = path.indexOf("/manifests/"), blobAt = path.indexOf("/blobs/");
            if (path.startsWith("/v2/") && manifestAt > 0) {
                String repo = path.substring(4, manifestAt), ref = path.substring(manifestAt + "/manifests/".length());
                var content = manifests.get(repo + (ref.startsWith("sha256:") ? "@" : ":") + ref);
                if (content == null) { send(exchange, 404, "application/json", "{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\"}]}", null); return; }
                exchange.getResponseHeaders().add("Docker-Content-Digest", content.digest());
                send(exchange, 200, content.mediaType(), null, content.bytes()); return;
            }
            if (path.startsWith("/v2/") && blobAt > 0) {
                var content = blobs.get(path.substring(4, blobAt) + "@" + path.substring(blobAt + "/blobs/".length()));
                if (content == null) { send(exchange, 404, "application/json", "{\"errors\":[{\"code\":\"BLOB_UNKNOWN\"}]}", null); return; }
                send(exchange, 200, content.mediaType(), null, content.bytes()); return;
            }
            send(exchange, 404, "text/plain", "404 page not found", null);
        }
    }
    private void page(HttpExchange exchange, String path, String field, String name, List<String> all, Map<String, String> params) throws IOException {
        Collections.sort(all);
        int n = params.containsKey("n") ? Integer.parseInt(params.get("n")) : 100;
        String last = params.get("last");
        var remaining = all.stream().filter(v -> last == null || v.compareTo(last) > 0).toList();
        var items = remaining.subList(0, Math.min(n, remaining.size()));
        if (remaining.size() > n)
            exchange.getResponseHeaders().add("Link", "<" + path + "?last=" + items.get(items.size() - 1) + "&n=" + n + ">; rel=\"next\"");
        String json = items.stream().map(v -> "\"" + v + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
        send(exchange, 200, "application/json", name == null ? "{\"" + field + "\":" + json + "}" : "{\"name\":\"" + name + "\",\"" + field + "\":" + json + "}", null);
    }
    public static void send(HttpExchange exchange, int status, String type, String text, byte[] bytes) throws IOException {
        byte[] body = bytes != null ? bytes : text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Docker-Distribution-Api-Version", "registry/2.0");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }
}
