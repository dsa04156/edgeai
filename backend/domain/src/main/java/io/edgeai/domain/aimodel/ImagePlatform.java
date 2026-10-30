package io.edgeai.domain.aimodel;

import java.util.Set;

/** Platform an image manifest actually declares (index descriptor or image config); never inferred from a tag name. */
public record ImagePlatform(String os, String architecture, String variant) {
    /** Platforms the EdgeAI execution side can run; other declared platforms are shown but cannot be registered. */
    public static final Set<String> RUNNABLE_ARCHITECTURES = Set.of("amd64", "arm64");

    public ImagePlatform {
        if (os == null || os.isBlank() || os.length() > 32 || architecture == null || architecture.isBlank() || architecture.length() > 32)
            throw new IllegalArgumentException("Platform requires os and architecture");
        if (variant != null && (variant.isBlank() || variant.length() > 16)) throw new IllegalArgumentException("Invalid platform variant");
        for (String part : new String[]{os, architecture, variant == null ? "x" : variant})
            if (!part.matches("[a-z0-9._-]+")) throw new IllegalArgumentException("Invalid platform value");
    }
    public boolean runnable() { return os.equals("linux") && RUNNABLE_ARCHITECTURES.contains(architecture); }
    /** Attestation/provenance entries of BuildKit indexes are declared as unknown/unknown and are never images to run. */
    public boolean unknown() { return os.equals("unknown") && architecture.equals("unknown"); }
    public String label() { return os + "/" + architecture + (variant == null ? "" : "/" + variant); }
}
