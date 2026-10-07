package io.edgeai.app.service;

import io.edgeai.adapters.metrics.PrometheusNodeMetrics;
import io.edgeai.adapters.metrics.PrometheusQueries;
import io.edgeai.domain.node.NodeMetricsSource;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeMetricsTest {
    private static final Instant NOW = Instant.parse("2026-10-06T06:00:00Z");
    private static final JsonMapper JSON = new JsonMapper();
    private final List<Object> rows = new ArrayList<>();

    private void row(String name, String tag, Map<String, String> labels, Object value) {
        var metric = new HashMap<>(labels);
        if (!name.isEmpty()) metric.put("__name__", name);
        if (!tag.isEmpty()) metric.put("edgeai_metric", tag);
        rows.add(Map.of("metric", metric, "value", List.of(NOW.getEpochSecond(), value.toString())));
    }
    private Map<String, String> target(String job, String instance) { return Map.of("job", job, "instance", instance); }
    private void node(String name) { row("kube_node_info", "", Map.of("node", name), 1); }
    private void up(Map<String, String> labels, int value) {
        row("up", "", labels, value); row("", "up_time", labels, NOW.minusSeconds(15).getEpochSecond());
    }
    private void measurement(String id, Map<String, String> labels, Object value, int age) {
        row("", id, labels, value); row("", id + "_time", labels, NOW.minusSeconds(age).getEpochSecond());
    }
    private NodeMetricsSource.Snapshot decode() {
        try (var adapter = new PrometheusNodeMetrics("http://localhost:9090")) {
            return adapter.decode(JSON.writeValueAsBytes(Map.of("status", "success", "data", Map.of("resultType", "vector", "result", rows))), NOW);
        }
    }
    @Test void mapsNodeExporterHostnameAndPreservesRealZero() {
        node("node-a");
        var labels = target("node-exporter", "192.0.2.1:9100");
        row("node_uname_info", "", Map.of("job", "node-exporter", "instance", "192.0.2.1:9100", "nodename", "NODE-A"), 1);
        up(labels, 1); measurement("cpu", labels, 0, 30);
        var snapshot = decode();
        assertEquals("node-a", snapshot.items().getFirst().nodeName());
        var cpu = snapshot.items().getFirst().measurements().getFirst();
        assertEquals(0, cpu.value()); assertTrue(cpu.fresh());
        assertEquals(NOW.minusSeconds(30), cpu.observedAt());
    }
    @Test void mapsDcgmPodToNodeWithoutTreatingHostnameAsNode() {
        node("gpu-host");
        row("kube_pod_info", "", Map.of("namespace", "kube-system", "pod", "dcgm-abc", "node", "gpu-host"), 1);
        var labels = Map.of("job", "dcgm-exporter", "instance", "192.0.2.2:9400", "namespace", "kube-system", "pod", "dcgm-abc", "Hostname", "dcgm-abc", "UUID", "GPU-123");
        up(target("dcgm-exporter", "192.0.2.2:9400"), 1); measurement("dcgm_usage", labels, 37, 15);
        var gpu = decode().items().getFirst();
        assertEquals("gpu-host", gpu.nodeName());
        assertEquals("GPU-123", gpu.measurements().getFirst().device());
        assertTrue(gpu.measurements().getFirst().fresh());
    }
    @Test void mapsCpuThermalSensorToHostWithoutInventingAnAccelerator() {
        node("raspi-node");
        var labels = target("node-exporter", "192.0.2.3:9100");
        row("node_uname_info", "", Map.of("job", "node-exporter", "instance", "192.0.2.3:9100", "nodename", "raspi-node"), 1);
        up(labels, 1); measurement("cpu_temp", labels, 46.3, 30);
        var temperature = decode().items().getFirst().measurements().getFirst();
        assertEquals("CPU_TEMPERATURE", temperature.key());
        assertEquals("CELSIUS", temperature.unit());
        assertEquals("", temperature.device());
        assertEquals(46.3, temperature.value());
        assertTrue(temperature.fresh());
    }
    @Test void neverInventsMappingForUnknownOrConflictingNodes() {
        node("node-a"); node("node-b");
        row("kube_pod_info", "", Map.of("namespace", "kube-system", "pod", "exporter", "node", "node-b"), 1);
        measurement("dcgm_usage", Map.of("node", "node-a", "namespace", "kube-system", "pod", "exporter"), 10, 0);
        measurement("cpu", target("node-exporter", "unknown:9100"), 10, 0);
        var snapshot = decode();
        assertTrue(snapshot.items().isEmpty()); assertEquals(2, snapshot.unmappedSeries());
    }
    @Test void staleOrDownExporterCannotLookCurrent() {
        node("node-a");
        var labels = Map.of("node", "node-a", "job", "node-exporter", "instance", "a:9100");
        up(labels, 1); measurement("cpu", labels, 30, 91);
        assertFalse(decode().items().getFirst().measurements().getFirst().fresh());
        rows.clear(); node("node-a"); up(labels, 0); measurement("cpu", labels, 30, 0);
        assertFalse(decode().items().getFirst().measurements().getFirst().fresh());
    }
    @Test void gpuCollectorHealthIsRequiredEvenWhenScrapeIsUp() {
        node("node-a");
        var labels = Map.of("node", "node-a", "job", "jetson-gpu-exporter", "instance", "a:9100");
        up(labels, 1); measurement("jetson_usage", labels, 0, 15);
        assertFalse(decode().items().getFirst().measurements().getFirst().fresh());
        row("", "health_jetson_gpu_collector_success", labels, 1);
        row("", "jetson_gpu_collector_success_time", labels, NOW.minusSeconds(15).getEpochSecond());
        assertTrue(decode().items().getFirst().measurements().getFirst().fresh());
    }
    @Test void invalidValuesAndSentinelsAreNotTurnedIntoZero() {
        node("node-a"); var labels = Map.of("node", "node-a");
        measurement("cpu", labels, "NaN", 0);
        measurement("memory", labels, "Infinity", 0);
        measurement("dcgm_temp", labels, "9223372036854775794", 0);
        measurement("dcgm_usage", labels, 101, 0);
        assertTrue(decode().items().isEmpty());
    }
    @Test void independentAcceleratorsRemainSeparateAndConflictsAreOmitted() {
        node("node-a");
        measurement("dcgm_usage", Map.of("node", "node-a", "UUID", "GPU-1", "instance", "a"), 12, 0);
        measurement("dcgm_usage", Map.of("node", "node-a", "UUID", "GPU-2", "instance", "a"), 23, 0);
        assertEquals(2, decode().items().getFirst().measurements().size());
        measurement("dcgm_usage", Map.of("node", "node-a", "UUID", "GPU-1", "instance", "b"), 42, 0);
        var values = decode().items().getFirst().measurements();
        assertEquals(1, values.size()); assertEquals("GPU-2", values.getFirst().device());
    }
    @Test void successfulEmptyAndInvalidResponseAreDifferent() {
        assertEquals("AVAILABLE", decode().status()); assertTrue(decode().items().isEmpty());
        try (var adapter = new PrometheusNodeMetrics("http://localhost:9090")) {
            assertThrows(IllegalStateException.class, () -> adapter.decode("{\"status\":\"error\"}".getBytes(), NOW));
            assertThrows(IllegalStateException.class, () -> adapter.decode(JSON.writeValueAsBytes(Map.of("status", "success", "warnings", List.of("partial"), "data", Map.of("resultType", "vector", "result", List.of()))), NOW));
        }
    }
    @Test void queryKeepsCollectorHealthDistinctFromUpAndUsesSourceTimestamps() {
        String query = PrometheusQueries.query();
        assertTrue(query.contains("health_jetson_gpu_collector_success"));
        assertTrue(query.contains("health_spark_gpu_collector_success"));
        assertTrue(query.contains("timestamp(node_cpu_seconds_total"));
        assertTrue(query.contains("rate(hairp_npu_busy_seconds_total[1m])"));
    }
    private void readyCondition(String status, int age, int exporterUp) {
        node("node-a");
        var labels = Map.of("node", "node-a", "job", "kube-state-metrics", "instance", "ksm:8080", "status", status);
        up(target("kube-state-metrics", "ksm:8080"), exporterUp);
        measurement("node_ready", labels, 1, age);
    }
    @Test void kubernetesReadyConditionSuppliesStatusWithoutUsageOrInventory() {
        for (var state : Map.of("true", "READY", "false", "NOT_READY", "unknown", "UNKNOWN").entrySet()) {
            rows.clear(); readyCondition(state.getKey(), 15, 1);
            var node = decode().items().getFirst();
            assertTrue(node.measurements().isEmpty());
            assertEquals(state.getValue(), node.readiness().status());
            assertTrue(node.readiness().fresh());
            assertEquals(NOW.minusSeconds(15), node.readiness().observedAt());
        }
    }
    @Test void staleOrDownKubernetesExporterCannotSupplyFreshReadiness() {
        readyCondition("true", 91, 1);
        assertFalse(decode().items().getFirst().readiness().fresh());
        rows.clear(); readyCondition("true", 15, 0);
        assertFalse(decode().items().getFirst().readiness().fresh());
    }
    @Test void inactiveConditionsAreIgnoredAndConflictingActiveConditionsAreOmitted() {
        readyCondition("true", 15, 1);
        var other = Map.of("node", "node-a", "job", "kube-state-metrics", "instance", "ksm:8080", "status", "false");
        measurement("node_ready", other, 0, 15);
        assertEquals("READY", decode().items().getFirst().readiness().status());
        measurement("node_ready", other, 1, 15);
        assertNull(decode().items().getFirst().readiness());
    }
    @Test void cacheCoalescesReadsAndDoesNotRetainSuccessfulValuesAfterFailure() {
        @SuppressWarnings("unchecked") ObjectProvider<NodeMetricsSource> provider = mock(ObjectProvider.class);
        var source = mock(NodeMetricsSource.class); when(provider.getIfAvailable()).thenReturn(source);
        Clock clock = mock(Clock.class); when(clock.instant()).thenReturn(NOW);
        var service = new NodeMetricsService(provider, clock);
        var available = new NodeMetricsSource.Snapshot("AVAILABLE", NOW, 90, List.of(), 0);
        when(source.snapshot(NOW)).thenReturn(available);
        assertSame(available, service.snapshot()); assertSame(available, service.snapshot());
        verify(source, times(1)).snapshot(NOW);
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        when(source.snapshot(NOW.plusSeconds(5))).thenThrow(new IllegalStateException("internal endpoint details"));
        assertEquals("UNAVAILABLE", service.snapshot().status()); assertTrue(service.snapshot().items().isEmpty());
        verify(source, times(1)).snapshot(NOW.plusSeconds(5));
    }
    @Test void disabledSourceDoesNotContactPrometheus() {
        @SuppressWarnings("unchecked") ObjectProvider<NodeMetricsSource> provider = mock(ObjectProvider.class);
        assertEquals("DISABLED", new NodeMetricsService(provider, Clock.fixed(NOW, ZoneOffset.UTC)).snapshot().status());
    }
    private void hardware(String device, String kind, String vendor, String model, int age, int collector) {
        var labels = Map.of("node", "node-a", "job", "node-hardware-inventory", "instance", "inventory:9403",
            "device", device, "kind", kind, "vendor", vendor, "model", model);
        measurement("accelerator", labels, 1, age);
        var target = target("node-hardware-inventory", "inventory:9403");
        if (rows.stream().noneMatch(r -> r.toString().contains("health_node_hardware_inventory_success"))) {
            up(target, 1);
            row("", "health_node_hardware_inventory_success", target, collector);
            row("", "node_hardware_inventory_success_time", target, NOW.minusSeconds(15).getEpochSecond());
        }
    }
    @Test void installedHailoAppearsWithoutUtilizationAndRequiresFreshHealthyInventory() {
        for (int age : List.of(15, 91)) for (int collector : List.of(0, 1)) {
            rows.clear(); node("node-a"); hardware("pci:0001:01:00.0", "NPU", "Hailo", "Hailo-8", age, collector);
            var node = decode().items().getFirst();
            assertTrue(node.measurements().isEmpty());
            assertEquals("Hailo-8", node.accelerators().getFirst().model());
            assertEquals(age == 15 && collector == 1, node.accelerators().getFirst().fresh());
        }
    }
    @Test void pciIdentityJoinsNvidiaUsageWithoutMergingIntegratedGpu() {
        node("node-a");
        hardware("pci:0000:02:00.0", "GPU", "NVIDIA", "NVIDIA GPU", 15, 1);
        hardware("pci:0000:00:02.0", "GPU", "Intel", "Intel GPU", 15, 1);
        measurement("dcgm_usage", Map.of("node", "node-a", "UUID", "GPU-123", "pci_bus_id", "00000000:02:00.0"), 20, 15);
        var node = decode().items().getFirst();
        assertEquals(2, node.accelerators().size());
        assertEquals("pci:0000:02:00.0", node.measurements().getFirst().device());
    }
    @Test void vendorFallbackRequiresUniqueHardwareAndUniqueMeasuredDevice() {
        node("node-a"); hardware("pci:0000:01:00.0", "NPU", "Mobilint", "ARIES", 15, 1);
        measurement("mobilint_usage", Map.of("node", "node-a", "device", "aries0"), 0, 15);
        assertEquals("pci:0000:01:00.0", decode().items().getFirst().measurements().getFirst().device());
        measurement("mobilint_usage", Map.of("node", "node-a", "device", "aries1"), 0, 15);
        assertEquals(List.of("aries0", "aries1"), decode().items().getFirst().measurements().stream().map(NodeMetricsSource.Measurement::device).toList());
    }
}
