package io.edgeai.app.config;

import io.edgeai.domain.resourceprofile.WorkloadTarget;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Server-owned analysis scope: browsers choose among these namespaces, never URLs or queries. */
public record ResourceProfilingSettings(List<String> namespaces, String krrVersion, Duration observationBudget, Duration totalBudget) {
    public static final Duration MIN_WINDOW = Duration.ofMinutes(5);
    public static final Duration MAX_WINDOW = Duration.ofDays(14);
    public ResourceProfilingSettings {
        namespaces = List.copyOf(namespaces);
        namespaces.forEach(WorkloadTarget::namespace);
        if (observationBudget.isNegative() || observationBudget.isZero() || totalBudget.compareTo(observationBudget) < 0 || totalBudget.compareTo(Duration.ofSeconds(200)) > 0)
            throw new IllegalArgumentException("Analysis budgets require 0 < observation <= total <= 200 s (console proxy waits 210 s)");
    }
    public static ResourceProfilingSettings parse(String namespaces, String krrVersion, int observationSeconds, int totalSeconds) {
        return new ResourceProfilingSettings(Arrays.stream(namespaces.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList(), krrVersion,
            Duration.ofSeconds(observationSeconds), Duration.ofSeconds(totalSeconds));
    }
}
