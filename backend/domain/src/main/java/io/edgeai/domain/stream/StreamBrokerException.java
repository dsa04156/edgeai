package io.edgeai.domain.stream;

/** Deliberately excludes broker diagnostics, wire payloads and credentials. */
public final class StreamBrokerException extends RuntimeException {
    public enum Reason { UNAVAILABLE, REJECTED, INVALID_RESPONSE, CONFIGURATION, CONFLICT, REVOKED }
    private final Reason reason;
    public StreamBrokerException(Reason reason){super("Stream broker: "+reason.name());this.reason=reason;}
    public Reason reason(){return reason;}
}
