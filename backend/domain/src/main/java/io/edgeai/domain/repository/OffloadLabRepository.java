package io.edgeai.domain.repository;

import io.edgeai.domain.execution.OffloadLabSession;
import java.time.Instant;
import java.util.*;

public interface OffloadLabRepository {
    /** false when another session is still open (single open session) or the key already exists. */
    boolean create(OffloadLabSession session);
    Optional<OffloadLabSession> find(UUID id,boolean lock);
    Optional<OffloadLabSession> byKey(UUID key);
    /** The open session if any, otherwise the most recently created one. */
    Optional<OffloadLabSession> latest();
    List<UUID> unfinished();
    void startLoad(UUID id,int generation,UUID key,String digest,int workers,int seconds,Instant now);
    void loadSubmitted(UUID id,int generation,UUID jobUid);
    /** Only when {@code generation} is still the current one. */
    void stopLoad(UUID id,int generation,Instant now);
    void stopping(UUID id,Instant now);
    void stopped(UUID id,Instant now);
}
