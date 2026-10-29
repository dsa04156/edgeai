package io.edgeai.app.config;

import io.edgeai.domain.resourceprofile.WorkloadTarget;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Server-owned analysis scope: browsers choose among these namespaces, never URLs or queries. */
public record ResourceProfilingSettings(List<String> namespaces, String krrVersion) {
    public static final Duration MIN_WINDOW = Duration.ofMinutes(5);
    public static final Duration MAX_WINDOW = Duration.ofDays(14);
    public ResourceProfilingSettings {
        namespaces = List.copyOf(namespaces);
        namespaces.forEach(WorkloadTarget::namespace);
    }
    public static ResourceProfilingSettings parse(String namespaces, String krrVersion) {
        return new ResourceProfilingSettings(Arrays.stream(namespaces.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList(), krrVersion);
    }
}
