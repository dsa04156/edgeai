package io.edgeai.app.dto;

import io.edgeai.domain.repository.StreamRunRepository.DeviceBinding;
import io.edgeai.domain.stream.*;
import java.time.Instant;
import java.util.UUID;

/** Public provenance and progress only; MQTT credentials and signed URLs are never included. */
public record StreamRouteResponse(UUID id,UUID runId,UUID componentId,UUID sourceTaskId,UUID sourceDeviceId,
        UUID sourceProfileVersionId,String sourceMode,String sourcePort,UUID consumerTaskId,String consumerPort,
        String mediaType,int maxPayloadBytes,UUID sourceSessionId,Long sourceEpoch,Generation generation) {
    public record Generation(UUID id,long number,String state,UUID producerId,long producerEpoch,
            UUID consumerAttemptId,long consumerEpoch,Instant leaseUntil,String fenceReason,Instant closedAt){}
    public static StreamRouteResponse from(DataRoute r,UUID component,DeviceBinding pin,RouteGeneration g){
        return new StreamRouteResponse(r.id(),r.runId(),component,r.sourceTaskId(),r.sourceDeviceId(),r.sourceProfileVersionId(),r.sourceMode(),r.sourcePort(),
            r.consumerTaskId(),r.consumerPort(),r.mediaType(),r.maxPayloadBytes(),pin==null?null:pin.sessionId(),pin==null?null:pin.epoch(),
            g==null?null:new Generation(g.id(),g.generation(),g.state(),g.producer().id(),g.producer().epoch(),g.consumer().id(),g.consumer().epoch(),g.leaseUntil(),g.fenceReason(),g.closedAt()));
    }
}
