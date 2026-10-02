package io.edgeai.domain.remote;
import java.time.Instant;
import java.util.*;
/** Immutable specification and artifact metadata; no credentials, URLs or inline artifact data. */
public record RemoteWork(RemoteIdentity identity,String serviceSpecJson,String parametersJson,List<RemoteFile> inputs,Instant expiresAt) {
    public RemoteWork {
        Objects.requireNonNull(identity);Objects.requireNonNull(expiresAt);
        if(serviceSpecJson==null || parametersJson==null || serviceSpecJson.length()>65536 || parametersJson.length()>65536)
            throw new IllegalArgumentException("Bounded execution JSON required");
        inputs=List.copyOf(inputs);
        if(inputs.size()>16 || inputs.stream().map(RemoteFile::port).distinct().count()!=inputs.size())throw new IllegalArgumentException("Unique bounded input ports required");
    }
}
