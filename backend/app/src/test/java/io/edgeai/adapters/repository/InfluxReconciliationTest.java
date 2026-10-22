package io.edgeai.adapters.repository;

import com.sun.net.httpserver.*;
import io.edgeai.domain.repository.SensorTelemetryRepository.Observation;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InfluxReconciliationTest {
    @TempDir Path temporary;
    final JsonMapper json = new JsonMapper();
    final UUID uid = UUID.randomUUID();
    final Instant at = Instant.parse("2026-10-16T10:00:00Z");
    final List<String> writes = Collections.synchronizedList(new ArrayList<>());
    final List<String> deletes = Collections.synchronizedList(new ArrayList<>());
    HttpServer server;
    volatile Throwable failure;
    volatile int queryStatus = 200;
    InfluxTelemetryRepository repository;
    @BeforeEach void start() throws Exception {
        Path token = temporary.resolve("token"); Files.writeString(token, "owned-test-credential-never-printed");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (path.equals("/api/v2/query") && queryStatus != 200) {
                    exchange.sendResponseHeaders(queryStatus, -1);
                } else if (path.equals("/api/v2/query")) {
                    String document = json.writeValueAsString(Map.of("quality", "UNVERIFIED", "value", 23.5, "type", "float64", "observedAt", at.toString()));
                    String csv = "_time,_value\n" + at + ",\"" + document.replace("\"", "\"\"") + "\"\n";
                    byte[] data = csv.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data);
                } else if (path.equals("/api/v2/write")) {
                    writes.add(body); exchange.sendResponseHeaders(204, -1);
                } else {
                    deletes.add(path); exchange.sendResponseHeaders(503, -1);
                }
            } catch (Throwable error) { failure = error; } finally { exchange.close(); }
        });
        server.start();
        repository = new InfluxTelemetryRepository("http://127.0.0.1:" + server.getAddress().getPort(), "owned-test", token.toString(), "sensor-originals", "vd-results-", 30, false);
    }
    @AfterEach void stop() { repository.close(); server.stop(0); assertThat(failure).isNull(); }
    String qualified() {
        var document = new TreeMap<String,Object>(Map.of("quality", "QUALIFIED", "value", 23.5, "type", "float64", "observedAt", at.toString(), "sequence", 1, "sourceGeneration", UUID.randomUUID().toString(), "sessionId", "abc", "configurationSha256", "sha256:owned"));
        document.putAll(Map.of("deviceUid", uid.toString(), "deviceId", "sensor", "namespace", "edgeai-devices", "property", "temperature", "unit", "C"));
        return json.writeValueAsString(document);
    }
    @Test void qualifiedStoreIsOneWriteAndHidingTheLegacyDuplicateNeverUsesTheDeleteEndpoint() {
        var observation = new Observation(uid, "temperature", at, qualified());
        assertThatCode(() -> repository.append(observation)).doesNotThrowAnyException();
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0)).contains("series=").contains("value_numeric=23.5").contains("archive_visible=true");
        assertThat(repository.hideLegacyDuplicate(observation)).isTrue();
        assertThat(deletes).isEmpty();
        assertThat(writes).hasSize(2);
        assertThat(writes.get(1)).contains("series=legacy:legacy:0").contains("document=\"\",series_document=\"\",archive_visible=false").endsWith(" " + at.getEpochSecond() + "000000000");
    }
    @Test void failingReconciliationQueryDoesNotAffectTheQualifiedStore() {
        queryStatus = 503;
        var observation = new Observation(uid, "temperature", at, qualified());
        assertThatCode(() -> repository.append(observation)).doesNotThrowAnyException();
        assertThatThrownBy(() -> repository.hideLegacyDuplicate(observation)).isInstanceOf(RuntimeException.class);
        assertThat(writes).hasSize(1);
    }
}
