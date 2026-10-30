package io.edgeai.domain.aimodel;

/**
 * The single server-configured container registry. Callers pass validated repository names and references only;
 * the registry location, credentials and continuation links are owned by the implementation.
 */
public interface ImageRegistry {
    /** Host[:port] shown to users for push instructions; never contains credentials. */
    String host();
    RegistryPage repositories(String last, int limit);
    RegistryPage tags(String repository, String last, int limit);
    ResolvedImage resolve(String repository, String reference);
    /** Re-reads the digest-pinned manifest, the selected platform manifest and its config blob. */
    VerifiedImage verify(String repository, String digest, ImagePlatform platform);
}
