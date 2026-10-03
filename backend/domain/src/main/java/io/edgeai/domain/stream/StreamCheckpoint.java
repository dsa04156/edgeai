package io.edgeai.domain.stream;

import io.edgeai.domain.storage.*;
import java.time.Instant;
import java.util.*;

/** Immutable verified object identity and bounded control metadata; never raw stream data. */
public record StreamCheckpoint(UUID id,UUID runId,UUID taskId,UUID attemptId,UUID runtimeId,long epoch,
        UUID producerPodUid,UUID serviceProfileVersionId,Request request,long revision,String summaryJson,
        VerifiedArtifact artifact,Instant createdAt) {
    public static final long MAX_BYTES=72L*1024*1024;
    public static final String MEDIA_TYPE="application/vnd.edgeai.stream-checkpoint+json";
    public record Request(UUID previousId,long serial,String sha256,long bytes,String executionSha256,List<UUID> generationIds) {
        public Request {
            if(serial<0 || serial>9007199254740991L || bytes<1 || bytes>MAX_BYTES)throw new IllegalArgumentException("Invalid checkpoint budget or serial");
            digest(sha256);digest(executionSha256);
            if(generationIds==null || generationIds.isEmpty() || generationIds.size()>32 || generationIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(generationIds).size()!=generationIds.size())throw new IllegalArgumentException("Invalid checkpoint generation set");
            generationIds=generationIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        }
        public ArtifactContent content(UUID task,UUID attempt){return new ArtifactContent(task,attempt,"stream-checkpoint",sha256,bytes,MEDIA_TYPE);}
    }
    private static void digest(String value){if(value==null || !value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid checkpoint digest");}
}
