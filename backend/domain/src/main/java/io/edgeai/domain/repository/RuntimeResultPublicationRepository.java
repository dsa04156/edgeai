package io.edgeai.domain.repository;
import java.time.*;
import java.util.*;
public interface RuntimeResultPublicationRepository {
    record Publication(UUID resultId,UUID runtimeId,UUID leaseOwner) {}
    Optional<Publication> lease(String namespace,UUID owner,Instant now,Duration duration);
    boolean finish(UUID resultId,UUID owner,Instant now);
    boolean defer(UUID resultId,UUID owner,Instant availableAt,Instant now);
}
