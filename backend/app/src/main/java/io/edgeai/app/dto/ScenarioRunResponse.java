package io.edgeai.app.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** snapshot and comparison are present on run detail only; snapshot stays null until both runtimes were verified. */
public record ScenarioRunResponse(UUID id, String state, int requestedCount, int dispatchedCount, Instant createdAt,
        Instant startedAt, Instant stopRequestedAt, Instant finishedAt, String failureCode, String failureMessage,
        Snapshot snapshot, Comparison comparison) {
    public record Snapshot(Dataset dataset, List<Target> targets) {}
    public record Dataset(String kind, String preprocessing, String manifestSha256, String inputsSha256, int imageCount) {}
    public record Target(String id, String modelName, String modelSha256, String nodeName, String deviceName, String provider,
            String precision, String runtime) {}
    /**
     * Per-target aggregates over every recorded image, plus paired figures over images that succeeded on both
     * targets. Compute figures use the runtime-measured time of successful calls (preprocessing, transfer and blocking
     * inference inside the runtime) and meanRoundTripMs the API-measured time of successful calls; callWallSeconds sums
     * the API-measured duration of every sequential HTTP call to that target, failed and timed-out ones included.
     * runWallSeconds runs from start to finish (or to now while active); both targets are called concurrently per image.
     * pairedLabeledCorrect is null when the dataset labels are not configured as trustworthy.
     */
    public record Comparison(List<TargetSummary> targets, int pairedCount, int top1AgreementCount, boolean labelsTrusted,
            Double runWallSeconds, Double pairedImagesPerRunWallSecond) {}
    public record TargetSummary(String target, int processed, int succeeded, int failed, Double meanInferenceMs,
            Double p50InferenceMs, Double p95InferenceMs, Double meanRoundTripMs, Double computeImagesPerSecond,
            double callWallSeconds, Double callWallImagesPerSecond, Integer pairedLabeledCorrect) {}
}
