package io.edgeai.domain.aimodel;

/** Registry failure with a fixed, credential-free reason; the message never echoes upstream bodies or URLs. */
public final class RegistryException extends RuntimeException {
    public enum Reason {
        NOT_CONFIGURED, UNAVAILABLE, TIMEOUT, NOT_FOUND, UNAUTHORIZED, REDIRECT_BLOCKED, TOO_LARGE, DIGEST_MISMATCH,
        UNSUPPORTED_ARTIFACT, INVALID_RESPONSE, PLATFORM_NOT_FOUND
    }
    private final Reason reason;
    public RegistryException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
