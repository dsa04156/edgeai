package io.edgeai.app.support;

import io.edgeai.domain.profile.ProfileVersion;
import io.edgeai.domain.resourceprofile.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** JSON documents of analyses, saved revisions and the versioned 2-1 handoff contract. */
public final class ResourceProfileDocuments {
    public static final String HANDOFF_API_VERSION = "edgeai.io/resource-profile-handoff/v1";
    public static final String PROFILE_SCOPE = "edgeai-resource-profile-v1";
    private ResourceProfileDocuments() {}

    public static Map<String, Object> service(ProfileVersion version) {
        return ordered("profileVersionId", version.id().toString(), "key", version.identity().key(),
            "version", version.identity().version(), "digest", version.digest());
    }

    public static Map<String, Object> target(WorkloadTarget target) {
        var map = ordered("namespace", target.namespace(), "kind", target.kind().name(), "name", target.name(), "container", target.container());
        map.put("pod", target.pod());
        map.put("podUid", target.podUid() == null ? null : target.podUid().toString());
        return map;
    }

    public static Map<String, Object> usage(UsageSummary usage) {
        var map = ordered("status", usage.status().name(), "unit", usage.unit());
        map.put("min", number(usage.min())); map.put("average", number(usage.average()));
        map.put("p95", number(usage.p95())); map.put("max", number(usage.max()));
        map.put("samples", usage.samples());
        map.put("firstSample", usage.firstSample() == null ? null : usage.firstSample().toString());
        map.put("lastSample", usage.lastSample() == null ? null : usage.lastSample().toString());
        map.put("reason", usage.reason());
        return map;
    }

    public static Map<String, Object> measurement(WorkloadMeasurement m) {
        var pods = m.pods().stream().map(p -> (Object) ordered("name", p.name(), "uid", p.uid(), "node", p.node())).toList();
        var map = ordered("method", m.method(), "aggregation", WorkloadMeasurement.AGGREGATION, "stepSeconds", m.stepSeconds(), "pods", pods);
        map.put("cpu", usage(m.cpu())); map.put("memory", usage(m.memory()));
        map.put("perPod", m.perPod().stream().map(p -> {
            var row = ordered("pod", p.pod(), "samples", p.samples());
            row.put("cpuP95Millicores", number(p.cpuP95Millicores())); row.put("cpuMaxMillicores", number(p.cpuMaxMillicores()));
            row.put("memoryMaxBytes", number(p.memoryMaxBytes()));
            return (Object) row;
        }).toList());
        return map;
    }

    public static Map<String, Object> device(DeviceObservation d) {
        var map = ordered("node", d.device().node(), "kind", d.device().kind().name(), "device", d.device().device());
        map.put("exporter", d.exporter()); map.put("model", d.model());
        map.put("attribution", d.attribution().name()); map.put("perWorkload", d.perWorkload());
        map.put("status", d.status().name());
        map.put("utilization", d.utilization() == null ? null : usage(d.utilization()));
        map.put("memoryUsed", d.memoryUsed() == null ? null : usage(d.memoryUsed()));
        map.put("memoryTotalBytes", number(d.memoryTotalBytes()));
        map.put("reason", d.reason());
        return map;
    }

    public static Map<String, Object> recommendation(KrrRecommendation r) {
        var map = ordered("engine", "KRR", "status", r.status().name(), "reason", r.reason());
        var window = ordered("historyHours", r.historyHours());
        window.put("start", r.windowStart() == null ? null : r.windowStart().toString());
        window.put("end", r.windowEnd() == null ? null : r.windowEnd().toString());
        map.put("window", window);
        var cpu = new LinkedHashMap<String, Object>();
        cpu.put("requestMillicores", r.cpuRequestMillicores()); cpu.put("limitMillicores", r.cpuLimitMillicores()); cpu.put("info", r.cpuInfo());
        var memory = new LinkedHashMap<String, Object>();
        memory.put("requestBytes", r.memoryRequestBytes()); memory.put("limitBytes", r.memoryLimitBytes()); memory.put("info", r.memoryInfo());
        map.put("cpu", cpu); map.put("memory", memory);
        map.put("podCount", r.podCount()); map.put("warnings", r.warnings());
        map.put("provenance", new TreeMap<>(r.provenance()));
        return map;
    }

    public static Map<String, Object> notRequested() {
        var map = ordered("engine", "KRR", "status", "NOT_REQUESTED", "reason", "KRR 추천을 요청하지 않았습니다.");
        var cpu = new LinkedHashMap<String, Object>(); cpu.put("requestMillicores", null); cpu.put("limitMillicores", null); cpu.put("info", null);
        var memory = new LinkedHashMap<String, Object>(); memory.put("requestBytes", null); memory.put("limitBytes", null); memory.put("info", null);
        map.put("window", null); map.put("cpu", cpu); map.put("memory", memory);
        map.put("podCount", 0); map.put("warnings", List.of()); map.put("provenance", Map.of());
        return map;
    }

    public static final List<String> ANALYSIS_LIMITATIONS = List.of(
        "CPU는 cAdvisor container_cpu_usage_seconds_total의 rate, 메모리는 container_memory_working_set_bytes입니다. 값은 Pod당 컨테이너 기준이며 복제본을 합산하지 않습니다(perPod는 복제본별 범위). 최대값은 수집·조회 간격의 해상도로 제한됩니다.",
        "KRR 추천은 KRR 자체의 이력 구간(현재 시각 기준 historyHours)을 사용하며 측정 구간과 다를 수 있습니다. KRR은 CPU·메모리만 추천합니다.",
        "GPU/NPU 장치 지표는 노드의 장치 단위 관측이며 exporter의 Pod 표시는 연관일 뿐 단독 점유가 아닙니다. 서비스별 사용량으로 해석하지 않으며 사용률을 장치 개수로 변환하지 않습니다.");

    /** Saved revision response: the canonical stored document plus its storage identity. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> profile(ResourceProfile profile) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", profile.id().toString());
        result.put("revision", profile.revision());
        result.put("measurementStatus", profile.evidenceStatus());
        result.put("analysisId", profile.analysisId() == null ? null : profile.analysisId().toString());
        result.put("digest", profile.digest());
        result.put("createdAt", profile.createdAt().toString());
        result.putAll((Map<String, Object>) WorkflowInput.JSON.decode(profile.documentJson()));
        return result;
    }

    /** Versioned 2-1 contract: exact revision, Kubernetes-quantity mappings and compatibility; never Pod YAML. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> handoff(ResourceProfile profile) {
        var document = (Map<String, Object>) WorkflowInput.JSON.decode(profile.documentJson());
        var resources = (Map<String, Object>) document.get("resources");
        var cpu = (Map<String, Object>) resources.get("cpu");
        var memory = (Map<String, Object>) resources.get("memory");
        long cpuMin = longValue(cpu.get("minMillicores")), cpuRequest = longValue(cpu.get("requestMillicores")), cpuMax = longValue(cpu.get("maxMillicores"));
        long memMin = longValue(memory.get("minBytes")), memRequest = longValue(memory.get("requestBytes")), memMax = longValue(memory.get("maxBytes"));
        var result = new LinkedHashMap<String, Object>();
        result.put("apiVersion", HANDOFF_API_VERSION);
        result.put("kind", "ResourceProfileHandoff");
        result.put("profile", ordered("id", profile.id().toString(), "revision", profile.revision(), "digest", profile.digest(),
            "createdAt", profile.createdAt().toString(), "measurementStatus", profile.evidenceStatus()));
        result.put("service", document.get("service"));
        result.put("compatibility", document.get("compatibility"));
        result.put("inputConditions", document.get("inputConditions"));
        var outCpu = ordered("unit", "millicores", "min", cpuMin, "request", cpuRequest, "max", cpuMax, "basis", cpu.get("basis"));
        outCpu.put("kubernetes", ordered("min", cpuQuantity(cpuMin), "request", cpuQuantity(cpuRequest), "max", cpuQuantity(cpuMax)));
        var outMemory = ordered("unit", "bytes", "min", memMin, "request", memRequest, "max", memMax, "basis", memory.get("basis"));
        outMemory.put("kubernetes", ordered("min", memoryQuantity(memMin), "request", memoryQuantity(memRequest), "max", memoryQuantity(memMax)));
        var accelerators = new ArrayList<Object>();
        for (var item : (List<Map<String, Object>>) resources.get("accelerators")) {
            var a = new LinkedHashMap<String, Object>(item);
            a.put("kubernetes", ordered("min", item.get("min").toString(), "request", item.get("request").toString(), "max", item.get("max").toString()));
            a.put("deviceMemoryReservable", false);
            a.put("verticalScaling", "NOT_SUPPORTED");
            a.put("basis", "MANUAL");
            accelerators.add(a);
        }
        result.put("resources", ordered("cpu", outCpu, "memory", outMemory, "accelerators", accelerators));
        var vpa = new LinkedHashMap<String, Object>();
        vpa.put("controlledResources", List.of("cpu", "memory"));
        vpa.put("minAllowed", ordered("cpu", cpuQuantity(cpuMin), "memory", memoryQuantity(memMin)));
        vpa.put("maxAllowed", ordered("cpu", cpuQuantity(cpuMax), "memory", memoryQuantity(memMax)));
        vpa.put("recommendedUpdateMode", "Off");
        vpa.put("compatibility", ordered("kubernetes", "1.31", "verticalPodAutoscaler", "1.5.x", "kubeEdge", "UNVERIFIED"));
        vpa.put("acceleratorScaling", "NOT_SUPPORTED");
        result.put("mappings", ordered(
            "podContainerResources", ordered(
                "requests.cpu", "resources.cpu.kubernetes.request",
                "requests.memory", "resources.memory.kubernetes.request",
                "requests[accelerators[].resourceName]", "resources.accelerators[].kubernetes.request",
                "upperBound", "resources.*.kubernetes.max is the permitted maximum; 2-1 decides container limits"),
            "verticalPodAutoscaler", vpa));
        var evidence = document.get("evidence") instanceof Map<?, ?> e ? (Map<String, Object>) e : null;
        var outEvidence = ordered("measurementStatus", profile.evidenceStatus(), "analysisId", profile.analysisId() == null ? null : profile.analysisId().toString());
        outEvidence.put("target", evidence == null ? null : evidence.get("target"));
        outEvidence.put("window", evidence == null ? null : evidence.get("window"));
        outEvidence.put("measurement", evidence == null ? null : evidence.get("measurement"));
        outEvidence.put("recommendation", evidence == null ? null : evidence.get("recommendation"));
        outEvidence.put("devices", evidence == null ? List.of() : evidence.get("devices"));
        result.put("evidence", outEvidence);
        result.put("limitations", List.of(
            "요구량·허용 범위 선언이며 실제 예약·분할·증강은 실행 측(HAMi/device plugin/VPA) 담당입니다.",
            "deviceMemoryBytes는 모델의 장치 메모리 요구량이며 Kubernetes에서 예약 가능한 자원 필드가 아닙니다.",
            "VPA 연계는 CPU·메모리만 해당하며 GPU/NPU는 VPA로 조정하지 않습니다. KubeEdge 환경의 VPA 동작은 검증되지 않았습니다."));
        return result;
    }

    public static String cpuQuantity(long millicores) { return millicores + "m"; }
    public static String memoryQuantity(long bytes) {
        String[] suffixes = {"Ti", "Gi", "Mi", "Ki"};
        for (int i = 0; i < suffixes.length; i++) {
            long unit = 1L << (10 * (4 - i));
            if (bytes % unit == 0) return bytes / unit + suffixes[i];
        }
        return Long.toString(bytes);
    }
    static long longValue(Object value) { return new BigDecimal(value.toString()).longValueExact(); }
    static Object number(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }
    public static LinkedHashMap<String, Object> ordered(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
    public static String instant(Instant value) { return value == null ? null : value.toString(); }
}
