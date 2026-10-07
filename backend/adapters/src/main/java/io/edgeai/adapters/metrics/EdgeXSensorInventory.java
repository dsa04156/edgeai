package io.edgeai.adapters.metrics;

import io.edgeai.domain.node.SensorInventorySource;
import io.edgeai.domain.node.InfrastructureSource.Sensor;
import java.net.URI;
import java.net.http.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only Core Metadata inventory. No commands, registration writes, protocol secrets or legacy fallback. */
public final class EdgeXSensorInventory implements SensorInventorySource, AutoCloseable {
    private final URI origin;
    private final HttpClient client;
    private final JsonMapper json = new JsonMapper();
    public EdgeXSensorInventory(String url) {
        origin = URI.create(url.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
            || !origin.getPath().isEmpty() || origin.getQuery() != null || origin.getFragment() != null)
            throw new IllegalArgumentException("EdgeX URL must be an HTTP(S) origin without credentials");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private List<JsonNode> list(String path, String field) {
        var items = new ArrayList<JsonNode>();
        int total = -1;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            if (System.nanoTime() > deadline || items.size() > 10000) throw new IllegalStateException("EdgeX inventory exceeded budget");
            try {
                var request = HttpRequest.newBuilder(origin.resolve(path + "?offset=" + items.size() + "&limit=100"))
                    .timeout(Duration.ofSeconds(4)).header("Accept", "application/json").GET().build();
                var response = client.send(request, ignored -> new LimitedBody());
                if (response.statusCode() != 200) throw new IllegalStateException("EdgeX inventory unavailable");
                var root = json.readTree(response.body());
                int count = root.path("totalCount").asInt(-1);
                if (root.path("statusCode").asInt() != 200 || !root.path(field).isArray() || count < 0
                    || (total != -1 && total != count)) throw new IllegalStateException("Incomplete EdgeX inventory");
                total = count;
                int before = items.size(); root.path(field).forEach(items::add);
                if (items.size() > total || (items.size() == before && items.size() < total)) throw new IllegalStateException("Incomplete EdgeX inventory");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("EdgeX inventory interrupted"); }
            catch (IOException e) { throw new IllegalStateException("EdgeX inventory unavailable"); }
        } while (items.size() < total);
        return items;
    }
    @Override public List<Sensor> sensors() {
        return decode(list("/api/v3/device/all", "devices"), list("/api/v3/deviceprofile/all", "profiles"));
    }
    public static List<Sensor> decode(List<JsonNode> devices, List<JsonNode> profiles) {
        var resources = new HashMap<String, List<String>>();
        for (var profile : profiles) {
            String name = profile.path("name").asText(); var properties = new ArrayList<String>();
            for (var resource : profile.path("deviceResources")) properties.add(resource.path("name").asText());
            if (name.isBlank() || resources.putIfAbsent(name, List.copyOf(properties)) != null) throw new IllegalStateException("Ambiguous EdgeX profile");
        }
        var sensors = new ArrayList<Sensor>(); var names = new HashSet<String>();
        for (var device : devices) {
            String name = device.path("name").asText(), profile = device.path("profileName").asText();
            if (name.isBlank() || !names.add(name)) throw new IllegalStateException("Ambiguous EdgeX device");
            sensors.add(new Sensor(name, device.path("tags").path("nodeName").asText(), profile,
                device.path("serviceName").asText(), device.path("adminState").asText("UNKNOWN"),
                device.path("operatingState").asText("UNKNOWN"), resources.getOrDefault(profile, List.of())));
        }
        return sensors.stream().sorted(Comparator.comparing(Sensor::name)).toList();
    }
    @Override public void close() { client.close(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription; private long size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> values) {
            for (var value : values) size += value.remaining();
            if (size > 8 * 1024 * 1024) { subscription.cancel(); delegate.onError(new IOException("EdgeX response too large")); }
            else delegate.onNext(values);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
