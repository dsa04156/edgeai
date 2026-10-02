package io.edgeai.domain.remote;
import java.util.Set;
/** Immutable provider binding; no endpoint credentials or bearer material. */
public record RemoteTarget(String providerKey,String configurationDigest,String sourceMode) {
    public RemoteTarget {
        if(providerKey==null || providerKey.length()>63 || !providerKey.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*") ||
            configurationDigest==null || !configurationDigest.matches("sha256:[a-f0-9]{64}") || !Set.of("SYNTHETIC","EXTERNAL").contains(sourceMode))
            throw new IllegalArgumentException("Invalid remote provider binding");
    }
}
