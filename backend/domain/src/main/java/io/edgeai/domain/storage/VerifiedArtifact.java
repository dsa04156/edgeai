package io.edgeai.domain.storage;

public record VerifiedArtifact(String bucket, String objectKey, String versionId, String sha256, long bytes, String mediaType) {}
