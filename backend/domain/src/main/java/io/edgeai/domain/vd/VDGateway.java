package io.edgeai.domain.vd;

import java.util.*;

public interface VDGateway extends AutoCloseable {
    UUID ensurePod(VDRuntime runtime,Map<String,Object> pod,String token);
    PodIdentity authenticatePod(VDRuntime runtime,String token);
    boolean stop(VDRuntime runtime);
    Snapshot listPods();
    String watchPods(String resourceVersion);
    record PodIdentity(UUID podUid,UUID nodeUid,String nodeName,boolean ready) {}
    record Observation(UUID runtimeId,UUID vdId,long generation,String name,UUID podUid,String phase,boolean terminating,boolean ready) {}
    record Snapshot(Map<UUID,Observation> pods,String resourceVersion) { public Snapshot { pods=Map.copyOf(pods); } }
    @Override void close();
}
