package io.edgeai.domain.storage;

public interface ArtifactStore {
    ArtifactGrant upload(ArtifactContent expected);
    VerifiedArtifact verify(ArtifactContent expected, String versionId);
    ArtifactGrant download(VerifiedArtifact artifact);
}
