package io.edgeai.domain.resourceprofile;

import java.time.Instant;
import java.util.UUID;

/** Server-produced, immutable measurement/recommendation evidence that a saved profile may cite. */
public record ResourceAnalysis(UUID id, UUID serviceProfileVersionId, String documentJson, Instant createdAt) {}
