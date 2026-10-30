package io.edgeai.app.config;

import io.edgeai.domain.runtime.RuntimeNames;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Server-owned bounds of model resource measurement. Browsers choose a node, a test allocation inside these limits and a
 * sample request; never a namespace, image, YAML, volume or URL.
 */
public record ModelProfilingSettings(boolean enabled, String namespace, String serviceAccount, int maxActiveRuns,
                                     List<String> acceleratorResources, Duration scrapeGrace, Duration collectTimeout,
                                     Duration kubernetesTimeout) {
    public static final int MIN_DURATION = 300, DEFAULT_DURATION = 300, MAX_DURATION = 1800;
    public static final int MIN_CONCURRENCY = 1, DEFAULT_CONCURRENCY = 2, MAX_CONCURRENCY = 16;
    public static final int MIN_REQUEST_TIMEOUT = 1, DEFAULT_REQUEST_TIMEOUT = 10, MAX_REQUEST_TIMEOUT = 60;
    public static final int MIN_READINESS = 30, DEFAULT_READINESS = 300, MAX_READINESS = 900;
    public static final int BODY_MAX_BYTES = 65536;
    public static final long MIN_CPU = 100, MAX_CPU = 8000, MIN_MEMORY = 64L << 20, MAX_MEMORY = 16L << 30;
    public static final int MIN_ACCELERATORS = 1, MAX_ACCELERATORS = 4;
    public static final double MIN_SUCCESS_RATIO = 0.95;
    public static final int MIN_SUCCESSFUL_REQUESTS = 10;
    /** Job deadline beyond readiness + duration: image pull slack and the end of the last request. */
    public static final int DEADLINE_SLACK_SECONDS = 120;

    public ModelProfilingSettings {
        RuntimeNames.dns(namespace, 63);
        RuntimeNames.dns(serviceAccount, 253);
        if (maxActiveRuns < 1 || maxActiveRuns > 16) throw new IllegalArgumentException("max-active-runs must be 1..16");
        acceleratorResources = List.copyOf(acceleratorResources);
        for (String r : acceleratorResources)
            if (!r.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)+/[A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?"))
                throw new IllegalArgumentException("Invalid accelerator resource " + r);
        if (scrapeGrace.isNegative() || scrapeGrace.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("scrape grace must be 0..300 s");
        if (collectTimeout.compareTo(Duration.ofMinutes(1)) < 0 || collectTimeout.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("collect timeout must be 1..60 min");
        if (kubernetesTimeout.compareTo(Duration.ofMinutes(1)) < 0) throw new IllegalArgumentException("kubernetes timeout must be at least 1 min");
    }
    public static List<String> list(String csv) { return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList(); }
}
