package io.edgeai.app.support;

import io.edgeai.app.config.ModelProfilingSettings;
import io.edgeai.domain.aimodel.InferenceLoadDriver.HttpLoad;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.edgeai.app.config.ModelProfilingSettings.*;
import static io.edgeai.app.support.ResourceProfileInput.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * Strict parsing of a measurement run request and of a requirement confirmation. Unknown fields are rejected, so a
 * request can never carry Pod YAML, volumes, environment, privileges or a target URL: the server builds the Job and
 * the request target from validated parts only.
 */
public final class ModelProfilingInput {
    public static final int MAX_BYTES = 131072;
    public static final String RUN_SCOPE = "edgeai-ai-model-profiling-run-v1", CONFIRM_SCOPE = "edgeai-ai-model-resource-profile-request-v1";
    public static final Set<String> CONTENT_TYPES = Set.of("application/json", "text/plain", "application/octet-stream");
    private ModelProfilingInput() {}

    public record Accelerator(String resourceName, int count) {}
    public record Allocation(long cpuMillicores, long memoryBytes, Accelerator accelerator) {}
    /** HTTP: port..requestTimeoutSeconds set, command null. COMMAND: command/args set, HTTP fields null. */
    public record Load(String type, Integer port, String path, String method, String contentType, String body, String bodyBase64,
                       Integer concurrency, Integer requestTimeoutSeconds, int readinessTimeoutSeconds, List<String> command, List<String> args) {
        public boolean http() { return type.equals("HTTP"); }
        public byte[] bytes() {
            if (body != null) return body.getBytes(StandardCharsets.UTF_8);
            if (bodyBase64 != null) return Base64.getDecoder().decode(bodyBase64);
            return new byte[0];
        }
        public HttpLoad httpLoad() { return new HttpLoad(port, path, method, contentType, bytes(), concurrency, requestTimeoutSeconds); }
    }
    public record Run(String nodeName, Allocation allocation, int durationSeconds, Load load) {
        public Map<String, Object> normalized() {
            var a = AiModelDocuments.ordered("cpuMillicores", allocation.cpuMillicores(), "memoryBytes", allocation.memoryBytes());
            a.put("accelerator", allocation.accelerator() == null ? null
                : AiModelDocuments.ordered("resourceName", allocation.accelerator().resourceName(), "count", allocation.accelerator().count()));
            var l = AiModelDocuments.ordered("type", load.type(), "readinessTimeoutSeconds", load.readinessTimeoutSeconds());
            if (load.http()) {
                l.put("port", load.port()); l.put("path", load.path()); l.put("method", load.method()); l.put("contentType", load.contentType());
                l.put("body", load.body()); l.put("bodyBase64", load.bodyBase64());
                l.put("concurrency", load.concurrency()); l.put("requestTimeoutSeconds", load.requestTimeoutSeconds());
            } else { l.put("command", load.command()); l.put("args", load.args()); }
            return AiModelDocuments.ordered("nodeName", nodeName, "allocation", a, "durationSeconds", durationSeconds, "load", l);
        }
        public String digest() { return JSON.digest(RUN_SCOPE, normalized()); }
    }

    public static Run run(String body) {
        var map = fields(JSON.parse(body, MAX_BYTES), Set.of("nodeName", "allocation", "load"), Set.of("durationSeconds"));
        String node = text(map.get("nodeName"), 253);
        if (!node.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*")) throw new IllegalArgumentException("노드 이름 형식이 아닙니다.");
        var a = fields(map.get("allocation"), Set.of("cpuMillicores", "memoryBytes"), Set.of("accelerator"));
        long cpu = bounded(integer(a.get("cpuMillicores")), MIN_CPU, MAX_CPU, "시험 CPU는 " + MIN_CPU + "–" + MAX_CPU + " millicores입니다.");
        long memory = bounded(integer(a.get("memoryBytes")), MIN_MEMORY, MAX_MEMORY, "시험 메모리는 64MiB–16GiB입니다.");
        Accelerator accelerator = null;
        if (a.get("accelerator") != null) {
            var x = fields(a.get("accelerator"), Set.of("resourceName", "count"), Set.of());
            accelerator = new Accelerator(text(x.get("resourceName"), 253),
                (int) bounded(integer(x.get("count")), MIN_ACCELERATORS, MAX_ACCELERATORS, "시험 가속기 수는 " + MIN_ACCELERATORS + "–" + MAX_ACCELERATORS + "개입니다."));
        }
        int duration = map.get("durationSeconds") == null ? DEFAULT_DURATION
            : (int) bounded(integer(map.get("durationSeconds")), MIN_DURATION, MAX_DURATION, "측정 시간은 " + MIN_DURATION + "–" + MAX_DURATION + "초입니다.");
        return new Run(node, new Allocation(cpu, memory, accelerator), duration, load(map.get("load")));
    }

    static Load load(Object value) {
        var type = text(fields(value, Set.of("type"), Set.of("port", "path", "method", "contentType", "body", "bodyBase64", "concurrency",
            "requestTimeoutSeconds", "readinessTimeoutSeconds", "command", "args")).get("type"), 16);
        var map = object(value);
        int readiness = map.get("readinessTimeoutSeconds") == null ? DEFAULT_READINESS
            : (int) bounded(integer(map.get("readinessTimeoutSeconds")), MIN_READINESS, MAX_READINESS, "준비 대기 시간은 " + MIN_READINESS + "–" + MAX_READINESS + "초입니다.");
        if (type.equals("COMMAND")) {
            fields(value, Set.of("type", "command"), Set.of("args", "readinessTimeoutSeconds"));
            var command = AiModelInput.strings(map.get("command"), "command");
            if (command == null) throw new IllegalArgumentException("벤치마크 명령(command)이 필요합니다.");
            return new Load("COMMAND", null, null, null, null, null, null, null, null, readiness, command, AiModelInput.strings(map.get("args"), "args"));
        }
        if (!type.equals("HTTP")) throw new IllegalArgumentException("부하 방식은 HTTP 또는 COMMAND입니다.");
        fields(value, Set.of("type", "port", "path", "method"), Set.of("contentType", "body", "bodyBase64", "concurrency", "requestTimeoutSeconds", "readinessTimeoutSeconds"));
        int port = (int) bounded(integer(map.get("port")), 1, 65535, "포트는 1–65535입니다.");
        String path = text(map.get("path"), 512);
        if (!path.startsWith("/") || path.contains("#") || path.contains("\\") || path.chars().anyMatch(c -> c <= ' ' || c > '~')
            || Arrays.asList(path.split("[/?]")).contains(".."))
            throw new IllegalArgumentException("요청 경로는 '/'로 시작하는 공백·제어문자·'#'·'..' 없는 ASCII 경로입니다.");
        try { java.net.URI.create("http://127.0.0.1" + path); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("요청 경로가 URI 형식이 아닙니다."); }
        String method = text(map.get("method"), 8);
        if (!Set.of("GET", "POST").contains(method)) throw new IllegalArgumentException("메서드는 GET 또는 POST입니다.");
        String body = map.get("body") == null ? null : string(map.get("body"));
        String base64 = map.get("bodyBase64") == null ? null : string(map.get("bodyBase64"));
        String contentType = map.get("contentType") == null ? null : text(map.get("contentType"), 64);
        if (method.equals("GET")) {
            if (body != null || base64 != null || contentType != null) throw new IllegalArgumentException("GET 요청에는 본문·contentType을 지정하지 않습니다.");
        } else {
            if (contentType == null || !CONTENT_TYPES.contains(contentType)) throw new IllegalArgumentException("contentType은 " + CONTENT_TYPES + " 중 하나입니다.");
            if ((body == null) == (base64 == null)) throw new IllegalArgumentException("POST 요청에는 body 또는 bodyBase64 중 하나가 필요합니다.");
            if (base64 != null && !contentType.equals("application/octet-stream")) throw new IllegalArgumentException("bodyBase64는 application/octet-stream에만 씁니다.");
            if (body != null && contentType.equals("application/octet-stream")) throw new IllegalArgumentException("application/octet-stream 본문은 bodyBase64로 보냅니다.");
            if (base64 != null) {
                try { Base64.getDecoder().decode(base64); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("bodyBase64가 Base64 형식이 아닙니다."); }
            }
        }
        var load = new Load("HTTP", port, path, method, contentType, body, base64,
            map.get("concurrency") == null ? DEFAULT_CONCURRENCY : (int) bounded(integer(map.get("concurrency")), MIN_CONCURRENCY, MAX_CONCURRENCY, "동시 요청 수는 1–16입니다."),
            map.get("requestTimeoutSeconds") == null ? DEFAULT_REQUEST_TIMEOUT
                : (int) bounded(integer(map.get("requestTimeoutSeconds")), MIN_REQUEST_TIMEOUT, MAX_REQUEST_TIMEOUT, "요청 제한 시간은 1–60초입니다."),
            readiness, null, null);
        if (load.bytes().length > BODY_MAX_BYTES) throw new IllegalArgumentException("요청 본문은 64KiB 이하입니다.");
        return load;
    }
    private static String string(Object value) {
        if (!(value instanceof String s) || s.isEmpty() || s.length() > 2 * BODY_MAX_BYTES || s.indexOf(0) >= 0) throw new IllegalArgumentException("본문은 비어 있지 않은 문자열입니다.");
        return s;
    }
    static long bounded(long value, long min, long max, String message) {
        if (value < min || value > max) throw new IllegalArgumentException(message);
        return value;
    }

    /** An action without parameters: an absent body or exactly {}. */
    public static void fieldsOnly(String body) {
        if (body == null || body.isBlank()) return;
        fields(JSON.parse(body, 64), Set.of(), Set.of());
    }

    // ---- confirmation ------------------------------------------------------------------------------------------------

    public enum Basis { RECOMMENDED, MANUAL }
    /** minimum/maximum null = not specified; the server never invents them. */
    public record Quantity(Long minimum, long request, Long maximum, Basis basis) {}
    public record Confirm(UUID runId, Quantity cpu, Quantity memory, List<Accelerator> accelerators, String note) {
        public Map<String, Object> normalized() {
            var map = AiModelDocuments.ordered("runId", runId.toString(), "cpu", quantity(cpu, "Millicores"), "memory", quantity(memory, "Bytes"));
            map.put("accelerators", accelerators.stream().map(a -> (Object) AiModelDocuments.ordered("resourceName", a.resourceName(), "count", a.count(),
                "basis", "TEST_ALLOCATION")).toList());
            map.put("note", note);
            return map;
        }
        public String fingerprint() { return JSON.digest(CONFIRM_SCOPE, normalized()); }
    }
    public static Map<String, Object> quantity(Quantity q, String unit) {
        var map = AiModelDocuments.ordered("minimum" + unit, q.minimum(), "request" + unit, q.request(), "maximum" + unit, q.maximum());
        map.put("basis", q.basis().name());
        return map;
    }

    public static Confirm confirm(String body) {
        var map = fields(JSON.parse(body, 16384), Set.of("runId", "cpu", "memory"), Set.of("accelerators", "note"));
        var cpu = quantity(map.get("cpu"), "Millicores", 1, ModelProfilingSettings.MAX_CPU * 8);
        var memory = quantity(map.get("memory"), "Bytes", 1, 256L << 30);
        var accelerators = new ArrayList<Accelerator>();
        for (Object item : list(orEmpty(map.get("accelerators")), 8)) {
            var a = fields(item, Set.of("resourceName", "count", "basis"), Set.of());
            if (!"TEST_ALLOCATION".equals(a.get("basis")))
                throw new IllegalArgumentException("가속기 요구량은 시험 할당(TEST_ALLOCATION)만 확정할 수 있습니다.");
            accelerators.add(new Accelerator(text(a.get("resourceName"), 253), (int) bounded(integer(a.get("count")), 1, 1024, "가속기 수가 범위를 벗어났습니다.")));
        }
        return new Confirm(WorkflowInput.uuid(map.get("runId")), cpu, memory, List.copyOf(accelerators), map.get("note") == null ? null : text(map.get("note"), 2000));
    }
    static Quantity quantity(Object value, String unit, long min, long max) {
        var q = fields(value, Set.of("request" + unit, "basis"), Set.of("minimum" + unit, "maximum" + unit));
        Basis basis;
        try { basis = Basis.valueOf(text(q.get("basis"), 16)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("요구량 출처는 RECOMMENDED 또는 MANUAL입니다. 실측값 자체는 요구량 출처가 아닙니다."); }
        long request = bounded(integer(q.get("request" + unit)), min, max, "요청량이 범위를 벗어났습니다.");
        Long minimum = q.get("minimum" + unit) == null ? null : bounded(integer(q.get("minimum" + unit)), min, max, "최소량이 범위를 벗어났습니다.");
        Long maximum = q.get("maximum" + unit) == null ? null : bounded(integer(q.get("maximum" + unit)), min, max, "최대량이 범위를 벗어났습니다.");
        if (minimum != null && minimum > request || maximum != null && maximum < request) throw new IllegalArgumentException("최소 ≤ 요청 ≤ 최대여야 합니다.");
        return new Quantity(minimum, request, maximum, basis);
    }
}
