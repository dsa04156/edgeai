package io.edgeai.app.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hawkeye video sessions over the published SERVICE workflow with this key (docs/scenarios/hawkeye-warehouse-safety.md).
 * observerUrl is the continuous motion observer used by snapshot versions (empty: those versions cannot start);
 * observerLease is how long the observer keeps reading without a renewal from this API.
 */
@ConfigurationProperties("edgeai.hawkeye-video")
public record HawkeyeVideoProperties(boolean enabled, String workflowKey, int maxWindows, Duration poll, String observerUrl, Duration observerLease) {
    public HawkeyeVideoProperties {
        if (observerLease == null) observerLease = Duration.ofSeconds(15);
        if (observerLease.compareTo(Duration.ofSeconds(5)) < 0 || observerLease.compareTo(Duration.ofSeconds(120)) > 0)
            throw new IllegalArgumentException("Hawkeye video observer-lease must be 5..120 seconds");
        if (maxWindows < 1 || maxWindows > 1000) throw new IllegalArgumentException("Hawkeye video max-windows must be 1..1000");
        if (poll == null || poll.isNegative() || poll.isZero()) throw new IllegalArgumentException("Hawkeye video poll must be positive");
    }
}
