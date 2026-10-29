package io.edgeai.domain.repository;

import io.edgeai.domain.resourceprofile.*;
import java.util.*;

public interface ResourceProfileRepository {
    void createAnalysis(ResourceAnalysis analysis);
    Optional<ResourceAnalysis> analysis(UUID id);
    /** Serialises revision allocation and idempotency for one SERVICE version inside the caller's transaction. */
    void lockService(UUID serviceProfileVersionId);
    Optional<ResourceProfile> byIdempotencyKey(UUID key);
    int nextRevision(UUID serviceProfileVersionId);
    void create(ResourceProfile profile);
    Optional<ResourceProfile> profile(UUID id);
    Optional<ResourceProfile> revision(UUID serviceProfileVersionId, int revision);
    List<ResourceProfile> list(UUID serviceProfileVersionId, int limit, int offset);
}
