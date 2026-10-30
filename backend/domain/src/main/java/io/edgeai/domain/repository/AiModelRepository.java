package io.edgeai.domain.repository;

import io.edgeai.domain.aimodel.AiModelExecution;
import java.util.*;

public interface AiModelRepository {
    /**
     * Serialises registration per Idempotency-Key and per (model key, version, configuration) inside the caller's
     * transaction, always in that order so two registrations cannot wait on each other.
     */
    void lock(UUID idempotencyKey, String modelKey, String modelVersion, String configName);
    Optional<AiModelExecution> byIdempotencyKey(UUID key);
    Optional<AiModelExecution> byIdentity(String modelKey, String modelVersion, String configName);
    void create(AiModelExecution execution);
    Optional<AiModelExecution> find(UUID id);
    /** Newest first; {@code query} matches key, display name, version or repository case-insensitively. */
    List<AiModelExecution> list(String query, int limit, int offset);
}
