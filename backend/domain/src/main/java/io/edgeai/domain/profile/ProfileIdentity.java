package io.edgeai.domain.profile;

public record ProfileIdentity(Kind kind, String key, String version) {
    public enum Kind { DEVICE, SERVICE, VD }
    public ProfileIdentity {
        if (kind == null) throw new IllegalArgumentException("kind must be DEVICE, SERVICE or VD");
        validateKey(key);
        if (version == null || version.length() > 32 ||
            !version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("version must be MAJOR.MINOR.PATCH (maximum 32 characters)");
    }
    public static void validateKey(String key) {
        if (key == null || key.length() > 100 || !key.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
            throw new IllegalArgumentException("key must be a lowercase slug (maximum 100 characters)");
    }
}
