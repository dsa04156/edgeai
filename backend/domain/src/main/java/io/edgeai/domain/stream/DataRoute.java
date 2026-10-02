package io.edgeai.domain.stream;

import io.edgeai.domain.profile.ProfileIdentity;
import java.time.Instant;
import java.util.*;

/** Immutable logical input connection. Runtime actors belong to RouteGeneration. */
public record DataRoute(UUID id,UUID runId,UUID sourceTaskId,UUID sourceDeviceId,UUID sourceProfileVersionId,
        String sourceMode,String sourcePort,UUID consumerTaskId,String consumerPort,String mediaType,int maxPayloadBytes,Instant createdAt) {
    public DataRoute {
        Objects.requireNonNull(id);Objects.requireNonNull(runId);Objects.requireNonNull(sourceProfileVersionId);
        Objects.requireNonNull(consumerTaskId);Objects.requireNonNull(createdAt);
        if((sourceTaskId==null)==(sourceDeviceId==null) || consumerTaskId.equals(sourceTaskId))throw new IllegalArgumentException("One distinct stream source required");
        if(sourceDeviceId!=null ? sourceMode==null || !Set.of("LIVE","REPLAY","SYNTHETIC").contains(sourceMode) : sourceMode!=null)
            throw new IllegalArgumentException("Stream source mode mismatch");
        ProfileIdentity.validateKey(sourcePort);ProfileIdentity.validateKey(consumerPort);
        if(mediaType==null || mediaType.length()>127 || !mediaType.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")
            || maxPayloadBytes<1 || maxPayloadBytes>262144)throw new IllegalArgumentException("Invalid stream media or size budget");
    }
    public boolean deviceSource(){return sourceDeviceId!=null;}
}
