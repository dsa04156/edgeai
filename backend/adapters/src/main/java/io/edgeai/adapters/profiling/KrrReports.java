package io.edgeai.adapters.profiling;

import io.edgeai.domain.resourceprofile.KrrRecommendation;
import io.edgeai.domain.resourceprofile.KrrRecommendation.Status;
import io.edgeai.domain.resourceprofile.WorkloadTarget;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * Reads the JSON report of KRR (robusta-dev/krr, `krr simple -f json`, v1.31.1 Result model): each scan names
 * object.{namespace,kind,name,container}; recommended.{requests,limits}.{cpu,memory}.value is a number (CPU cores,
 * memory bytes), "?" when KRR could not compute it, or null when KRR leaves it unset (simple: CPU limit).
 * Only the scan whose identity equals the requested target is used; anything else is reported, not guessed.
 */
public final class KrrReports {
    private KrrReports() {}

    public static KrrRecommendation parse(JsonNode report, WorkloadTarget target, Instant generatedAt, Map<String, Object> bridgeProvenance) {
        var provenance = new TreeMap<String, Object>(bridgeProvenance);
        provenance.put("tool", "krr");
        int requestedHours = ((Number) bridgeProvenance.getOrDefault("requestedHistoryHours", 0)).intValue();
        if (report == null || !report.isObject() || !report.path("scans").isArray() || !report.path("strategy").isObject())
            return KrrRecommendation.failed(Status.INVALID_REPORT, "KRR 보고서 형식이 예상과 다릅니다.", requestedHours, provenance);
        var strategy = report.path("strategy");
        provenance.put("strategy", strategy.path("name").asText());
        var settings = new TreeMap<String, Object>();
        strategy.path("settings").properties().forEach(e -> settings.put(e.getKey(), scalar(e.getValue())));
        provenance.put("strategySettings", settings);
        var config = report.path("config");
        if (config.isObject()) {
            if (config.has("cpu_min_value")) provenance.put("cpuMinMillicores", config.path("cpu_min_value").asLong());
            if (config.has("memory_min_value")) provenance.put("memoryMinMegabytes", config.path("memory_min_value").asLong());
        }
        var errors = new ArrayList<String>();
        for (var error : report.path("errors")) errors.add(error.path("name").asText("unknown"));
        provenance.put("reportErrors", errors);
        double hours = strategy.path("settings").path("history_duration").asDouble(requestedHours);
        int historyHours = (int) Math.ceil(hours);
        Instant windowStart = generatedAt.minus(Duration.ofSeconds((long) (hours * 3600)));

        var matches = new ArrayList<JsonNode>();
        for (var scan : report.path("scans")) {
            var object = scan.path("object");
            if (object.path("namespace").asText().equals(target.namespace()) && object.path("kind").asText().equals(target.kind().name())
                && object.path("name").asText().equals(target.name()) && object.path("container").asText().equals(target.container()))
                matches.add(scan);
        }
        provenance.put("scannedObjects", report.path("scans").size());
        if (matches.isEmpty())
            return new KrrRecommendation(Status.NOT_FOUND, "KRR 결과에 대상 워크로드·컨테이너가 없습니다.", null, null, null, null, null, null,
                windowStart, generatedAt, historyHours, 0, errors, provenance);
        if (matches.size() > 1)
            return new KrrRecommendation(Status.AMBIGUOUS, "KRR 결과에 같은 대상이 여러 개 있어 하나로 정할 수 없습니다.", null, null, null, null, null, null,
                windowStart, generatedAt, historyHours, 0, errors, provenance);
        var scan = matches.getFirst();
        var recommended = scan.path("recommended");
        try {
            Long cpuRequest = value(recommended.path("requests").path("cpu"), 1000);
            Long cpuLimit = value(recommended.path("limits").path("cpu"), 1000);
            Long memoryRequest = value(recommended.path("requests").path("memory"), 1);
            Long memoryLimit = value(recommended.path("limits").path("memory"), 1);
            String cpuInfo = text(recommended.path("info").path("cpu"));
            String memoryInfo = text(recommended.path("info").path("memory"));
            int pods = 0;
            for (var pod : scan.path("object").path("pods")) if (!pod.path("deleted").asBoolean(false)) pods++;
            var warnings = new ArrayList<String>();
            for (var warning : scan.path("object").path("warnings")) warnings.add(warning.asText());
            warnings.addAll(errors);
            Status status = cpuRequest != null && memoryRequest != null ? Status.AVAILABLE : cpuRequest != null || memoryRequest != null ? Status.PARTIAL : Status.NO_DATA;
            String reason = status == Status.AVAILABLE ? null
                : "KRR이 값을 계산하지 않았습니다(cpu: " + Objects.requireNonNullElse(cpuInfo, "?") + ", memory: " + Objects.requireNonNullElse(memoryInfo, "?") + ").";
            provenance.put("severity", scan.path("severity").asText());
            return new KrrRecommendation(status, reason, cpuRequest, cpuLimit, memoryRequest, memoryLimit, cpuInfo, memoryInfo,
                windowStart, generatedAt, historyHours, pods, warnings, provenance);
        } catch (IllegalArgumentException invalid) {
            return KrrRecommendation.failed(Status.INVALID_REPORT, "KRR 추천값이 유효한 수가 아닙니다.", historyHours, provenance);
        }
    }

    /** {"value": number|"?"|null, "severity": ...} or a bare value; "?"/null are absent, never zero. */
    static Long value(JsonNode node, int scale) {
        JsonNode value = node.isObject() ? node.path("value") : node;
        if (value.isMissingNode() || value.isNull()) return null;
        if (value.isTextual()) {
            if (value.asText().equals("?")) return null;
            throw new IllegalArgumentException("Unexpected KRR value");
        }
        if (!value.isNumber()) throw new IllegalArgumentException("Unexpected KRR value");
        double number = value.asDouble();
        if (!Double.isFinite(number) || number <= 0) throw new IllegalArgumentException("Unexpected KRR value");
        // CPU cores -> millicores and memory bytes are rounded up so a recommendation is never understated.
        return new BigDecimal(value.asText()).multiply(BigDecimal.valueOf(scale)).setScale(0, RoundingMode.CEILING).longValueExact();
    }
    private static String text(JsonNode node) { return node.isTextual() ? node.asText() : null; }
    private static Object scalar(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.asBoolean();
        return node.isNull() ? null : node.asText();
    }
}
