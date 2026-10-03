package io.edgeai.domain.stream;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable per-actor observations; retries do not constitute new liveness. */
public record RouteHeartbeat(UUID generationId,long windowMicros,long producerSequence,long consumerSequence,
        Instant producerSeen,Instant consumerSeen) {
    public RouteHeartbeat {
        Objects.requireNonNull(generationId);Objects.requireNonNull(producerSeen);Objects.requireNonNull(consumerSeen);
        if(windowMicros<5_000_000 || windowMicros>120_000_000 || producerSequence<0 || consumerSequence<0
                || producerSequence>9007199254740991L || consumerSequence>9007199254740991L)
            throw new IllegalArgumentException("Invalid stream heartbeat state");
    }
    public long sequence(boolean producer){return producer?producerSequence:consumerSequence;}
    public RouteHeartbeat observe(boolean producer,long sequence,Instant now){
        if(sequence!=sequence(producer)+1 || now.isBefore(producer?producerSeen:consumerSeen))
            throw new IllegalArgumentException("Invalid stream heartbeat observation");
        return new RouteHeartbeat(generationId,windowMicros,producer?sequence:producerSequence,producer?consumerSequence:sequence,
            producer?now:producerSeen,producer?consumerSeen:now);
    }
}
