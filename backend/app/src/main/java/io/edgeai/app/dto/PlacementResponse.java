package io.edgeai.app.dto;

import io.edgeai.domain.execution.Task;
import io.edgeai.domain.execution.TaskAttempt;
import io.edgeai.domain.runtime.RuntimeInstance;
import java.time.Instant;
import java.util.UUID;

/** Public placement projection. Claim nonces and runtime credentials are never serialized. */
public record PlacementResponse(UUID taskId, String taskKey, String taskState,
        TaskAttemptResponse attempt, RuntimePlacement runtime) {
    public static PlacementResponse from(Task task, TaskAttempt attempt, RuntimeInstance runtime) {
        return new PlacementResponse(task.id(), task.key(), task.state(),
            attempt == null ? null : TaskAttemptResponse.from(attempt),
            runtime == null ? null : new RuntimePlacement(runtime.id(), runtime.namespace(), runtime.jobName(),
                runtime.nodeName(), runtime.producerPodUid(), runtime.desiredState(), runtime.observedState(),
                runtime.failureReason(), runtime.updatedAt()));
    }
    public record RuntimePlacement(UUID id, String namespace, String jobName, String nodeName,
            UUID podUid, String desiredState, String observedState, String failureReason, Instant updatedAt) {}
}
