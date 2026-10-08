package io.edgeai.domain.device;

/** CRD transport boundary. Documents are JSON; the domain does not depend on Kubernetes libraries. */
public interface DeviceResourceGateway {
    record Reply(int status, String document) {}
    Reply request(String method, String path, Object document);
}
