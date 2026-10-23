package io.edgeai.app.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Hawkeye video sessions over the published SERVICE workflow with this key (docs/scenarios/hawkeye-warehouse-safety.md). */
@ConfigurationProperties("edgeai.hawkeye-video")
public record HawkeyeVideoProperties(boolean enabled, String workflowKey, int maxWindows, Duration poll) {
    public HawkeyeVideoProperties {
        if (maxWindows < 1 || maxWindows > 1000) throw new IllegalArgumentException("Hawkeye video max-windows must be 1..1000");
        if (poll == null || poll.isNegative() || poll.isZero()) throw new IllegalArgumentException("Hawkeye video poll must be positive");
    }
}
