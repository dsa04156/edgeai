package io.edgeai.app.dto;

import io.edgeai.domain.vd.VDOperation;
import java.time.Instant;
import java.util.UUID;

public record VDOperationResponse(UUID id,String kind,UUID vdId,long requestedRevision,UUID sourceRuntimeId,
        UUID targetRuntimeId,String state,String reason,Instant createdAt,Instant updatedAt,Instant finishedAt) implements OperationResponse {
    public static VDOperationResponse from(VDOperation value) {
        return new VDOperationResponse(value.id(),"VD_"+value.kind(),value.vdId(),value.requestedRevision(),value.sourceRuntimeId(),
            value.targetRuntimeId(),value.state(),value.reason(),value.createdAt(),value.updatedAt(),value.finishedAt());
    }
}
