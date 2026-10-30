package io.edgeai.domain.aimodel;

import java.util.List;

/**
 * A manifest read from the configured registry with its content digest verified against the returned bytes.
 * {@code digest} is the digest of what the reference names: an image index (multi-platform) or a single image
 * manifest. For an index, each runnable entry keeps its own platform manifest digest.
 */
public record ResolvedImage(String repository, String reference, String digest, String mediaType, Kind kind,
                            List<PlatformManifest> platforms) {
    public enum Kind { INDEX, MANIFEST }
    public record PlatformManifest(ImagePlatform platform, String digest, String mediaType, long size) {}
    public ResolvedImage { platforms = List.copyOf(platforms); }
}
