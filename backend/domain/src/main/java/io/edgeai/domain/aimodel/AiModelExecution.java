package io.edgeai.domain.aimodel;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable catalog entry: one execution configuration (image digest + platform + resource requirements) of one
 * model version. A new version or configuration is a new entry; nothing here starts, schedules or reserves anything.
 */
public record AiModelExecution(UUID id, String modelKey, String modelVersion, String configName, String displayName,
                               String repository, String imageDigest, UUID resourceProfileId, String measurementStatus,
                               UUID idempotencyKey, String requestFingerprint, String digest, String documentJson, Instant createdAt) {
    public static final String KEY = "[a-z][a-z0-9._-]{0,99}";
    public static final String VERSION = "[A-Za-z0-9][A-Za-z0-9._+-]{0,63}";
    public static final String CONFIG = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?";
    public static final String DEFAULT_CONFIG = "default";

    public AiModelExecution {
        if (modelKey == null || !modelKey.matches(KEY)) throw new IllegalArgumentException("Invalid model key");
        if (modelVersion == null || !modelVersion.matches(VERSION)) throw new IllegalArgumentException("Invalid model version");
        if (configName == null || !configName.matches(CONFIG)) throw new IllegalArgumentException("Invalid execution configuration name");
        ImageReference.repository(repository);
        ImageReference.digest(imageDigest);
    }
}
