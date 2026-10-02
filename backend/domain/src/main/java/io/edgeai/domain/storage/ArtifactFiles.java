package io.edgeai.domain.storage;
import java.nio.file.Path;
/** Control-plane file transfer through its own authenticated storage endpoint, never a Runner URL. */
public interface ArtifactFiles {
    void downloadFile(VerifiedArtifact artifact,Path destination);
    String uploadFile(ArtifactContent content,Path source);
}
