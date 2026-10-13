package io.edgeai.app.support;

import io.edgeai.domain.profile.ProfileIdentity;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class ProfileJson {
    private final JsonDocuments documents = new JsonDocuments();
    public Parsed parse(ProfileIdentity.Kind kind, String body) {
        Object value = documents.parse(body, 65536);
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("key", "version", "spec")))
            throw new IllegalArgumentException("body requires exactly key, version and spec");
        if (!(map.get("key") instanceof String key) || !(map.get("version") instanceof String version))
            throw new IllegalArgumentException("key and version must be strings");
        var identity = new ProfileIdentity(kind, key, version);
        if (!(map.get("spec") instanceof Map<?, ?> spec) || spec.isEmpty())
            throw new IllegalArgumentException("spec must be a nonempty JSON object");
        return new Parsed(identity, documents.boundedCanonical(spec, 65536), documents.digest("edgeai-profile-v1\n" + kind.name(), spec));
    }
    public Object decode(String json) { return documents.decode(json); }
    public record Parsed(ProfileIdentity identity, String spec, String digest) {}
}
