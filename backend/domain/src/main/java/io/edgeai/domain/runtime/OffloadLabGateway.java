package io.edgeai.domain.runtime;

import java.time.Instant;
import java.util.*;

/**
 * Offload test console access to the runtime namespace: one fixed CPU or CUDA GPU load program per session generation and
 * read-only Pod observation. Never accepts an image or command from callers and never touches objects without the session/run labels.
 */
public interface OffloadLabGateway {
    /** NVIDIA extended resources a CUDA load may request, in preference order; the device plugin supplies device and driver. */
    List<String> GPU_RESOURCES=List.of("nvidia.com/gpu.shared","nvidia.com/gpu");
    /** gpuResource: the node's advertised GPU extended resource (server inventory) for a GPU load, null for a CPU load. */
    record Load(UUID sessionId,int generation,String nodeName,int workers,int seconds,String gpuResource) {
        public Load(UUID sessionId,int generation,String nodeName,int workers,int seconds){this(sessionId,generation,nodeName,workers,seconds,null);}
    }
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
