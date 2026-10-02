package io.edgeai.domain.profile;

import java.time.Instant;
import java.util.UUID;

public record ProfileVersion(UUID id, ProfileIdentity identity, String specJson,
                             String digest, Instant createdAt) {}
