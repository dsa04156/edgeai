package io.edgeai.adapters.profiling;

import com.sun.net.httpserver.HttpServer;
import io.edgeai.domain.resourceprofile.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.tuple;

/** Prometheus HTTP API response shapes served by a local fake; records every PromQL expression the adapter sends. */
class PrometheusWorkloadMetricsTest {
    private static final Instant END = Instant.parse("2026-10-29T15:00:00Z");
    private static final Instant START = END.minus(Duration.ofHours(1));
    private HttpServer server;
    private final List<String> queries = Collections.synchronizedList(new ArrayList<>());

    private PrometheusWorkloadMetrics serve(Function<String, String> responder) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String query = "";
            for (String pair : form.split("&")) if (pair.startsWith("query=")) query = URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
            queries.add(query);
            byte[] body = responder.apply(query).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new PrometheusWorkloadMetrics("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }

    private static String vector(String... metrics) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + String.join(",", metrics) + "]}}";
    }
    private static String sample(String labels) { return "{\"metric\":{" + labels + "},\"value\":[1793286000,\"1\"]}"; }
    private static String matrix(String labels, double from, double step, double... values) {
        var points = new StringJoiner(",");
        for (int i = 0; i < values.length; i++) points.add("[" + (from + i * step) + ",\"" + values[i] + "\"]");
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{" + labels + "},\"values\":[" + points + "]}]}}";
    }
    private static final String EMPTY_MATRIX = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";
    private static double[] ramp(int count, double from, double step) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) values[i] = from + i * step;
        return values;
    }

    private String deployment(String query, String cpu, String memory) {
        if (query.contains("kube_replicaset_owner")) return vector(sample("\"replicaset\":\"detector-7d9\",\"owner_name\":\"detector\""));
        if (query.contains("kube_pod_owner")) return vector(sample("\"pod\":\"detector-7d9-abc\",\"owner_kind\":\"ReplicaSet\",\"owner_name\":\"detector-7d9\""));
        if (query.contains("kube_pod_info")) return vector(sample("\"pod\":\"detector-7d9-abc\",\"uid\":\"0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b\",\"node\":\"gpu-node-1\""));
        if (query.contains("container_cpu_usage_seconds_total")) return cpu;
        if (query.contains("container_memory_working_set_bytes")) return memory;
        throw new AssertionError("Unexpected query " + query);
    }

    @Test void exactWorkloadContainerUsageIsSummarisedFromFixedQueries() throws Exception {
        double endEpoch = END.getEpochSecond();
        var metrics = serve(q -> deployment(q,
            matrix("\"pod\":\"detector-7d9-abc\"", endEpoch - 3585, 15, ramp(240, 10, 1)),
            matrix("\"pod\":\"detector-7d9-abc\"", endEpoch - 3585, 15, ramp(240, 1.0e8, 1.0e6))));
        var target = new WorkloadTarget("edgeai", WorkloadTarget.Kind.Deployment, "detector", "model", null, null);
        var result = metrics.measure(target, START, END);
        assertThat(result.stepSeconds()).isEqualTo(15);
        assertThat(result.pods()).extracting(ObservedPod::uid).containsExactly("0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b");
        assertThat(result.cpu().status()).isEqualTo(ObservationStatus.AVAILABLE);
        assertThat(result.cpu().unit()).isEqualTo("millicores");
        assertThat(result.cpu().min()).isEqualTo(10.0);
        assertThat(result.cpu().max()).isEqualTo(249.0);
        assertThat(result.cpu().p95()).isEqualTo(237.0);       // nearest rank: ceil(0.95*240)=228th value
        assertThat(result.cpu().samples()).isEqualTo(240);
        assertThat(result.memory().max()).isEqualTo(1.0e8 + 239 * 1.0e6);
        var cpuQuery = queries.stream().filter(q -> q.contains("container_cpu_usage_seconds_total")).findFirst().orElseThrow();
        assertThat(cpuQuery).isEqualTo("sum by(pod) (max by(pod,id) (rate(container_cpu_usage_seconds_total{namespace=\"edgeai\",pod=~\"detector-7d9-abc\",container=\"model\"}[120s]))) * 1000");
        var memoryQuery = queries.stream().filter(q -> q.contains("container_memory_working_set_bytes")).findFirst().orElseThrow();
        assertThat(memoryQuery).as("duplicate scrapes of one cgroup collapse before the Pod sum")
            .isEqualTo("sum by(pod) (max by(pod,id) (container_memory_working_set_bytes{namespace=\"edgeai\",pod=~\"detector-7d9-abc\",container=\"model\"}))");
        assertThat(result.perPod()).extracting(PodUsage::pod).containsExactly("detector-7d9-abc");
        assertThat(queries).anyMatch(q -> q.contains("kube_replicaset_owner{namespace=\"edgeai\",owner_kind=\"Deployment\",owner_name=\"detector\"}"));
    }

    @Test void missingSparseAndStaleSeriesAreDistinctStatusesWithoutValues() throws Exception {
        double endEpoch = END.getEpochSecond();
        var target = new WorkloadTarget("edgeai", WorkloadTarget.Kind.Deployment, "detector", "model", null, null);
        var none = serve(q -> deployment(q, EMPTY_MATRIX, matrix("\"pod\":\"detector-7d9-abc\"", endEpoch - 60, 15, 1, 2, 3)));
        var result = none.measure(target, START, END);
        assertThat(result.cpu().status()).isEqualTo(ObservationStatus.NO_DATA);
        assertThat(result.cpu().max()).isNull();
        assertThat(result.memory().status()).isEqualTo(ObservationStatus.INSUFFICIENT);
        assertThat(result.memory().max()).isNull();
        assertThat(result.memory().samples()).isEqualTo(3);
        stop();

        var stale = serve(q -> deployment(q, matrix("\"pod\":\"detector-7d9-abc\"", endEpoch - 3585, 15, ramp(100, 5, 0)), EMPTY_MATRIX));
        var old = stale.measure(target, START, END);
        assertThat(old.cpu().status()).isEqualTo(ObservationStatus.STALE);
        assertThat(old.cpu().max()).isEqualTo(5.0);
        assertThat(old.cpu().lastSample()).isBefore(END.minusSeconds(300));
    }

    @Test void unknownPodUidOrMissingWorkloadIsNotFoundAndNeverMeasured() throws Exception {
        var metrics = serve(q -> deployment(q, EMPTY_MATRIX, EMPTY_MATRIX));
        var wrongUid = new WorkloadTarget("edgeai", WorkloadTarget.Kind.Deployment, "detector", "model", "detector-7d9-abc", UUID.randomUUID());
        var result = metrics.measure(wrongUid, START, END);
        assertThat(result.cpu().status()).isEqualTo(ObservationStatus.NOT_FOUND);
        assertThat(result.memory().status()).isEqualTo(ObservationStatus.NOT_FOUND);
        assertThat(queries).noneMatch(q -> q.contains("container_cpu_usage_seconds_total"));
        stop(); queries.clear();
        var empty = serve(q -> q.contains("matrix") ? EMPTY_MATRIX : vector());
        assertThat(empty.measure(new WorkloadTarget("edgeai", WorkloadTarget.Kind.StatefulSet, "db", "postgres", null, null), START, END).cpu().status())
            .isEqualTo(ObservationStatus.NOT_FOUND);
    }

    @Test void dcgmDeviceTelemetryIsNodeLevelUnlessTheExporterNamesOnlyTargetPods() throws Exception {
        double endEpoch = END.getEpochSecond();
        String device = "\"pci_bus_id\":\"00000000:02:00.0\",\"UUID\":\"GPU-1\",\"modelName\":\"NVIDIA GeForce RTX 5080\",\"node\":\"gpu-node-1\"";
        var metrics = serve(q -> q.contains("DCGM_FI_DEV_GPU_UTIL") ? matrix(device, endEpoch - 3585, 15, ramp(240, 0, 0.4))
            : q.contains("DCGM_FI_DEV_FB_FREE") ? matrix(device, endEpoch - 3585, 15, ramp(240, 1.7e10, 0))
            : matrix(device, endEpoch - 3585, 15, ramp(240, 1.0e9, 1.0e6)));
        var pods = List.of(new ObservedPod("detector-7d9-abc", "uid", "gpu-node-1"));
        var observed = metrics.device(new DeviceRef("gpu-node-1", AcceleratorRequirement.Kind.GPU, "pci:0000:02:00.0"), "edgeai", pods, START, END);
        assertThat(observed.status()).isEqualTo(ObservationStatus.AVAILABLE);
        assertThat(observed.attribution()).isEqualTo(DeviceObservation.Attribution.NODE_DEVICE_UNKNOWN_OWNER);
        assertThat(observed.perWorkload()).isFalse();
        assertThat(observed.model()).isEqualTo("NVIDIA GeForce RTX 5080");
        assertThat(observed.utilization().unit()).isEqualTo("percent");
        assertThat(observed.memoryTotalBytes()).isEqualTo(1.7e10);
        assertThat(queries).allMatch(q -> q.contains("kube_pod_info{node=\"gpu-node-1\"}"));
        assertThat(metrics.device(new DeviceRef("gpu-node-1", AcceleratorRequirement.Kind.GPU, "pci:0000:03:00.0"), "edgeai", pods, START, END).status())
            .isEqualTo(ObservationStatus.NO_DATA);
        stop();

        String shared = device + ",\"exported_namespace\":\"edgeai\",\"exported_pod\":\"other-pod\"";
        var sharedMetrics = serve(q -> matrix(shared, endEpoch - 3585, 15, ramp(240, 1, 0)));
        assertThat(sharedMetrics.device(new DeviceRef("gpu-node-1", AcceleratorRequirement.Kind.GPU, "pci:0000:02:00.0"), "edgeai", pods, START, END).attribution())
            .isEqualTo(DeviceObservation.Attribution.NODE_DEVICE_SHARED);
        stop();
        String exclusive = device + ",\"exported_namespace\":\"edgeai\",\"exported_pod\":\"detector-7d9-abc\"";
        var exclusiveMetrics = serve(q -> matrix(exclusive, endEpoch - 3585, 15, ramp(240, 1, 0)));
        var owned = exclusiveMetrics.device(new DeviceRef("gpu-node-1", AcceleratorRequirement.Kind.GPU, "pci:0000:02:00.0"), "edgeai", pods, START, END);
        // Labels associate the device with the target Pod; they do not prove exclusive allocation or per-model usage.
        assertThat(owned.attribution()).isEqualTo(DeviceObservation.Attribution.TARGET_ASSOCIATED);
        assertThat(owned.perWorkload()).isFalse();
        assertThat(owned.reason()).contains("장치 전체 값");
        assertThat(exclusiveMetrics.device(new DeviceRef("gpu-node-1", AcceleratorRequirement.Kind.GPU, "pci:0000:02:00.0"), "lab", pods, START, END).attribution())
            .as("same Pod name in another namespace is not the target").isEqualTo(DeviceObservation.Attribution.NODE_DEVICE_SHARED);
    }

    private static String twoSeries(String a, String b, double from, double[] first, double[] second) {
        String one = matrix(a, from, 15, first), two = matrix(b, from, 15, second);
        return one.substring(0, one.lastIndexOf("]}}")) + "," + two.substring(two.indexOf("\"result\":[") + 10);
    }

    @Test void replicasAreSummarisedPerPodContainerAndNeverSummedIntoOneRequirement() throws Exception {
        double endEpoch = END.getEpochSecond();
        var metrics = serve(q -> {
            if (q.contains("kube_replicaset_owner")) return vector(sample("\"replicaset\":\"detector-7d9\",\"owner_name\":\"detector\""));
            if (q.contains("kube_pod_owner")) return vector(sample("\"pod\":\"detector-a\",\"owner_name\":\"detector-7d9\""), sample("\"pod\":\"detector-b\",\"owner_name\":\"detector-7d9\""));
            if (q.contains("kube_pod_info")) return vector(sample("\"pod\":\"detector-a\",\"uid\":\"uid-a\",\"node\":\"n1\""), sample("\"pod\":\"detector-b\",\"uid\":\"uid-b\",\"node\":\"n2\""));
            if (q.contains("container_cpu_usage_seconds_total"))
                return twoSeries("\"pod\":\"detector-a\"", "\"pod\":\"detector-b\"", endEpoch - 3585, ramp(240, 100, 0), ramp(240, 300, 0));
            return twoSeries("\"pod\":\"detector-a\"", "\"pod\":\"detector-b\"", endEpoch - 3585, ramp(240, 2.0e8, 0), ramp(240, 5.0e8, 0));
        });
        var result = metrics.measure(new WorkloadTarget("edgeai", WorkloadTarget.Kind.Deployment, "detector", "model", null, null), START, END);
        assertThat(result.cpu().max()).as("largest single replica, not 100m+300m").isEqualTo(300.0);
        assertThat(result.cpu().min()).isEqualTo(100.0);
        assertThat(result.memory().max()).isEqualTo(5.0e8);
        assertThat(result.cpu().samples()).isEqualTo(480);
        assertThat(result.perPod()).extracting(PodUsage::pod, PodUsage::cpuMaxMillicores, PodUsage::memoryMaxBytes)
            .containsExactly(tuple("detector-a", 100.0, 2.0e8), tuple("detector-b", 300.0, 5.0e8));
    }

    @Test void aPodNameReusedByAnotherIncarnationIsAmbiguousInsteadOfMergedUnderOneUid() throws Exception {
        var metrics = serve(q -> {
            if (q.contains("kube_pod_owner")) return vector(sample("\"pod\":\"db-0\",\"owner_name\":\"db\""));
            if (q.contains("kube_pod_info")) return vector(sample("\"pod\":\"db-0\",\"uid\":\"11111111-1111-4111-8111-111111111111\",\"node\":\"n1\""),
                sample("\"pod\":\"db-0\",\"uid\":\"22222222-2222-4222-8222-222222222222\",\"node\":\"n2\""));
            throw new AssertionError("usage must not be queried for an ambiguous incarnation: " + q);
        });
        for (var uid : new UUID[]{UUID.fromString("11111111-1111-4111-8111-111111111111"), null}) {
            var result = metrics.measure(new WorkloadTarget("edgeai", WorkloadTarget.Kind.StatefulSet, "db", "postgres", "db-0", uid), START, END);
            assertThat(result.cpu().status()).isEqualTo(ObservationStatus.AMBIGUOUS);
            assertThat(result.memory().max()).isNull();
            assertThat(result.pods()).extracting(ObservedPod::uid).hasSize(2);
        }
        assertThat(queries).noneMatch(q -> q.contains("container_"));
    }

    @Test void exportersWithoutRangeSupportAreUnsupportedNotZero() throws Exception {
        var metrics = serve(q -> q.contains("node_accelerator_info") ? vector(sample("\"device\":\"pci:0001:01:00.0\",\"vendor\":\"Hailo\",\"kind\":\"NPU\"")) : EMPTY_MATRIX);
        var hailo = metrics.device(new DeviceRef("edge-1", AcceleratorRequirement.Kind.NPU, "pci:0001:01:00.0"), "edgeai", List.of(), START, END);
        assertThat(hailo.status()).isEqualTo(ObservationStatus.UNSUPPORTED);
        assertThat(hailo.utilization()).isNull();
        var jetson = metrics.device(new DeviceRef("edge-1", AcceleratorRequirement.Kind.GPU, "platform:17000000.gpu"), "edgeai", List.of(), START, END);
        assertThat(jetson.status()).isEqualTo(ObservationStatus.UNSUPPORTED);
    }

    @Test void identitiesAreValidatedBeforeAnyQueryAndNamesAreAnchored() {
        assertThatThrownBy(() -> new WorkloadTarget("edgeai", WorkloadTarget.Kind.Deployment, "x\"} or vector(1) #", "c", null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeviceRef("node-1", AcceleratorRequirement.Kind.GPU, "GPU-1\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PrometheusWorkloadMetrics.exact(List.of("a.b", "c"))).isEqualTo("\"a\\\\.b|c\"");
        assertThat(PrometheusWorkloadMetrics.pciAddress("pci:0000:02:00.0")).isEqualTo(PrometheusWorkloadMetrics.pciAddress("00000000:02:00.0"));
        assertThat(PrometheusWorkloadMetrics.step(START, END)).isEqualTo(15);
        assertThat(PrometheusWorkloadMetrics.step(END.minus(Duration.ofDays(14)), END)).isEqualTo(1210);
        assertThatThrownBy(() -> new PrometheusWorkloadMetrics("http://prom:9090/api?x=1")).isInstanceOf(IllegalArgumentException.class);
    }
}
