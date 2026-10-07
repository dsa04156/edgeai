package io.edgeai.adapters.metrics;

import io.edgeai.domain.node.SensorRegistrationSource;
import io.edgeai.domain.node.SensorRegistrationSource.*;
import static io.edgeai.domain.node.SensorRegistrationSource.Failure.Kind.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Server-owned driver contracts for deploy/edgex. No arbitrary upstream URL or protocol JSON. */
public final class EdgeXSensorRegistration implements SensorRegistrationSource, AutoCloseable {
    private static final List<Integer> BAUD_RATES = List.of(1200,2400,4800,9600,19200,38400,57600,115200,230400,460800,921600);
    private static final List<Template> TEMPLATES = templates();
    private final String origin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public EdgeXSensorRegistration(String origin) {
        URI uri = URI.create(origin);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || !uri.getPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("EdgeX URL must be an HTTP(S) origin without credentials");
        this.origin = origin;
    }

    private static List<Template> templates() {
        List<Template> result = new ArrayList<>();
        String[] channels = {"temperature", "light", "magnetic", "acceleration-x", "acceleration-y", "acceleration-z"};
        String[] labels = {"온도", "조도", "자기", "가속도 X", "가속도 Y", "가속도 Z"};
        for (int i = 0; i < channels.length; i++) {
            String profile = "etri-arduino-" + channels[i];
            result.add(new Template(profile, "Arduino · " + labels[i], "device-serial-jetson", "etri-dev0001-jetorn",
                profile, "SERIAL", List.of(channels[i].replace('-', '_') + "_raw"), "/dev/edgeai/arduino-001", BAUD_RATES));
        }
        String[] groups = {"temperature", "humidity", "pressure", "compass", "orientation", "gyroscope"};
        String[] names = {"온도", "습도", "기압", "방향", "자세", "자이로"};
        List<List<String>> resources = List.of(List.of("temp_humidity", "temp_pressure"), List.of("humidity"),
            List.of("pressure"), List.of("compass"), List.of("pitch", "roll", "yaw"), List.of("gyro_x", "gyro_y", "gyro_z"));
        for (int i = 0; i < groups.length; i++) {
            String profile = "etri-sensehat-" + groups[i];
            result.add(new Template(profile, "Sense HAT · " + names[i], "device-sensehat-raspi", "etri-dev0003-raspi5",
                profile, "I2C", resources.get(i), "/dev/i2c-1", List.of()));
        }
        return List.copyOf(result);
    }

    @Override public Catalog catalog() {
        var services = list("deviceservice", "services");
        var profiles = list("deviceprofile", "profiles");
        return new Catalog(TEMPLATES.stream().filter(t -> services.stream().anyMatch(s -> t.serviceName().equals(s.path("name").asText()))
            && profiles.stream().anyMatch(p -> compatible(t, p))).toList());
    }

    private boolean compatible(Template t, JsonNode profile) {
        if (!t.profileName().equals(profile.path("name").asText()) || !profile.path("deviceResources").isArray()) return false;
        Set<String> resources = new HashSet<>();
        for (JsonNode r : profile.path("deviceResources")) {
            if (!"R".equals(r.path("properties").path("readWrite").asText())) return false;
            if (t.protocol().equals("SERIAL") && !"Int32".equals(r.path("properties").path("valueType").asText())) return false;
            if (!resources.add(r.path("name").asText())) return false;
        }
        return resources.equals(new HashSet<>(t.resources()));
    }

    @Override public synchronized Result register(Request request) {
        if (request == null) throw invalid();
        identifier(request.name()); identifier(request.physicalDeviceId()); identifier(request.templateId());
        Template template = TEMPLATES.stream().filter(t -> t.id().equals(request.templateId())).findFirst().orElseThrow(EdgeXSensorRegistration::invalid);
        boolean serial = template.protocol().equals("SERIAL");
        if (serial ? request.endpoint() == null || !request.endpoint().matches("/dev/edgeai/[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
            || request.baudRate() == null || !BAUD_RATES.contains(request.baudRate()) : !template.endpoint().equals(request.endpoint()) || request.baudRate() != null) throw invalid();
        if (catalog().templates().stream().noneMatch(t -> t.id().equals(template.id())))
            throw new Failure(CONFLICT, "지원 프로필 또는 수집기가 없습니다. 등록 항목을 다시 조회하세요.");
        Map<String, Object> connection = serial
            ? Map.of("Port", request.endpoint(), "BaudRate", request.baudRate(), "DeviceID", request.physicalDeviceId(),
                "ResourceName", template.resources().getFirst(), "Parser", "arduino-multisensor-v1", "RecoveryStrategy", "on-demand-read")
            : Map.of("Bus", request.endpoint(), "DeviceID", request.physicalDeviceId(), "ResourceGroup", template.id().substring("etri-sensehat-".length()));
        Map<String, Object> device = Map.of("name", request.name(), "serviceName", template.serviceName(), "profileName", template.profileName(),
            "adminState", "UNLOCKED", "operatingState", "DOWN", "protocols", Map.of(serial ? "serial" : "i2c", connection),
            "tags", Map.of("physicalDeviceId", request.physicalDeviceId(), "nodeName", template.nodeName()),
            "labels", List.of("physical-sensor", "edgeai", "web-registration"));
        var devices = list("device", "devices");
        var existing = devices.stream().filter(d -> request.name().equals(d.path("name").asText())).findFirst();
        if (existing.isPresent()) {
            if (!matches(existing.get(), template, request, connection)) throw new Failure(CONFLICT, "같은 이름의 센서가 다른 연결 정보로 등록되어 있습니다.");
            return result(existing.get(), false);
        }
        for (var other : devices) {
            if (!template.serviceName().equals(other.path("serviceName").asText())) continue;
            JsonNode protocol = other.path("protocols").path(serial ? "serial" : "i2c");
            if (!request.endpoint().equals(protocol.path(serial ? "Port" : "Bus").asText())) continue;
            // A physical port may be shared by distinct channels only with identical connection settings.
            if (!request.physicalDeviceId().equals(protocol.path("DeviceID").asText())
                || (serial && (request.baudRate() != protocol.path("BaudRate").asInt()
                    || !"arduino-multisensor-v1".equals(protocol.path("Parser").asText("arduino-multisensor-v1"))
                    || !"on-demand-read".equals(protocol.path("RecoveryStrategy").asText()))))
                throw new Failure(CONFLICT, "이 포트는 다른 장치 ID 또는 통신 설정으로 사용 중입니다.");
            String channel = serial ? "ResourceName" : "ResourceGroup";
            if (connection.get(channel).equals(protocol.path(channel).asText()) || "*".equals(protocol.path(channel).asText()))
                throw new Failure(CONFLICT, "같은 연결의 측정 항목이 이미 등록되어 있습니다. 기존 센서를 확인하세요.");
        }
        JsonNode response = exchange("POST", "/api/v3/device", List.of(Map.of("apiVersion", "v3", "device", device)));
        if (!response.isArray() || response.size() != 1) throw unknown();
        int status = response.get(0).path("statusCode").asInt();
        if (status == 409) throw new Failure(CONFLICT, "센서 등록이 충돌했습니다. 목록을 확인하세요.");
        if (status == 400) throw new Failure(INVALID, "수집기가 등록 정보를 거절했습니다. 연결 설정을 확인하세요.");
        if (status != 201) throw unknown();
        JsonNode saved;
        try { saved = exchange("GET", "/api/v3/device/name/" + request.name(), null).path("device"); }
        catch (Failure e) { throw unknown(); }
        if (!matches(saved, template, request, connection)) throw unknown();
        return result(saved, true);
    }

    private boolean matches(JsonNode device, Template template, Request request, Map<String, Object> connection) {
        if (!request.name().equals(device.path("name").asText()) || !template.profileName().equals(device.path("profileName").asText())
            || !template.serviceName().equals(device.path("serviceName").asText())
            || !request.physicalDeviceId().equals(device.path("tags").path("physicalDeviceId").asText())
            || !template.nodeName().equals(device.path("tags").path("nodeName").asText())) return false;
        JsonNode protocol = device.path("protocols").path(template.protocol().equals("SERIAL") ? "serial" : "i2c");
        for (var entry : connection.entrySet()) {
            String fallback = entry.getKey().equals("Parser") ? "arduino-multisensor-v1" : "";
            if (!entry.getValue().toString().equals(protocol.path(entry.getKey()).asText(fallback))) return false;
        }
        return true;
    }

    private Result result(JsonNode device, boolean created) {
        return new Result(device.path("name").asText(), device.path("serviceName").asText(), device.path("profileName").asText(),
            created, device.path("adminState").asText(), device.path("operatingState").asText());
    }

    private List<JsonNode> list(String path, String field) {
        JsonNode response = exchange("GET", "/api/v3/" + path + "/all?limit=1000", null);
        JsonNode rows = response.path(field);
        if (!rows.isArray() || response.path("totalCount").asInt(-1) != rows.size() || rows.size() > 1000)
            throw new Failure(UNAVAILABLE, "센서 등록 목록 전체를 확인할 수 없습니다.");
        List<JsonNode> result = new ArrayList<>(); rows.forEach(result::add); return result;
    }

    private JsonNode exchange(String method, String path, Object body) {
        boolean write = method.equals("POST");
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(3)).header("Accept", "application/json");
        if (write) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else builder.GET();
        try {
            var response = http.send(builder.build(), info -> new BoundedBody());
            if (write && response.statusCode() == 409) throw new Failure(CONFLICT, "센서 등록이 충돌했습니다. 목록을 확인하세요.");
            if (write && response.statusCode() == 400) throw invalid();
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw write ? unknown() : unavailable();
            JsonNode root = json.readTree(response.body());
            if (root == null || (!write && root.path("statusCode").asInt() != 200)) throw write ? unknown() : unavailable();
            return root;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw write ? unknown() : unavailable(); }
        catch (IOException | tools.jackson.core.JacksonException e) { throw write ? unknown() : unavailable(); }
    }
    private static void identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw invalid(); }
    private static Failure invalid() { return new Failure(INVALID, "센서 이름·종류·연결 경로·장치 ID·통신 속도를 확인하세요."); }
    private static Failure unavailable() { return new Failure(UNAVAILABLE, "EdgeX 등록 정보를 조회할 수 없습니다."); }
    private static Failure unknown() { return new Failure(UNKNOWN, "등록 결과를 확인하지 못했습니다. 센서 목록을 확인하고 같은 이름·설정으로 다시 요청하세요."); }
    @Override public void close() { http.close(); }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var b : buffers) size += b.remaining();
            if (size > 2 * 1024 * 1024) { subscription.cancel(); delegate.onError(new IOException("EdgeX response too large")); }
            else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
