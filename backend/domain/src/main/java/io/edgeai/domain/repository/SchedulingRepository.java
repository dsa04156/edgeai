package io.edgeai.domain.repository;
import io.edgeai.domain.scheduling.*;
import java.util.*;
public interface SchedulingRepository {
    void lockProfileKey(UUID key);
    Optional<SchedulingProfile> profileByIdempotencyKey(UUID key);
    void create(SchedulingProfile profile);
    Optional<SchedulingProfile> profile(UUID id);
    void record(SchedulingDecision decision);
    Optional<SchedulingDecision> decision(UUID runId);
}
