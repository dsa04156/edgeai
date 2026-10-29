package io.edgeai.adapters.profiling;

import io.edgeai.domain.resourceprofile.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only Prometheus range queries for one explicit workload container (cAdvisor + kube-state-metrics) and for
 * selected devices of the existing exporters. Every expression is a fixed template; only validated Kubernetes names
 * and normalised device identities are interpolated, so no caller-supplied PromQL is ever evaluated.
 */
public final class PrometheusWorkloadMetrics implements WorkloadMetricsSource, AutoCloseable {
    public static final int MIN_SAMPLES = 10;
    public static final int MAX_PODS = 200;
    public static final int MAX_POINTS = 1000;
    private static final long MAX_BODY = 8L * 1024 * 1024;
    private final URI query;
    private final URI range;
    private final HttpClient client;
    private final JsonMapper json = new JsonMapper();

    public PrometheusWorkloadMetrics(String url) {
        URI origin = URI.create(url.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
            || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null || !origin.getPath().isEmpty())
            throw new IllegalArgumentException("Prometheus URL must be an HTTP(S) origin without credentials");
        query = origin.resolve("/api/v1/query");
        range = origin.resolve("/api/v1/query_range");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Series(Map<String, String> labels, List<double[]> points) {
        String label(String key) { return labels.getOrDefault(key, ""); }
    }

    // ---- workload discovery -------------------------------------------------------------------------------------

    @Override public List<WorkloadCandidate> workloads(String namespace, Instant start, Instant end) {
        String ns = quote(WorkloadTarget.namespace(namespace));
        String lookback = seconds(start, end);
        var owners = instant("max_over_time(kube_pod_owner{namespace=" + ns + "}[" + lookback + "])", end);
        var replicaSets = owners("max_over_time(kube_replicaset_owner{namespace=" + ns + ",owner_kind=\"Deployment\"}[" + lookback + "])", "replicaset", end);
        var jobs = owners("max_over_time(kube_job_owner{namespace=" + ns + ",owner_kind=\"CronJob\"}[" + lookback + "])", "job_name", end);
        var containers = new HashMap<String, Set<String>>();
        for (var s : instant("max_over_time(kube_pod_container_info{namespace=" + ns + "}[" + lookback + "])", end))
            if (!s.label("container").isEmpty()) containers.computeIfAbsent(s.label("pod"), k -> new TreeSet<>()).add(s.label("container"));
        var result = new TreeMap<String, WorkloadCandidate>();
        for (var s : owners) {
            String pod = s.label("pod"), kind = s.label("owner_kind"), name = s.label("owner_name");
            WorkloadTarget.Kind resolved;
            if (kind.equals("ReplicaSet") && replicaSets.containsKey(name)) { resolved = WorkloadTarget.Kind.Deployment; name = replicaSets.get(name); }
            else if (kind.equals("Job") && jobs.containsKey(name)) { resolved = WorkloadTarget.Kind.CronJob; name = jobs.get(name); }
            else if (Set.of("StatefulSet", "DaemonSet", "Job").contains(kind)) resolved = WorkloadTarget.Kind.valueOf(kind);
            else if (kind.isEmpty() || kind.equals("<none>")) { resolved = WorkloadTarget.Kind.Pod; name = pod; }
            else continue; // other controllers (e.g. bare ReplicaSet) are not offered as targets
            if (name.isEmpty() || pod.isEmpty()) continue;
            String key = resolved + "/" + name;
            var known = result.get(key);
            var merged = new TreeSet<>(known == null ? List.of() : known.containers());
            merged.addAll(containers.getOrDefault(pod, Set.of()));
            result.put(key, new WorkloadCandidate(namespace, resolved, name, List.copyOf(merged)));
        }
        return List.copyOf(result.values());
    }

    @Override public List<ObservedPod> pods(WorkloadTarget target, Instant start, Instant end) {
        String ns = quote(target.namespace());
        String lookback = seconds(start, end);
        List<String> names;
        if (target.kind() == WorkloadTarget.Kind.Pod) names = List.of(target.name());
        else {
            List<String> ownerNames = List.of(target.name());
            String ownerKind = target.kind().name();
            if (target.kind() == WorkloadTarget.Kind.Deployment) {
                ownerNames = labels("max_over_time(kube_replicaset_owner{namespace=" + ns + ",owner_kind=\"Deployment\",owner_name=" + quote(target.name()) + "}[" + lookback + "])", "replicaset", end);
                ownerKind = "ReplicaSet";
            } else if (target.kind() == WorkloadTarget.Kind.CronJob) {
                ownerNames = labels("max_over_time(kube_job_owner{namespace=" + ns + ",owner_kind=\"CronJob\",owner_name=" + quote(target.name()) + "}[" + lookback + "])", "job_name", end);
                ownerKind = "Job";
            }
            if (ownerNames.isEmpty()) return List.of();
            names = labels("max_over_time(kube_pod_owner{namespace=" + ns + ",owner_kind=\"" + ownerKind + "\",owner_name=~" + exact(ownerNames) + "}[" + lookback + "])", "pod", end);
        }
        if (names.isEmpty()) return List.of();
        var info = new TreeMap<String, ObservedPod>();
        for (var s : instant("max_over_time(kube_pod_info{namespace=" + ns + ",pod=~" + exact(names.stream().limit(MAX_PODS).toList()) + "}[" + lookback + "])", end))
            info.put(s.label("pod") + "/" + s.label("uid"), new ObservedPod(s.label("pod"), s.label("uid"), s.label("node")));
        for (String name : names) if (info.keySet().stream().noneMatch(k -> k.startsWith(name + "/"))) info.put(name + "/", new ObservedPod(name, "", ""));
        return info.values().stream().sorted(Comparator.comparing(ObservedPod::name).thenComparing(ObservedPod::uid)).limit(MAX_PODS).toList();
    }

    // ---- workload measurement ----------------------------------------------------------------------------------

    @Override public WorkloadMeasurement measure(WorkloadTarget target, Instant start, Instant end) {
        int step = step(start, end);
        var pods = pods(target, start, end);
        if (target.pod() != null) {
            var incarnations = pods.stream().filter(p -> p.name().equals(target.pod())).toList();
            // cAdvisor series are selected by Pod name. A name reused by several UIDs in the window (e.g. a recreated
            // StatefulSet Pod) would merge incarnations, so a Pod-scoped measurement is refused instead of mislabelled.
            if (incarnations.stream().map(ObservedPod::uid).distinct().count() > 1)
                return missing(start, end, step, incarnations, ObservationStatus.AMBIGUOUS,
                    "구간에 같은 Pod 이름의 실행(UID)이 여러 개 있어 하나의 Pod 실행으로 측정할 수 없습니다. 구간을 한 실행으로 좁히세요.");
            pods = incarnations.stream().filter(p -> target.podUid() == null || p.uid().equals(target.podUid().toString())).toList();
        }
        if (pods.isEmpty())
            return missing(start, end, step, List.of(), ObservationStatus.NOT_FOUND,
                target.podUid() != null ? "지정한 Pod UID가 해당 워크로드의 관측 Pod와 일치하지 않습니다." : "관측 구간에서 대상 워크로드의 Pod를 찾지 못했습니다.");
        String selector = "namespace=" + quote(target.namespace()) + ",pod=~" + exact(pods.stream().map(ObservedPod::name).distinct().toList())
            + ",container=" + quote(target.container());
        int rateWindow = Math.max(120, 2 * step);
        // Duplicate scrapes of one container cgroup (same id from several kubelet/cAdvisor jobs) are collapsed with
        // max before summing the Pod's container cgroups; replicas stay separate series (by pod), never summed.
        var cpu = rangeQuery("sum by(pod) (max by(pod,id) (rate(container_cpu_usage_seconds_total{" + selector + "}[" + rateWindow + "s]))) * 1000", start, end, step);
        var memory = rangeQuery("sum by(pod) (max by(pod,id) (container_memory_working_set_bytes{" + selector + "}))", start, end, step);
        return new WorkloadMeasurement(start, end, step, pods, summarize(cpu, "millicores", end, step), summarize(memory, "bytes", end, step),
            "cadvisor per Pod container (" + WorkloadMeasurement.AGGREGATION + "): rate(container_cpu_usage_seconds_total[" + rateWindow
                + "s]) and container_memory_working_set_bytes, duplicate scrapes collapsed by cgroup id", perPod(cpu, memory));
    }

    private static WorkloadMeasurement missing(Instant start, Instant end, int step, List<ObservedPod> pods, ObservationStatus status, String reason) {
        return new WorkloadMeasurement(start, end, step, pods, UsageSummary.missing(status, "millicores", 0, reason),
            UsageSummary.missing(status, "bytes", 0, reason), "cadvisor", List.of());
    }

    /** Each replica's own envelope, so a multi-replica workload is never presented as one summed Pod requirement. */
    static List<PodUsage> perPod(List<Series> cpu, List<Series> memory) {
        var names = new TreeSet<String>();
        cpu.forEach(s -> names.add(s.label("pod")));
        memory.forEach(s -> names.add(s.label("pod")));
        var result = new ArrayList<PodUsage>();
        for (String pod : names) {
            var cpuValues = values(cpu, pod);
            var memoryValues = values(memory, pod);
            Double p95 = cpuValues.isEmpty() ? null : cpuValues.get(Math.max(0, (int) Math.ceil(0.95 * cpuValues.size()) - 1));
            result.add(new PodUsage(pod, p95, cpuValues.isEmpty() ? null : cpuValues.getLast(), memoryValues.isEmpty() ? null : memoryValues.getLast(),
                Math.max(cpuValues.size(), memoryValues.size())));
        }
        return result;
    }
    private static List<Double> values(List<Series> series, String pod) {
        return series.stream().filter(s -> s.label("pod").equals(pod)).flatMap(s -> s.points().stream()).map(p -> p[1])
            .filter(v -> Double.isFinite(v) && v >= 0).sorted().toList();
    }

    /** Distribution of every per-Pod sample; stale when the newest sample stops well before the window end. */
    public static UsageSummary summarize(List<Series> series, String unit, Instant end, int step) {
        var values = new ArrayList<Double>();
        double first = Double.MAX_VALUE, last = -1;
        for (var s : series) for (var p : s.points()) {
            if (!Double.isFinite(p[1]) || p[1] < 0) continue;
            values.add(p[1]); first = Math.min(first, p[0]); last = Math.max(last, p[0]);
        }
        if (values.isEmpty()) return UsageSummary.missing(ObservationStatus.NO_DATA, unit, 0, "관측 구간에 해당 지표 시계열이 없습니다.");
        if (values.size() < MIN_SAMPLES)
            return UsageSummary.missing(ObservationStatus.INSUFFICIENT, unit, values.size(), "표본 " + values.size() + "개로 최소 " + MIN_SAMPLES + "개보다 적습니다.");
        Collections.sort(values);
        double sum = 0; for (double v : values) sum += v;
        double p95 = values.get(Math.max(0, (int) Math.ceil(0.95 * values.size()) - 1));
        Instant firstAt = Instant.ofEpochMilli((long) (first * 1000)), lastAt = Instant.ofEpochMilli((long) (last * 1000));
        boolean stale = lastAt.isBefore(end.minusSeconds(Math.max(300, 3L * step)));
        double average = Math.min(Math.max(sum / values.size(), values.getFirst()), values.getLast());
        return new UsageSummary(stale ? ObservationStatus.STALE : ObservationStatus.AVAILABLE, unit, values.getFirst(), average, p95, values.getLast(),
            values.size(), firstAt, lastAt, stale ? "마지막 표본이 관측 구간 종료보다 오래되었습니다." : null);
    }

    // ---- device observation ------------------------------------------------------------------------------------

    @Override public DeviceObservation device(DeviceRef device, String namespace, List<ObservedPod> targetPods, Instant start, Instant end) {
        int step = step(start, end);
        String node = quote(device.node());
        String address = pciAddress(device.device());
        if (device.kind() == AcceleratorRequirement.Kind.GPU && !address.isEmpty()) {
            String join = " * on(namespace,pod) group_left(node) max by(namespace,pod,node) (kube_pod_info{node=" + node + "})";
            var util = matching(rangeQuery("DCGM_FI_DEV_GPU_UTIL" + join, start, end, step), address);
            var used = matching(rangeQuery("DCGM_FI_DEV_FB_USED * 1048576" + join, start, end, step), address);
            var total = matching(rangeQuery("(DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE) * 1048576" + join, start, end, step), address);
            if (util.isEmpty() && used.isEmpty())
                return unsupported(device, "dcgm-exporter", ObservationStatus.NO_DATA, "선택한 GPU의 DCGM 지표가 관측 구간에 없습니다.");
            String model = util.stream().map(s -> s.label("modelName")).filter(v -> !v.isEmpty()).findFirst().orElse(null);
            return observation(device, "dcgm-exporter", model, util, used, total, namespace, targetPods, end, step);
        }
        if (device.kind() == AcceleratorRequirement.Kind.NPU) {
            var inventory = instant("max_over_time(node_accelerator_info{node=" + node + ",kind=\"NPU\"}[" + seconds(start, end) + "])", end);
            var mobilint = inventory.stream().filter(s -> s.label("vendor").toLowerCase(Locale.ROOT).contains("mobilint")).toList();
            boolean selected = mobilint.size() == 1 && (mobilint.getFirst().label("device").equals(device.device()) || device.device().matches("aries[0-9]+"));
            if (selected) {
                var util = rangeQuery("mobilint_npu_utilization_ratio{node=" + node + "} * 100", start, end, step);
                var used = rangeQuery("mobilint_npu_memory_used_bytes{node=" + node + "}", start, end, step);
                var total = rangeQuery("mobilint_npu_memory_total_bytes{node=" + node + "}", start, end, step);
                long devices = util.stream().map(s -> s.label("device")).distinct().count();
                // Without a shared bus address, pair only a unique inventory device with a unique exporter device.
                if (devices > 1) return unsupported(device, "mobilint-device-plugin", ObservationStatus.AMBIGUOUS, "노드에 Mobilint 장치가 여러 개라 선택한 장치와 지표를 1:1로 대응할 수 없습니다.");
                if (util.isEmpty() && used.isEmpty()) return unsupported(device, "mobilint-device-plugin", ObservationStatus.NO_DATA, "선택한 NPU의 Mobilint 지표가 관측 구간에 없습니다.");
                return observation(device, "mobilint-device-plugin", mobilint.getFirst().label("model"), util, used, total, namespace, targetPods, end, step);
            }
        }
        return unsupported(device, null, ObservationStatus.UNSUPPORTED,
            "이 장치의 exporter는 기간 관측 연동을 지원하지 않습니다(지원: NVIDIA DCGM GPU, Mobilint NPU).");
    }

    private DeviceObservation observation(DeviceRef device, String exporter, String model, List<Series> util, List<Series> used, List<Series> total,
                                          String namespace, List<ObservedPod> targetPods, Instant end, int step) {
        var owners = new TreeSet<String>();
        boolean unlabeled = false;
        for (var s : util) {
            if (s.label("exported_pod").isEmpty()) unlabeled = true;
            else owners.add(s.label("exported_namespace") + "/" + s.label("exported_pod"));
        }
        var targets = targetPods.stream().map(p -> namespace + "/" + p.name()).collect(Collectors.toSet());
        DeviceObservation.Attribution attribution;
        if (unlabeled || owners.isEmpty()) attribution = DeviceObservation.Attribution.NODE_DEVICE_UNKNOWN_OWNER;
        else if (!targets.isEmpty() && targets.containsAll(owners))
            attribution = DeviceObservation.Attribution.TARGET_ASSOCIATED;
        else attribution = DeviceObservation.Attribution.NODE_DEVICE_SHARED;
        var utilization = summarize(dedupe(util), "percent", end, step);
        var memory = summarize(dedupe(used), "bytes", end, step);
        Double totalBytes = dedupe(total).stream().flatMap(s -> s.points().stream()).map(p -> p[1]).filter(Double::isFinite).reduce((a, b) -> b).orElse(null);
        var status = utilization.status() == ObservationStatus.AVAILABLE || memory.status() == ObservationStatus.AVAILABLE ? ObservationStatus.AVAILABLE
            : utilization.status() == ObservationStatus.STALE || memory.status() == ObservationStatus.STALE ? ObservationStatus.STALE : utilization.status();
        String reason = switch (attribution) {
            case TARGET_ASSOCIATED -> "exporter Pod 표시가 대상 Pod만 가리키지만 장치 전체 값이며 단독 점유·모델별 사용량을 뜻하지 않습니다.";
            case NODE_DEVICE_SHARED -> "다른 Pod도 이 장치를 사용해 서비스별 사용량으로 귀속할 수 없습니다.";
            case NODE_DEVICE_UNKNOWN_OWNER -> "exporter가 Pod 소유 정보를 제공하지 않아 장치 전체 사용량만 표시합니다.";
        };
        return new DeviceObservation(device, exporter, model, attribution, status, utilization, memory, totalBytes, reason);
    }

    /** Owner-labelled exporters repeat device values per Pod; keep one series per timestamp for device totals. */
    private static List<Series> dedupe(List<Series> series) {
        if (series.size() <= 1) return series;
        var byTime = new TreeMap<Double, Double>();
        for (var s : series) for (var p : s.points()) byTime.merge(p[0], p[1], Math::max);
        var points = byTime.entrySet().stream().map(e -> new double[]{e.getKey(), e.getValue()}).toList();
        return List.of(new Series(Map.of(), points));
    }
    private static List<Series> matching(List<Series> series, String address) {
        return series.stream().filter(s -> pciAddress(s.label("pci_bus_id")).equals(address)).toList();
    }
    private static DeviceObservation unsupported(DeviceRef device, String exporter, ObservationStatus status, String reason) {
        return new DeviceObservation(device, exporter, null, DeviceObservation.Attribution.NODE_DEVICE_UNKNOWN_OWNER, status, null, null, null, reason);
    }
    /** Same normalisation as the node inventory: "pci:0000:02:00.0" and DCGM "00000000:02:00.0" are one device. */
    public static String pciAddress(String value) {
        String address = value.toLowerCase(Locale.ROOT).replaceFirst("^pci:", "");
        if (!address.matches("[0-9a-f]{4,8}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-7]")) return "";
        int colon = address.indexOf(':');
        return Integer.parseUnsignedInt(address.substring(0, colon), 16) + address.substring(colon);
    }

    // ---- HTTP + decoding ---------------------------------------------------------------------------------------

    static int step(Instant start, Instant end) {
        long span = Duration.between(start, end).getSeconds();
        return (int) Math.max(15, (span + MAX_POINTS - 1) / MAX_POINTS);
    }
    private static String seconds(Instant start, Instant end) { return Math.max(60, Duration.between(start, end).getSeconds()) + "s"; }
    private static String quote(String value) { return "\"" + value + "\""; }
    /** Anchored alternation of validated names; '.' is the only regex metacharacter Kubernetes names allow. */
    static String exact(List<String> names) {
        return "\"" + names.stream().map(n -> n.replace(".", "\\\\.")).collect(Collectors.joining("|")) + "\"";
    }
    private Map<String, String> owners(String expression, String childLabel, Instant time) {
        var result = new HashMap<String, String>();
        for (var s : instant(expression, time)) if (!s.label(childLabel).isEmpty()) result.put(s.label(childLabel), s.label("owner_name"));
        return result;
    }
    private List<String> labels(String expression, String label, Instant time) {
        return instant(expression, time).stream().map(s -> s.label(label)).filter(v -> !v.isEmpty()).distinct().sorted().toList();
    }
    List<Series> instant(String expression, Instant time) {
        return decode(post(query, "query=" + encode(expression) + "&timeout=10s&time=" + time.getEpochSecond()), "vector");
    }
    List<Series> rangeQuery(String expression, Instant start, Instant end, int step) {
        return decode(post(range, "query=" + encode(expression) + "&timeout=10s&start=" + start.getEpochSecond() + "&end=" + end.getEpochSecond() + "&step=" + step), "matrix");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private byte[] post(URI uri, String form) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            var response = client.send(request, ignored -> new LimitedBody());
            if (response.statusCode() != 200) throw new IllegalStateException("Prometheus query unavailable");
            return response.body();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Prometheus query interrupted");
        } catch (IOException failure) { throw new IllegalStateException("Prometheus query unavailable"); }
    }

    /** Public for deterministic parsing tests against recorded Prometheus responses. */
    public List<Series> decode(byte[] body, String resultType) {
        JsonNode root = json.readTree(body);
        if (!root.path("status").asText().equals("success") || !root.path("data").path("resultType").asText().equals(resultType)
            || !root.path("data").path("result").isArray())
            throw new IllegalStateException("Incomplete Prometheus response");
        var result = new ArrayList<Series>();
        for (var row : root.path("data").path("result")) {
            var labels = new TreeMap<String, String>();
            row.path("metric").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
            var points = new ArrayList<double[]>();
            if (resultType.equals("vector")) point(row.path("value"), points);
            else for (var value : row.path("values")) point(value, points);
            result.add(new Series(labels, points));
        }
        return result;
    }
    private static void point(JsonNode value, List<double[]> points) {
        if (value.size() != 2) return;
        try {
            double v = Double.parseDouble(value.get(1).asText());
            if (Double.isFinite(v)) points.add(new double[]{value.get(0).asDouble(), v});
        } catch (NumberFormatException ignored) { /* NaN/Inf strings are not observations */ }
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
            if (size > MAX_BODY) { subscription.cancel(); delegate.onError(new IOException("Prometheus response too large")); }
            else delegate.onNext(values);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
