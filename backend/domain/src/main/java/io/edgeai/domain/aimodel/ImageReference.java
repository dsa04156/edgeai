package io.edgeai.domain.aimodel;

import java.util.regex.Pattern;

/**
 * OCI distribution-spec name rules (opencontainers/distribution-spec spec.md): repository names, tags and the only
 * accepted digest algorithm (sha256). Values are validated before they become part of any registry request path.
 */
public final class ImageReference {
    public static final int MAX_REPOSITORY = 255;
    private static final Pattern REPOSITORY = Pattern.compile("[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*");
    private static final Pattern TAG = Pattern.compile("[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private ImageReference() {}

    public static String repository(String value) {
        if (value == null || value.length() > MAX_REPOSITORY || !REPOSITORY.matcher(value).matches())
            throw new IllegalArgumentException("Invalid repository name");
        return value;
    }
    public static String tag(String value) {
        if (value == null || !TAG.matcher(value).matches()) throw new IllegalArgumentException("Invalid tag");
        return value;
    }
    public static String digest(String value) {
        if (value == null || !DIGEST.matcher(value).matches()) throw new IllegalArgumentException("Invalid sha256 digest");
        return value;
    }
    public static boolean isDigest(String value) { return value != null && DIGEST.matcher(value).matches(); }
    /** A manifest reference is either a tag or a sha256 digest. */
    public static String reference(String value) { return isDigest(value) ? value : tag(value); }
}
