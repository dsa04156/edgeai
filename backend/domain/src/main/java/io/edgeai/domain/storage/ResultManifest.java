package io.edgeai.domain.storage;
import io.edgeai.domain.runtime.RuntimeNames;
import java.util.*;
/** Runner declarations are untrusted until ArtifactStore verifies every immutable object version. */
public record ResultManifest(List<Output> outputs) {
    public ResultManifest {
        outputs=outputs.stream().sorted(Comparator.comparing(Output::port)).toList();
        if(outputs.isEmpty() || outputs.size()>16 || outputs.stream().map(Output::port).distinct().count()!=outputs.size())
            throw new IllegalArgumentException("Result requires 1..16 unique output ports");
    }
    public record Output(String port, long bytes, String sha256, String mediaType, String versionId) {
        public Output {
            RuntimeNames.port(port);
            new ArtifactContent(new UUID(0,0),new UUID(0,0),port,sha256,bytes,mediaType);
            if(versionId==null || versionId.isBlank() || versionId.equals("null") || versionId.length()>1024 || versionId.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("An explicit object version is required");
        }
        public ArtifactContent content(UUID taskId,UUID attemptId) { return new ArtifactContent(taskId,attemptId,port,sha256,bytes,mediaType); }
    }
}
