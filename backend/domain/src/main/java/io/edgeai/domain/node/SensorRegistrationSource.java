package io.edgeai.domain.node;

import java.util.List;

/** Registration of supported, already deployed sensor drivers; not driver installation. */
public interface SensorRegistrationSource {
    record Template(String id, String label, String serviceName, String nodeName, String profileName,
                    String protocol, List<String> resources, String endpoint, List<Integer> baudRates) {}
    record Catalog(List<Template> templates) {}
    record Request(String name, String templateId, String endpoint, String physicalDeviceId, Integer baudRate) {}
    record Result(String name, String serviceName, String profileName, boolean created,
                  String adminState, String operatingState) {}
    Catalog catalog();
    Result register(Request request);

    final class Failure extends RuntimeException {
        public enum Kind { INVALID, CONFLICT, UNAVAILABLE, UNKNOWN }
        private final Kind kind;
        public Failure(Kind kind, String message) { super(message); this.kind = kind; }
        public Kind kind() { return kind; }
    }
}
