package io.edgeai.domain.stream;

import io.edgeai.domain.runtime.RuntimeNames;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** The original immutable checkpoint receipt; raw state and frames remain in its fixed S3 version. */
public record StreamCheckpointAuthority(UUID id,UUID runId,String namespace,String documentJson) {
    public static final int MAX_BYTES=128*1024;
    public StreamCheckpointAuthority {
        Objects.requireNonNull(id);Objects.requireNonNull(runId);RuntimeNames.dns(namespace,63);
        if(documentJson==null || documentJson.isBlank() || documentJson.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalArgumentException("Invalid original checkpoint authority size");
    }
    public String objectKey(){return "authority/stream-checkpoint/"+id+".json";}
}
