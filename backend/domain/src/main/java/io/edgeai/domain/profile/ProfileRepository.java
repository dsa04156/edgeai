package io.edgeai.domain.profile;

import java.util.List;
import java.util.Optional;

public interface ProfileRepository {
    Publication publish(ProfileIdentity identity, String canonicalSpec, String digest);
    Optional<ProfileVersion> find(ProfileIdentity identity);
    List<ProfileVersion> list(ProfileIdentity.Kind kind, String key, int limit, int offset);
    record Publication(ProfileVersion version, boolean created) {}
}
