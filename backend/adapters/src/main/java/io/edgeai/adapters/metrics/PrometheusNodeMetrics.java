package io.edgeai.adapters.metrics;

import io.edgeai.domain.node.NodeMetricsSource;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

public final class PrometheusNodeMetrics implements NodeMetricsSource, AutoCloseable {
    public static final int MAX_AGE_SECONDS = 90;
    private final URI endpoint;
    private final HttpClient client;
    private final JsonMapper json = new JsonMapper();

    public PrometheusNodeMetrics(String url) {
        URI origin = URI.create(url.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
            || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null || !origin.getPath().isEmpty())
            throw new IllegalArgumentException("Prometheus URL must be an HTTP(S) origin without credentials");
        endpoint = origin.resolve("/api/v1/query");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public Snapshot snapshot(Instant now) {
        String form = "query=" + URLEncoder.encode(PrometheusQueries.query(), StandardCharsets.UTF_8)
            + "&timeout=3s&time=" + now.getEpochSecond();
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4))
            .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            var response = client.send(request, ignored -> new LimitedBody());
            if (response.statusCode() != 200) throw new IllegalStateException("Prometheus query unavailable");
            return decode(response.body(), now);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Prometheus query interrupted");
        } catch (IOException failure) { throw new IllegalStateException("Prometheus query unavailable"); }
    }

    record Sample(Map<String, String> labels, double value) {
        String label(String key) { return labels.getOrDefault(key, ""); }
        String target() { return label("job") + "\n" + label("instance"); }
        String identity() {
            var copy = new TreeMap<>(labels); copy.remove("__name__"); copy.remove("edgeai_metric");
            return copy.toString();
        }
        String device() {
            for (String key : List.of("UUID", "device", "gpu")) if (!label(key).isBlank()) return label(key);
            return "";
        }
    }

    /** Public for deterministic parsing tests; no requests or servers are needed. */
    public Snapshot decode(byte[] body, Instant now) {
        var root = json.readTree(body);
        if (!root.path("status").asText().equals("success") || !root.path("data").path("resultType").asText().equals("vector")
            || !root.path("data").path("result").isArray() || root.path("warnings").size() > 0)
            throw new IllegalStateException("Incomplete Prometheus response");
        var samples = new ArrayList<Sample>();
        for (var row : root.path("data").path("result")) {
            if (!row.path("metric").isObject() || row.path("value").size() != 2) throw new IllegalStateException("Invalid Prometheus sample");
            var labels = new TreeMap<String, String>();
            row.path("metric").properties().forEach(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
            double value;
            try { value = Double.parseDouble(row.path("value").get(1).asText()); }
            catch (NumberFormatException ignored) { continue; }
            if (Double.isFinite(value)) samples.add(new Sample(labels, value));
        }
        var nodes = new HashSet<String>();
        var pods = new HashMap<String, String>();
        var hosts = new HashMap<String, String>();
        var instances = new HashMap<String, String>();
        for (var sample : samples) if (sample.label("__name__").equals("kube_node_info") && sample.value() == 1) {
            String node = sample.label("node");
            if (!node.isBlank()) { nodes.add(node); alias(hosts, sample.label("internal_ip"), node); }
        }
        for (var sample : samples) {
            String node = canonical(sample.label("node"), nodes);
            if (sample.label("__name__").equals("kube_pod_info") && sample.value() == 1 && node != null)
                alias(pods, sample.label("namespace") + "/" + sample.label("pod"), node);
            if (sample.label("__name__").equals("node_uname_info") && sample.value() == 1) {
                String name = canonical(sample.label("nodename"), nodes);
                if (name != null) alias(instances, sample.target(), name);
            }
        }
        var tagged = new HashMap<String, Map<String, Sample>>();
        var health = new HashMap<String, List<Sample>>();
        for (var sample : samples) {
            String tag = sample.label("edgeai_metric");
            if (tag.startsWith("health_")) health.computeIfAbsent(tag.substring(7), key -> new ArrayList<>()).add(sample);
            else if (!tag.isEmpty()) tagged.computeIfAbsent(tag, key -> new HashMap<>()).put(sample.identity(), sample);
            else health.computeIfAbsent(sample.label("__name__"), key -> new ArrayList<>()).add(sample);
        }
        var rows = new TreeMap<String, List<Measurement>>();
        var hardware = new HashMap<String, List<Accelerator>>();
        int unmapped = 0;
        for (var sample : tagged.getOrDefault("accelerator", Map.of()).values()) {
            if (sample.value() != 1 || !Set.of("GPU", "NPU").contains(sample.label("kind")) || sample.device().isBlank()) continue;
            var stamp = tagged.getOrDefault("accelerator_time", Map.of()).get(sample.identity());
            if (stamp == null || stamp.value() < 0 || stamp.value() > now.plusSeconds(5).getEpochSecond()) continue;
            String node = resolve(sample, nodes, pods, hosts, instances);
            if (node == null) { unmapped++; continue; }
            Instant observedAt = Instant.ofEpochMilli((long) (stamp.value() * 1000));
            boolean current = fresh(observedAt, now) && healthy("up", sample, health, tagged, now)
                && healthy("node_hardware_inventory_success", sample, health, tagged, now);
            hardware.computeIfAbsent(node, key -> new ArrayList<>()).add(new Accelerator(sample.device(), sample.label("kind"),
                sample.label("vendor"), sample.label("model"), observedAt, current));
            rows.computeIfAbsent(node, key -> new ArrayList<>());
        }
        hardware.replaceAll((node, devices) -> devices.stream().filter(a -> devices.stream()
            .filter(b -> a.kind().equals(b.kind()) && a.device().equals(b.device())).count() == 1)
            .sorted(Comparator.comparing(Accelerator::kind).thenComparing(Accelerator::device)).toList());
        for (var metric : PrometheusQueries.METRICS) {
            for (var sample : tagged.getOrDefault(metric.id(), Map.of()).values()) {
                var stamp = tagged.getOrDefault(metric.id() + "_time", Map.of()).get(sample.identity());
                if (stamp == null || !valid(metric.unit(), sample.value()) || stamp.value() < 0 || stamp.value() > now.plusSeconds(5).getEpochSecond()) continue;
                String node = resolve(sample, nodes, pods, hosts, instances);
                if (node == null) { unmapped++; continue; }
                Instant observedAt = Instant.ofEpochMilli((long) (stamp.value() * 1000));
                boolean fresh = fresh(observedAt, now) && healthy("up", sample, health, tagged, now)
                    && (metric.collector().isEmpty() || healthy(metric.collector(), sample, health, tagged, now));
                String device = metric.key().startsWith("CPU_") || metric.key().equals("MEMORY_USAGE") ? ""
                    : metric.id().startsWith("jetson") ? "Jetson GPU" : metric.id().startsWith("spark") ? "GPU " + sample.label("gpu")
                    : sample.device();
                if (metric.key().startsWith("GPU_") || metric.key().startsWith("NPU_")) {
                    String kind = metric.key().substring(0, 3);
                    var candidates = hardware.getOrDefault(node, List.of()).stream().filter(a -> a.kind().equals(kind)).toList();
                    String address = pciAddress(sample.label("pci_bus_id").isBlank() ? sample.label("device") : sample.label("pci_bus_id"));
                    var matches = candidates.stream().filter(a -> !address.isEmpty() && pciAddress(a.device()).equals(address)).toList();
                    // Non-PCI exporters have no stable bus address. Only a unique vendor device AND
                    // a unique observed device on this node permit the fallback; never pair by index.
                    String vendor = metric.id().startsWith("jetson") || metric.id().startsWith("spark") ? "nvidia"
                        : metric.id().startsWith("mobilint") ? "mobilint" : "";
                    if (matches.isEmpty() && !vendor.isEmpty()) {
                        long peers = tagged.getOrDefault(metric.id(), Map.of()).values().stream()
                            .filter(s -> node.equals(resolve(s, nodes, pods, hosts, instances))).map(Sample::device).distinct().count();
                        if (peers == 1) matches = candidates.stream().filter(a -> a.vendor().equalsIgnoreCase(vendor)).toList();
                    }
                    if (matches.size() == 1) device = matches.getFirst().device();
                }
                rows.computeIfAbsent(node, key -> new ArrayList<>()).add(new Measurement(metric.key(), device, sample.value(), metric.unit(), observedAt, fresh));
            }
        }
        var readiness = new HashMap<String, List<Readiness>>();
        for (var sample : tagged.getOrDefault("node_ready", Map.of()).values()) {
            if (sample.value() != 1) continue;
            String node = canonical(sample.label("node"), nodes);
            var stamp = tagged.getOrDefault("node_ready_time", Map.of()).get(sample.identity());
            if (node == null || stamp == null || stamp.value() < 0 || stamp.value() > now.plusSeconds(5).getEpochSecond()) continue;
            String status = switch (sample.label("status")) {
                case "true" -> "READY"; case "false" -> "NOT_READY"; default -> "UNKNOWN";
            };
            Instant observedAt = Instant.ofEpochMilli((long) (stamp.value() * 1000));
            boolean current = fresh(observedAt, now) && healthy("up", sample, health, tagged, now);
            readiness.computeIfAbsent(node, key -> new ArrayList<>()).add(new Readiness(status, observedAt, current));
            // A node remains visible even when its usage exporter has no samples.
            rows.computeIfAbsent(node, key -> new ArrayList<>());
        }
        // Conflicting exporters for one device/metric are omitted, never chosen by iteration order.
        var items = rows.entrySet().stream().map(entry -> {
            var values = entry.getValue();
            var unique = values.stream().filter(value -> values.stream().filter(other -> other.key().equals(value.key()) && other.device().equals(value.device())).count() == 1)
                .sorted(Comparator.comparing(Measurement::key).thenComparing(Measurement::device)).toList();
            var conditions = readiness.getOrDefault(entry.getKey(), List.of());
            return new NodeMetrics(entry.getKey(), unique, conditions.size() == 1 ? conditions.getFirst() : null,
                hardware.getOrDefault(entry.getKey(), List.of()));
        }).toList();
        return new Snapshot("AVAILABLE", now, MAX_AGE_SECONDS, items, unmapped);
    }

    private static String pciAddress(String value) {
        String address = value.toLowerCase(Locale.ROOT).replaceFirst("^pci:", "");
        if (!address.matches("[0-9a-f]{4,8}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-7]")) return "";
        int colon = address.indexOf(':');
        return Integer.parseUnsignedInt(address.substring(0, colon), 16) + address.substring(colon);
    }

    private static boolean healthy(String name, Sample source, Map<String, List<Sample>> health,
                                   Map<String, Map<String, Sample>> tagged, Instant now) {
        var matches = health.getOrDefault(name, List.of()).stream().filter(s -> s.target().equals(source.target())
            && (s.label("device").isEmpty() || s.label("device").equals(source.label("device")))).toList();
        return matches.size() == 1 && matches.getFirst().value() == 1
            && Optional.ofNullable(tagged.getOrDefault(name + "_time", Map.of()).get(matches.getFirst().identity()))
                .filter(s -> s.value() >= now.minusSeconds(MAX_AGE_SECONDS).getEpochSecond() && s.value() <= now.plusSeconds(5).getEpochSecond()).isPresent();
    }
    private static boolean fresh(Instant time, Instant now) { return !time.isBefore(now.minusSeconds(MAX_AGE_SECONDS)); }
    private static boolean valid(String unit, double value) {
        return value >= 0 && switch (unit) {
            case "PERCENT" -> value <= 100;
            case "CELSIUS" -> value <= 200;
            case "WATTS" -> value <= 100000;
            default -> value <= 1e16;
        };
    }
    private static void alias(Map<String, String> values, String key, String node) {
        if (!key.isBlank()) values.merge(key, node, (a, b) -> a.equals(b) ? a : "");
    }
    private static String canonical(String name, Set<String> nodes) {
        var matches = nodes.stream().filter(n -> n.equalsIgnoreCase(name)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }
    private static String resolve(Sample sample, Set<String> nodes, Map<String, String> pods, Map<String, String> hosts, Map<String, String> instances) {
        var candidates = new HashSet<String>();
        for (String label : List.of("node", "nodename", "Hostname")) {
            String value = canonical(sample.label(label), nodes); if (value != null) candidates.add(value);
        }
        String host = sample.label("instance");
        if (host.startsWith("[")) { int end = host.indexOf(']'); host = end < 0 ? "" : host.substring(1, end); }
        else if (host.indexOf(':') == host.lastIndexOf(':') && host.contains(":")) host = host.substring(0, host.indexOf(':'));
        for (String value : Arrays.asList(pods.get(sample.label("namespace") + "/" + sample.label("pod")), hosts.get(host), instances.get(sample.target())))
            if (value != null) { if (value.isEmpty()) return null; candidates.add(value); }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }
    @Override public void close() { client.close(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> values) {
            for (var value : values) size += value.remaining();
            if (size > 4 * 1024 * 1024) { subscription.cancel(); delegate.onError(new IOException("Prometheus response too large")); }
            else delegate.onNext(values);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
