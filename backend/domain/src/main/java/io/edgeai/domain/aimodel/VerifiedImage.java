package io.edgeai.domain.aimodel;

/**
 * Registration-time verification result: the immutable reference ({@code digest}, index or manifest), the selected
 * platform's own manifest digest and the platform read from that manifest's image config blob.
 */
public record VerifiedImage(String repository, String digest, String mediaType, ResolvedImage.Kind kind, ImagePlatform platform,
                            String platformManifestDigest, String platformManifestMediaType, String configDigest,
                            String configMediaType, long layersSize, String created) {}
