package io.edgeai.domain.storage;

public final class ArtifactStoreUnavailableException extends RuntimeException {
    public ArtifactStoreUnavailableException() { super("Artifact storage is unavailable"); }
}
