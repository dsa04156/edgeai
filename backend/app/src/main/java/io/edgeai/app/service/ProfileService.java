package io.edgeai.app.service;

import io.edgeai.app.exception.ProfileConflictException;
import io.edgeai.app.exception.ProfileNotFoundException;
import io.edgeai.app.support.ProfileJson;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.profile.ProfileVersion;
import io.edgeai.domain.repository.ProfileRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final ProfileRepository repository;
    private final ProfileJson json;
    public ProfileService(ProfileRepository repository, ProfileJson json) {
        this.repository = repository; this.json = json;
    }
    @Transactional
    public ProfileRepository.Publication publish(ProfileIdentity.Kind kind, String body) {
        var parsed = json.parse(kind, body);
        var result = repository.publish(parsed.identity(), parsed.spec(), parsed.digest());
        if (!result.version().digest().equals(parsed.digest())) throw new ProfileConflictException();
        return result;
    }
    public ProfileVersion find(ProfileIdentity identity) { return repository.find(identity).orElseThrow(ProfileNotFoundException::new); }
    @Transactional
    public void delete(ProfileIdentity identity) {
        try {
            if (!repository.delete(identity)) throw new ProfileNotFoundException();
        } catch (org.springframework.dao.DataIntegrityViolationException used) {
            throw new io.edgeai.app.exception.ProfileInUseException();
        }
    }
    public List<ProfileVersion> list(ProfileIdentity.Kind kind, String key, int limit, int offset) {
        if (key != null) ProfileIdentity.validateKey(key);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 1000000)
            throw new IllegalArgumentException("limit must be 1–100 and offset 0–1000000");
        return repository.list(kind, key, limit + 1, offset);
    }
}
