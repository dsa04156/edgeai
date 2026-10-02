package io.edgeai.domain.runtime;
/** Fixed codes only: Kubernetes error bodies can contain workload/credential material. */
public final class RuntimeGatewayException extends RuntimeException {
    public enum Reason { UNAVAILABLE, OWNERSHIP_CONFLICT, AUTH_REJECTED, RUNTIME_LOST }
    private final Reason reason;
    public RuntimeGatewayException(Reason reason) { super(reason.name());this.reason=reason; }
    public Reason reason() { return reason; }
}
