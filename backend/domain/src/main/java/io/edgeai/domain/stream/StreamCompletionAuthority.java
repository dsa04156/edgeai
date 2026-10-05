package io.edgeai.domain.stream;

import io.edgeai.domain.runtime.RuntimeNames;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Immutable database snapshot of one original, sealed connected component. */
public record StreamCompletionAuthority(UUID id,UUID runId,String namespace,Instant grantedAt,String documentJson) {
    public static final int MAX_BYTES=16*1024*1024;
    public StreamCompletionAuthority {
        Objects.requireNonNull(id);Objects.requireNonNull(runId);Objects.requireNonNull(grantedAt);
        RuntimeNames.dns(namespace,63);
        if(documentJson==null || documentJson.isBlank() || documentJson.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalArgumentException("Invalid stream completion authority size");
    }
    public String objectKey(){return "authority/stream-completion/"+id+".json";}
}
