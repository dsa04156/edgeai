package io.edgeai.domain.remote;
import java.time.Instant;
import java.util.*;

/** Exact, bounded start authority; the provider journals acceptance before launching computation. */
public record RemoteStart(RemoteIdentity identity,String requestDigest,Instant expiresAt,UUID offloadId,Instant startDeadline) {
    public RemoteStart {
        Objects.requireNonNull(identity);Objects.requireNonNull(expiresAt);
        if(requestDigest==null || !requestDigest.matches("sha256:[a-f0-9]{64}") || (offloadId==null)!=(startDeadline==null))
            throw new IllegalArgumentException("Exact work digest and paired transfer authority required");
    }
}
