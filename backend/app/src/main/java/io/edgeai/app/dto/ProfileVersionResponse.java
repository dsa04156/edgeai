package io.edgeai.app.dto;

import io.edgeai.domain.profile.ProfileIdentity;
import java.time.Instant;
import java.util.UUID;

public record ProfileVersionResponse(UUID id, ProfileIdentity.Kind kind, String key, String version,
                                     Object spec, String digest, Instant createdAt) {}
