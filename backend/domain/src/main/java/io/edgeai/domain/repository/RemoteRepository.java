package io.edgeai.domain.repository;
import io.edgeai.domain.remote.*;
import java.time.Instant;
import java.util.*;
/** Mutations require the parent Run transaction lock. */
public interface RemoteRepository {
    Optional<RemoteAllocation> find(UUID id);
    void create(RemoteAllocation allocation);
    void observe(UUID id,long revision,String state,String observationJson,Instant now);
}
