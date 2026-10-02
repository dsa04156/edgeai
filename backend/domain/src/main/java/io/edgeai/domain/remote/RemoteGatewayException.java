package io.edgeai.domain.remote;
/** Stable classifications only: provider payloads and bearer credentials never become messages/causes. */
public final class RemoteGatewayException extends RuntimeException {
    public enum Reason { UNAVAILABLE, AUTH_REJECTED, CONFLICT, UNSUPPORTED, INVALID_INPUT, NOT_FOUND, INVALID_RESPONSE, INTEGRITY_FAILED }
    private final Reason reason;
    public RemoteGatewayException(Reason reason){super(reason.name());this.reason=reason;}
    public Reason reason(){return reason;}
}
