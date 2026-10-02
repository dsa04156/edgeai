package io.edgeai.domain.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** Ephemeral credentials for the Runner only: never persisted as public artifact metadata. */
public record ArtifactGrant(URI url, Map<String, String> headers, Instant expiresAt) {
    public ArtifactGrant { headers = Map.copyOf(headers); }
    @Override public String toString() { return "ArtifactGrant{url=REDACTED, headers=REDACTED, expiresAt=" + expiresAt + "}"; }
}
