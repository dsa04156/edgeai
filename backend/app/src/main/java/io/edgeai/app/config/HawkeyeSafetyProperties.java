package io.edgeai.app.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Pinned model, sample set and adapter settings of the Hawkeye scenario (docs/scenarios/hawkeye-warehouse-safety.md). */
@ConfigurationProperties("edgeai.hawkeye-safety")
public record HawkeyeSafetyProperties(boolean enabled, String url, Duration timeout, Duration lease, Duration heartbeat,
        Model model, Dataset dataset, List<Sample> samples) {
    public record Model(String name, String ollamaName, String repository, String revision, String license,
            String manifestDigest, String languageSha256, String visionSha256) {}
    public record Dataset(String name, String repository, String revision, String license, String split, String samplesSha256) {}
    public record Sample(String id, String file, String referenceLabel, String sha256) {}

    public Sample sample(String id) {
        return samples.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }
}
