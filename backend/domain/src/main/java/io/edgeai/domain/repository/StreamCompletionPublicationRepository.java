package io.edgeai.domain.repository;
import io.edgeai.domain.stream.StreamCompletionAuthority;
import java.time.*;
import java.util.*;
public interface StreamCompletionPublicationRepository {
    record Lease(UUID id,UUID owner) {}
    Optional<StreamCompletionAuthority> find(UUID id);
    Optional<StreamCompletionAuthority> forAttempt(UUID attempt);
    Optional<StreamCompletionAuthority> forGeneration(UUID generation);
    Optional<Lease> lease(String namespace,UUID owner,Instant now,Duration duration);
    boolean finish(UUID id,UUID owner,Instant now);
    boolean defer(UUID id,UUID owner,Instant availableAt,Instant now);
}
