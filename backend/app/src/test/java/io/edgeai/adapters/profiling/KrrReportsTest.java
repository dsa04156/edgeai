package io.edgeai.adapters.profiling;

import io.edgeai.domain.resourceprofile.KrrRecommendation.Status;
import io.edgeai.domain.resourceprofile.WorkloadTarget;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Fixture: trimmed real report of `krr simple -f json` v1.31.1 against the live read-only Prometheus (edgeai, 24h). */
class KrrReportsTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final Instant GENERATED = Instant.parse("2026-10-29T15:10:00Z");
    private static String fixture() throws Exception {
        try (var input = KrrReportsTest.class.getResourceAsStream("/resource-profiling/krr-v1.31.1-edgeai-report.json")) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static WorkloadTarget target(String kind, String name, String container) {
        return new WorkloadTarget("edgeai", WorkloadTarget.Kind.valueOf(kind), name, container, null, null);
    }
    private static Map<String, Object> bridge() { return Map.of("version", "v1.31.1", "requestedHistoryHours", 24); }

    @Test void exactTargetConvertsCoresToMillicoresAndKeepsUnsetCpuLimitAbsent() throws Exception {
        var result = KrrReports.parse(JSON.readTree(fixture()), target("Deployment", "edgeai-api", "api"), GENERATED, bridge());
        assertThat(result.status()).isEqualTo(Status.AVAILABLE);
        assertThat(result.cpuRequestMillicores()).isEqualTo(393L);   // KRR value 0.393 cores
        assertThat(result.cpuLimitMillicores()).isNull();             // simple strategy: CPU limit unset, not 0
        assertThat(result.memoryRequestBytes()).isEqualTo(530579456L);
        assertThat(result.memoryLimitBytes()).isEqualTo(530579456L);
        assertThat(result.historyHours()).isEqualTo(24);
        assertThat(result.windowEnd()).isEqualTo(GENERATED);
        assertThat(result.windowStart()).isEqualTo(GENERATED.minus(Duration.ofHours(24)));
        assertThat(result.podCount()).isEqualTo(1);
        assertThat(result.provenance()).containsEntry("strategy", "simple").containsEntry("version", "v1.31.1").containsEntry("cpuMinMillicores", 10L);
        assertThat(((Map<?, ?>) result.provenance().get("strategySettings")).containsKey("cpu_percentile")).isTrue();
    }

    @Test void containerIdentityIsExactWithinAMultiContainerWorkload() throws Exception {
        var ollama = KrrReports.parse(JSON.readTree(fixture()), target("Deployment", "edgeai-hawkeye-safety", "ollama"), GENERATED, bridge());
        var adapter = KrrReports.parse(JSON.readTree(fixture()), target("Deployment", "edgeai-hawkeye-safety", "adapter"), GENERATED, bridge());
        assertThat(ollama.memoryRequestBytes()).isEqualTo(4775215104L);
        assertThat(adapter.memoryRequestBytes()).isEqualTo(104857600L);
        assertThat(KrrReports.parse(JSON.readTree(fixture()), target("Deployment", "edgeai-hawkeye-safety", "missing"), GENERATED, bridge()).status())
            .isEqualTo(Status.NOT_FOUND);
        assertThat(KrrReports.parse(JSON.readTree(fixture()), target("StatefulSet", "edgeai-api", "api"), GENERATED, bridge()).status())
            .as("same name but different kind is a different workload").isEqualTo(Status.NOT_FOUND);
    }

    @Test void questionMarkValuesAreNoDataWithKrrReasonNeverZero() throws Exception {
        var result = KrrReports.parse(JSON.readTree(fixture()), target("StatefulSet", "edgeai-postgres", "postgres"), GENERATED, bridge());
        assertThat(result.status()).isEqualTo(Status.NO_DATA);
        assertThat(result.cpuRequestMillicores()).isNull();
        assertThat(result.memoryRequestBytes()).isNull();
        assertThat(result.cpuInfo()).isEqualTo("No data");
        assertThat(result.warnings()).contains("NoPrometheusCPUMetrics", "NoPrometheusMemoryMetrics");
        assertThat(result.reason()).contains("No data");
    }

    @Test void duplicatedOrMalformedScansAreReportedInsteadOfGuessed() throws Exception {
        var root = (ObjectNode) JSON.readTree(fixture());
        var scans = (tools.jackson.databind.node.ArrayNode) root.get("scans");
        scans.add(scans.get(0).deepCopy());
        assertThat(KrrReports.parse(root, target("Deployment", "edgeai-api", "api"), GENERATED, bridge()).status()).isEqualTo(Status.AMBIGUOUS);

        var invalid = (ObjectNode) JSON.readTree(fixture());
        ((ObjectNode) invalid.get("scans").get(0).get("recommended").get("requests").get("cpu")).put("value", "lots");
        assertThat(KrrReports.parse(invalid, target("Deployment", "edgeai-api", "api"), GENERATED, bridge()).status()).isEqualTo(Status.INVALID_REPORT);
        var negative = (ObjectNode) JSON.readTree(fixture());
        ((ObjectNode) negative.get("scans").get(0).get("recommended").get("requests").get("memory")).put("value", -5);
        assertThat(KrrReports.parse(negative, target("Deployment", "edgeai-api", "api"), GENERATED, bridge()).status()).isEqualTo(Status.INVALID_REPORT);
        assertThat(KrrReports.parse(JSON.readTree("{\"items\":[]}"), target("Deployment", "edgeai-api", "api"), GENERATED, bridge()).status())
            .isEqualTo(Status.INVALID_REPORT);
    }

    @Test void bridgeOutcomesMapToUnavailableWithoutAFabricatedRecommendation() throws Exception {
        var client = new KrrBridgeClient("http://127.0.0.1:1", Duration.ofSeconds(5));
        var api = target("Deployment", "edgeai-api", "api");
        String completed = "{\"status\":\"COMPLETED\",\"krrVersion\":\"v1.31.1\",\"bridgeVersion\":\"1\",\"startedAt\":\"2026-10-29T15:09:30Z\","
            + "\"finishedAt\":\"2026-10-29T15:10:00Z\",\"reportSha256\":\"abc\",\"arguments\":[\"simple\"],\"exitCode\":0,\"report\":" + fixture() + "}";
        var ok = client.decode(completed.getBytes(StandardCharsets.UTF_8), api, 24);
        assertThat(ok.status()).isEqualTo(Status.AVAILABLE);
        assertThat(ok.provenance()).containsEntry("reportSha256", "abc").containsEntry("finishedAt", "2026-10-29T15:10:00Z");
        for (String body : List.of("{\"status\":\"FAILED\",\"exitCode\":1,\"report\":null}", "{\"status\":\"TIMEOUT\",\"report\":null}", "not json")) {
            var failed = client.decode(body.getBytes(StandardCharsets.UTF_8), api, 24);
            assertThat(failed.status()).isIn(Status.UNAVAILABLE, Status.INVALID_REPORT);
            assertThat(failed.cpuRequestMillicores()).isNull();
            assertThat(failed.memoryRequestBytes()).isNull();
        }
        // An unreachable bridge is an explicit UNAVAILABLE status.
        assertThat(client.recommend(api, 24).status()).isEqualTo(Status.UNAVAILABLE);
        client.close();
        assertThatThrownBy(() -> new KrrBridgeClient("http://user:pw@bridge:8080", Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KrrBridgeClient("http://bridge:8080/run?cmd=rm", Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
    }
}
