package io.edgeai.domain.runtime;

import java.time.Instant;
import java.util.*;

/**
 * Offload test console access to the runtime namespace: one fixed CPU load program per session generation and read-only Pod
 * observation. Never accepts an image or command from callers and never touches objects without the session/run labels.
 */
public interface OffloadLabGateway {
    record Load(UUID sessionId,int generation,String nodeName,int workers,int seconds) {}
    /** role MODEL (runtime Job Pod of the Run) or LOAD; deletingAt is set once Kubernetes started terminating it. */
    record Pod(String role,String name,UUID uid,String nodeName,String phase,boolean ready,UUID attemptId,Integer generation,
            Instant createdAt,Instant startedAt,Instant deletingAt,String reason) {}
    String loadImage();
    /** Idempotently creates this generation's Job and returns its UID; an existing Job of another owner is a conflict. */
    UUID ensureLoad(Load load);
    /** Deletes this session's load Jobs of generation 1..{@code throughGeneration}; true when none of their Jobs or Pods remain. */
    boolean deleteLoad(UUID sessionId,int throughGeneration);
    List<Pod> pods(UUID sessionId,UUID runId);
    /** Sessions that still own a load Job, for orphan cleanup after restarts. */
    Set<UUID> loadSessions();
}
