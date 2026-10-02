package io.edgeai.domain.runtime;
import java.util.*;
public interface RuntimeGateway extends AutoCloseable {
    UUID ensureJob(RuntimeInstance runtime,Map<String,Object> job,String claimToken);
    RuntimePod authenticatePod(RuntimeInstance runtime,String podToken);
    boolean stop(RuntimeInstance runtime);
    Snapshot listJobs();
    String watchJobs(String resourceVersion);
    @Override void close();
    record JobObservation(UUID attemptId,UUID taskId,UUID runId,long epoch,String name,UUID jobUid,String state) {}
    record Snapshot(Map<UUID,JobObservation> jobs,String resourceVersion) { public Snapshot { jobs=Map.copyOf(jobs); } }
}
