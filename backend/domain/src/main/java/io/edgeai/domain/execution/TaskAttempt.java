package io.edgeai.domain.execution;
import java.time.Instant;
import java.util.*;
public record TaskAttempt(UUID id, UUID taskId, int number, long epoch, String state, String mode,UUID nodeId,String cause,List<String> excludedNodeNames,Instant createdAt,Instant updatedAt) {
    public TaskAttempt { excludedNodeNames=List.copyOf(excludedNodeNames); }
}
