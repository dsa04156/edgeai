package io.edgeai.domain.resourceprofile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CPU/memory recommendation produced by the open-source KRR tool for one exact workload container.
 * Null values mean KRR returned no value ("?" or unset); they are never replaced by zero or a local heuristic.
 * The window is KRR's own history window ending at generation time, which can differ from the measurement window.
 */
public record KrrRecommendation(Status status, String reason, Long cpuRequestMillicores, Long cpuLimitMillicores,
                                Long memoryRequestBytes, Long memoryLimitBytes, String cpuInfo, String memoryInfo,
                                Instant windowStart, Instant windowEnd, int historyHours, int podCount,
                                List<String> warnings, Map<String, Object> provenance) {
    public enum Status { AVAILABLE, PARTIAL, NO_DATA, NOT_FOUND, AMBIGUOUS, UNSUPPORTED, UNAVAILABLE, INVALID_REPORT }
    public KrrRecommendation {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
        for (Long value : new Long[]{cpuRequestMillicores, cpuLimitMillicores, memoryRequestBytes, memoryLimitBytes})
            if (value != null && value <= 0) throw new IllegalArgumentException("KRR values must be positive");
    }
    public static KrrRecommendation failed(Status status, String reason, int historyHours, Map<String, Object> provenance) {
        return new KrrRecommendation(status, reason, null, null, null, null, null, null, null, null, historyHours, 0, List.of(), provenance);
    }
}
