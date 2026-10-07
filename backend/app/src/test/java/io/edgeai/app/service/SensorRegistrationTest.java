package io.edgeai.app.service;

import com.sun.net.httpserver.HttpServer;
import io.edgeai.adapters.metrics.EdgeXSensorRegistration;
import io.edgeai.domain.node.SensorRegistrationSource.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SensorRegistrationTest {
    private HttpServer server;
    private EdgeXSensorRegistration source;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, JsonNode> devices = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private String services, profiles;
    private int writes, writeStatus;
    private boolean badList;
    private static final String PROFILE = "etri-arduino-temperature";
    private Request request(String name, String id, String port) { return new Request(name, PROFILE, port, id, 115200); }
    private Request request() { return request("temperature-002", "arduino-002", "/dev/edgeai/arduino-002"); }
    @BeforeEach void start() throws Exception {
        services = "[{\"name\":\"device-serial-jetson\"},{\"name\":\"device-sensehat-raspi\"}]";
        profiles = "[{\"name\":\"etri-arduino-temperature\",\"deviceResources\":[{\"name\":\"temperature_raw\",\"properties\":{\"valueType\":\"Int32\",\"readWrite\":\"R\"}}]},"
            + "{\"name\":\"etri-sensehat-humidity\",\"deviceResources\":[{\"name\":\"humidity\",\"properties\":{\"valueType\":\"Float64\",\"readWrite\":\"R\"}}]}]";
        writes = 0; writeStatus = 207;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); calls.add(exchange.getRequestMethod() + " " + path);
            int status = 200; String result;
            if (path.equals("/api/v3/deviceservice/all")) result = "{\"statusCode\":200,\"totalCount\":2,\"services\":" + services + "}";
            else if (path.equals("/api/v3/deviceprofile/all")) result = "{\"statusCode\":200,\"totalCount\":2,\"profiles\":" + profiles + "}";
            else if (path.equals("/api/v3/device/all")) result = "{\"statusCode\":200,\"totalCount\":" + (badList ? 1001 : devices.size()) + ",\"devices\":" + json.writeValueAsString(devices.values()) + "}";
            else if (path.startsWith("/api/v3/device/name/")) {
                var device = devices.get(path.substring("/api/v3/device/name/".length()));
                result = "{\"statusCode\":200,\"device\":" + json.writeValueAsString(device) + "}";
            } else if (path.equals("/api/v3/device") && exchange.getRequestMethod().equals("POST")) {
                JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
                var device = body.get(0).path("device"); devices.put(device.path("name").asText(), device); writes++;
                status = writeStatus; result = status == 207 ? "[{\"statusCode\":201,\"id\":\"new-device\"}]" : "private canary upstream error";
            } else { status = 404; result = "{}"; }
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); source = new EdgeXSensorRegistration("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { source.close(); server.stop(0); }
    @Test void catalogOnlyOffersMatchingDeployedProfiles() {
        var catalog = source.catalog(); assertEquals(2, catalog.templates().size());
        assertEquals(List.of("temperature_raw"), catalog.templates().getFirst().resources());
        profiles = profiles.replace("Int32", "Float64");
        assertEquals(1, source.catalog().templates().size());
        assertTrue(calls.stream().allMatch(c -> c.startsWith("GET")));
    }
    @Test void registersSingleChannelThenReplaysSameNameWithoutAnotherWrite() {
        var first = source.register(request()); assertTrue(first.created()); assertEquals("DOWN", first.operatingState());
        var saved = devices.get(first.name());
        assertEquals("device-serial-jetson", saved.path("serviceName").asText());
        assertEquals("temperature_raw", saved.path("protocols").path("serial").path("ResourceName").asText());
        assertEquals("etri-dev0001-jetorn", saved.path("tags").path("nodeName").asText());
        assertFalse(source.register(request()).created()); assertEquals(1, writes);
    }
    @Test void nameAndPhysicalChannelConflictsDoNotWrite() {
        source.register(request());
        for (Request conflict : List.of(request("temperature-002", "different-id", "/dev/edgeai/other"),
            request("another-name", "arduino-002", "/dev/edgeai/arduino-002"),
            request("another-name", "different-id", "/dev/edgeai/arduino-002")))
            assertEquals(Failure.Kind.CONFLICT, assertThrows(Failure.class, () -> source.register(conflict)).kind());
        assertEquals(1, writes);
    }
    @Test void invalidInputCannotReachNetwork() {
        for (Request invalid : List.of(request("../escape", "board", "/dev/edgeai/board"),
            request("sensor", "board", "/dev/edgeai/../passwd"), request("sensor", "board", "/dev/ttyUSB0"),
            new Request("sensor", "unknown", "/dev/edgeai/board", "board", 115200),
            new Request("sensor", PROFILE, "/dev/edgeai/board", "board", null),
            new Request("sensor", PROFILE, "/dev/edgeai/board", "board", 12345)))
            assertEquals(Failure.Kind.INVALID, assertThrows(Failure.class, () -> source.register(invalid)).kind());
        assertTrue(calls.isEmpty());
    }
    @Test void unknownWriteOutcomeKeepsIdentityAndDoesNotRetry() {
        writeStatus = 500;
        var failure = assertThrows(Failure.class, () -> source.register(request()));
        assertEquals(Failure.Kind.UNKNOWN, failure.kind()); assertFalse(failure.getMessage().contains("canary")); assertEquals(1, writes);
        // A response can fail after metadata has committed. An explicit same-input retry reads it back.
        assertFalse(source.register(request()).created()); assertEquals(1, writes);
    }
    @Test void incompleteInventoryCannotBypassDuplicateCheck() {
        badList = true;
        assertEquals(Failure.Kind.UNAVAILABLE, assertThrows(Failure.class, () -> source.register(request())).kind());
        assertEquals(0, writes);
    }
    @Test void senseHatHasFixedBusAndCannotAliasSameHardwareUsingAnotherId() {
        Request sense = new Request("humidity-01", "etri-sensehat-humidity", "/dev/i2c-1", "sensehat-001", null);
        assertTrue(source.register(sense).created());
        assertEquals("humidity", devices.get(sense.name()).path("protocols").path("i2c").path("ResourceGroup").asText());
        assertEquals(Failure.Kind.CONFLICT, assertThrows(Failure.class, () -> source.register(new Request("humidity-02", sense.templateId(), sense.endpoint(), "sensehat-002", null))).kind());
        assertEquals(Failure.Kind.INVALID, assertThrows(Failure.class, () -> source.register(new Request("humidity-02", sense.templateId(), "/dev/i2c-2", "sensehat-002", null))).kind());
        assertEquals(1, writes);
    }
    @Test void existingYamlRegistrationWithoutExplicitParserIsRecognized() {
        source.register(request());
        var saved = (tools.jackson.databind.node.ObjectNode) devices.get(request().name());
        ((tools.jackson.databind.node.ObjectNode) saved.path("protocols").path("serial")).remove("Parser");
        assertFalse(source.register(request()).created()); assertEquals(1, writes);
    }
}
