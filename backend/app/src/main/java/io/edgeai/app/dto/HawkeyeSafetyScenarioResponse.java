package io.edgeai.app.dto;

import io.edgeai.app.config.HawkeyeSafetyProperties;
import java.util.List;
import java.util.UUID;

/** Pinned model and samples plus the adapter's current self-report; reported is null when the adapter cannot be read. */
public record HawkeyeSafetyScenarioResponse(String id, boolean enabled, List<String> categories, Model model, Dataset dataset,
        List<HawkeyeSafetyProperties.Sample> samples, Runtime runtime, UUID activeRunId) {
    public record Model(String name, String ollamaName, String repository, String revision, String license, String manifestDigest,
            String languageSha256, String visionSha256) {}
    public record Dataset(String name, String repository, String revision, String license, String split, boolean synthetic,
            String samplesSha256) {}
    /** status: READY, NOT_READY, MISMATCH (ready but another model or sample set) or UNAVAILABLE; null runtime when disabled. */
    public record Runtime(String status, Reported reported, String error) {}
    public record Reported(String nodeName, String model, String modelDigest, String ollamaVersion, String processor,
            Long sizeBytes, Long sizeVramBytes) {}
}
