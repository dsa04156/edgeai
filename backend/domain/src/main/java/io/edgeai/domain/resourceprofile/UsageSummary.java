package io.edgeai.domain.resourceprofile;

import java.time.Instant;

/**
 * Observed distribution of one metric in {@code unit}. Values are null unless {@code status} is AVAILABLE or STALE;
 * the maximum is bounded by the scrape/step resolution and is not a guaranteed peak.
 */
public record UsageSummary(ObservationStatus status, String unit, Double min, Double average, Double p95, Double max,
                           int samples, Instant firstSample, Instant lastSample, String reason) {
    public UsageSummary {
        if (status == null || unit == null) throw new IllegalArgumentException("Status and unit are required");
        boolean valued = status == ObservationStatus.AVAILABLE || status == ObservationStatus.STALE;
        for (Double value : new Double[]{min, average, p95, max}) {
            if (valued && (value == null || !Double.isFinite(value) || value < 0)) throw new IllegalArgumentException("Observed values must be finite and non-negative");
            if (!valued && value != null) throw new IllegalArgumentException("Unavailable observations carry no values");
        }
        if (valued && !(min <= p95 && p95 <= max && min <= average && average <= max)) throw new IllegalArgumentException("Inconsistent observed distribution");
    }
    public static UsageSummary missing(ObservationStatus status, String unit, int samples, String reason) {
        return new UsageSummary(status, unit, null, null, null, null, samples, null, null, reason);
    }
}
