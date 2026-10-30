package io.edgeai.app.support;

import io.edgeai.domain.aimodel.InferenceLoadDriver.Progress;
import io.edgeai.domain.aimodel.ModelResourceProfile;
import io.edgeai.domain.aimodel.ProfilingRun;
import io.edgeai.domain.resourceprofile.ObservationStatus;
import io.edgeai.domain.resourceprofile.WorkloadMeasurement;
import java.util.*;
import static io.edgeai.app.support.ResourceProfileDocuments.ordered;
import static io.edgeai.app.support.WorkflowInput.JSON;

/** API views of measurement runs and confirmed model resource profiles, and the fixed recommendation policy. */
public final class ModelProfilingDocuments {
    public static final String POLICY = "edgeai-model-profiling-recommendation-v1";
    public static final String PROFILE_SCOPE = "edgeai-ai-model-resource-profile-v1";
    public static final double CPU_REQUEST_FACTOR = 1.15, CPU_MAXIMUM_FACTOR = 1.5, MEMORY_REQUEST_FACTOR = 1.2, MEMORY_MAXIMUM_FACTOR = 1.5;
    public static final double NEAR_ALLOCATION = 0.9;
    public static final List<String> RESULT_LIMITATIONS = List.of(
        "지정한 시험 노드·시험 할당량·부하 조건에서 한 번 관측한 값입니다. 이론적 최소 요구량이나 처리량·지연 목표 충족을 증명하지 않습니다.",
        "시험 할당량이 CPU limit으로도 걸리므로 관측 CPU는 할당량에서 잘린(throttling) 값일 수 있으며 실제 수요의 상한이 아닙니다.",
        "최대값은 수집 간격(step) 해상도의 관측 최대이며 순간 최대를 보장하지 않습니다.",
        "GPU/NPU 장치 지표는 장치 전체 값이며 모델 단독 사용량이 아닐 수 있습니다. 사용률을 장치 개수로 환산하지 않습니다.",
        "GPU/NPU 요구 수량은 시험에 할당한 값이며 측정으로 산정한 값이 아닙니다.");
    /** HTTP runs only: their window opens at the first 2xx, after the server has loaded the model. */
    public static final String HTTP_WINDOW_LIMITATION =
        "HTTP 측정 구간은 첫 2xx 응답 이후부터이므로 기동 중 모델 적재 시점의 메모리·CPU 최대값은 포함하지 않습니다. 추천값은 시험한 부하에 대한 값이며 최소 자원 증명이 아닙니다.";
    public static List<String> resultLimitations(boolean http) {
        if (!http) return RESULT_LIMITATIONS;
        var all = new ArrayList<>(RESULT_LIMITATIONS);
        all.add(1, HTTP_WINDOW_LIMITATION);
        return List.copyOf(all);
    }
    public static final List<String> PROFILE_LIMITATIONS = List.of(
        "확정 요구량은 실행 측에 전달할 선언이며 자원 예약·분할·증강이나 실행 보장이 아닙니다.",
        "사용 가능 판정은 이 프로파일의 이미지·실행 명령·부하 정의·시험 노드·가속기 구성 범위에서만 의미가 있습니다.",
        "사용자 지정(MANUAL) 값은 시험으로 검증되지 않았습니다.");
    private ModelProfilingDocuments() {}

    // ---- recommendation ----------------------------------------------------------------------------------------------

    static long ceil(double value, long unit) { return Math.max(unit, (long) Math.ceil(value / unit) * unit); }
    /**
     * Fixed policy over one SUCCEEDED measurement: request from the p95 (CPU) or maximum (memory) with headroom, maximum
     * from the observed maximum. Minimums are never produced: one observation cannot establish a lower bound.
     */
    public static Map<String, Object> recommendation(WorkloadMeasurement m, Map<String, Object> allocation) {
        long cpuRequest = ceil(m.cpu().p95() * CPU_REQUEST_FACTOR, 10);
        long cpuMaximum = Math.max(cpuRequest, ceil(m.cpu().max() * CPU_MAXIMUM_FACTOR, 10));
        long mib = 1L << 20;
        long memoryRequest = ceil(m.memory().max() * MEMORY_REQUEST_FACTOR, mib);
        long memoryMaximum = Math.max(memoryRequest, ceil(m.memory().max() * MEMORY_MAXIMUM_FACTOR, mib));
        var result = ordered("policy", POLICY,
            "cpu", ordered("requestMillicores", cpuRequest, "maximumMillicores", cpuMaximum),
            "memory", ordered("requestBytes", memoryRequest, "maximumBytes", memoryMaximum));
        var accelerator = allocation.get("accelerator");
        result.put("accelerators", accelerator instanceof Map<?, ?> a
            ? List.of(ordered("resourceName", a.get("resourceName"), "count", a.get("count"), "basis", "TEST_ALLOCATION")) : List.of());
        result.put("rationale", List.of(
            "CPU 요청 = 관측 p95 × " + CPU_REQUEST_FACTOR + " (10m 단위 올림), 최대 = max(관측 최대 × " + CPU_MAXIMUM_FACTOR + ", 요청)",
            "메모리 요청 = 관측 최대 working set × " + MEMORY_REQUEST_FACTOR + " (MiB 단위 올림), 최대 = 관측 최대 × " + MEMORY_MAXIMUM_FACTOR,
            "최소값은 단일 관측으로 정할 수 없어 추천하지 않습니다.",
            "GPU/NPU는 시험 할당 자원명·수를 그대로 기록하며 사용률로 환산하지 않습니다."));
        return result;
    }
    /** Hints when the test allocation may have capped what was observed. */
    public static List<String> notices(WorkloadMeasurement m, Map<String, Object> allocation) {
        var notices = new ArrayList<String>();
        double cpu = ((Number) allocation.get("cpuMillicores")).doubleValue(), memory = ((Number) allocation.get("memoryBytes")).doubleValue();
        if (m.cpu().p95() != null && m.cpu().p95() >= NEAR_ALLOCATION * cpu)
            notices.add("CPU p95가 시험 할당량의 90% 이상입니다. CPU limit에 걸려 수요가 잘렸을 수 있으니 더 큰 CPU 할당량으로 재측정하세요.");
        if (m.memory().max() != null && m.memory().max() >= NEAR_ALLOCATION * memory)
            notices.add("메모리 최대가 시험 할당량의 90% 이상입니다. 더 큰 메모리 할당량으로 재측정하세요.");
        return notices;
    }

    // ---- load evidence -----------------------------------------------------------------------------------------------

    public static Map<String, Object> progress(Progress p) {
        var map = ordered("requests", p.requests(), "succeeded", p.succeeded(), "failed", p.failed(), "connectionFailures", p.connectionFailures());
        map.put("statusCounts", new TreeMap<>(p.statusCounts()));
        map.put("latencyMillis", ordered("p50", p.latencyP50Millis(), "p95", p.latencyP95Millis(), "max", p.latencyMaxMillis(),
            "approximation", "고정 로그 구간(5%) 상한 근사, max는 실제 관측값"));
        map.put("startedAt", ResourceProfileDocuments.instant(p.started()));
        map.put("endedAt", ResourceProfileDocuments.instant(p.ended()));
        return map;
    }
    public static Map<String, Object> httpEvidence(Progress p, double seconds) {
        var map = ordered("type", "HTTP");
        map.putAll(progress(p));
        map.put("successRatio", p.requests() == 0 ? null : (double) p.succeeded() / p.requests());
        map.put("throughputPerSecond", seconds <= 0 ? null : p.succeeded() / seconds);
        return map;
    }

    // ---- views -------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> run(ProfilingRun r, String namespace, String image) {
        var request = (Map<String, Object>) JSON.decode(r.requestJson());
        var load = new LinkedHashMap<>((Map<String, Object>) request.get("load"));
        // The sample body is stored for the Job's lifetime and replay checks; views show its size and digest only.
        Object body = load.remove("body"), base64 = load.remove("bodyBase64");
        if (load.get("type").equals("HTTP") && load.get("method").equals("POST")) {
            byte[] bytes = body != null ? ((String) body).getBytes(java.nio.charset.StandardCharsets.UTF_8) : Base64.getDecoder().decode((String) base64);
            load.put("bodyBytes", bytes.length);
            load.put("bodyDigest", sha256(bytes));
        }
        var map = ordered("id", r.id().toString(), "modelId", r.modelId().toString(), "state", r.state(), "message", r.message(),
            "nodeName", r.nodeName(), "nodeArchitecture", r.nodeArchitecture(), "allocation", request.get("allocation"),
            "durationSeconds", request.get("durationSeconds"), "load", load);
        var k = ordered("namespace", namespace, "jobName", r.jobName(), "jobUid", str(r.jobUid()), "podName", r.podName(), "podUid", str(r.podUid()));
        k.put("image", image);
        map.put("kubernetes", k);
        map.put("window", ordered("start", ResourceProfileDocuments.instant(r.windowStart()), "end", ResourceProfileDocuments.instant(r.windowEnd())));
        map.put("progress", !r.finished() && r.progressJson() != null ? JSON.decode(r.progressJson()) : null);
        map.put("createdAt", r.createdAt().toString());
        map.put("updatedAt", r.updatedAt().toString());
        map.put("finishedAt", ResourceProfileDocuments.instant(r.finishedAt()));
        map.put("cancelRequestedAt", ResourceProfileDocuments.instant(r.cancelRequestedAt()));
        map.put("cleanup", ordered("state", r.cleanupState(), "completedAt", ResourceProfileDocuments.instant(r.cleanedAt())));
        map.put("failure", r.failureCode() == null ? null : ordered("code", r.failureCode(), "message", r.failureMessage()));
        map.put("result", r.resultJson() == null ? null : JSON.decode(r.resultJson()));
        return map;
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> profile(ModelResourceProfile p) {
        var map = ordered("id", p.id().toString(), "modelId", p.modelId().toString(), "revision", p.revision(), "runId", p.runId().toString(),
            "digest", p.digest(), "createdAt", p.createdAt().toString());
        map.putAll((Map<String, Object>) JSON.decode(p.documentJson()));
        return map;
    }
    public static Map<String, Object> profileSummary(ModelResourceProfile p) {
        var full = profile(p);
        return ordered("id", full.get("id"), "revision", full.get("revision"), "runId", full.get("runId"), "createdAt", full.get("createdAt"),
            "requirements", full.get("requirements"), "scope", full.get("scope"));
    }
    public static String sha256(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String str(UUID value) { return value == null ? null : value.toString(); }
    public static boolean available(ObservationStatus status) { return status == ObservationStatus.AVAILABLE; }
}
