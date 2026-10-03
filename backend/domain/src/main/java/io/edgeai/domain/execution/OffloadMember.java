package io.edgeai.domain.execution;

import java.util.*;

/** Frozen stream component membership and checkpoint lineage for one transfer. */
public record OffloadMember(UUID taskId,UUID sourceAttemptId,UUID targetAttemptId,UUID checkpointId,
        UUID targetNodeId,UUID targetVdId,List<String> excludedNodeNames) {
    public OffloadMember { excludedNodeNames=List.copyOf(excludedNodeNames); }
}
