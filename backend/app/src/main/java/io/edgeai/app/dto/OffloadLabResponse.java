package io.edgeai.app.dto;

import java.time.Instant;
import java.util.*;

/** Offload test console state. Every value is read from the database, node inventory/Prometheus or Kubernetes; null means unknown. */
public record OffloadLabResponse(boolean enabled,String disabledReason,Model model,String loadImage,Metrics metrics,List<NodeView> nodes,
        Map<String,Object> defaults,Session session) {
    public record Model(String workflowKey,UUID workflowVersionId,String version,String profileKey,String profileVersion,String image,
            List<String> command,List<String> architectures,Map<String,String> resourceRequests,Map<String,Object> taskParameters) {}
    public record Metrics(String status,Instant fetchedAt,int maxAgeSeconds) {}
    /** observedAt belongs to cpuPercent (memory when CPU is absent), gpuObservedAt to gpuPercent; gpuLoadResource from inventory. */
    public record NodeView(UUID id,String name,String role,String architecture,String status,Double cpuPercent,Double memoryPercent,
            Double gpuPercent,Instant observedAt,Instant gpuObservedAt,String gpuLoadResource) {}
    public record Session(UUID id,String state,UUID runId,String runState,UUID edgeNodeId,String edgeNodeName,Object policy,
            Instant createdAt,Instant stoppedAt,UUID taskId,String taskState,String currentNodeName,List<Attempt> attempts,
            List<OffloadOperationResponse> operations,List<Latency> latency,Load load,Pods pods) {}
    public record Attempt(UUID id,int number,String cause,String state,UUID nodeId,String nodeName,String jobName,UUID podUid,
            String desiredState,String observedState,String failureReason,Instant createdAt,Instant updatedAt) {}
    public record Latency(UUID attemptId,long sequence,Long latencyMicros,Instant observedAt) {}
    public record Load(String desired,int generation,Integer workers,Integer seconds,Instant startedAt,Instant expiresAt,UUID jobUid) {}
    public record Pods(String status,Instant observedAt,List<Pod> items) {}
    public record Pod(String role,String name,UUID uid,String nodeName,String phase,boolean ready,UUID attemptId,Integer generation,
            Instant createdAt,Instant startedAt,Instant deletingAt,String reason) {}
}
