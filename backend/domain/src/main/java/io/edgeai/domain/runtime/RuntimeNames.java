package io.edgeai.domain.runtime;

/** Kubernetes identifiers accepted at the execution boundary, without Kubernetes SDK dependencies. */
public final class RuntimeNames {
    private RuntimeNames() {}
    public static String dns(String value, int maximum) {
        if (value == null || value.length() > maximum || !value.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?"))
            throw new IllegalArgumentException("Invalid DNS name");
        for (String label : value.split("\\.", -1))
            if (label.length() > 63 || !label.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?"))
                throw new IllegalArgumentException("Invalid DNS label");
        return value;
    }
    public static String qualified(String value) {
        if (value == null) throw new IllegalArgumentException("Missing qualified name");
        String[] parts = value.split("/", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Invalid qualified name");
        if (parts.length == 2) dns(parts[0], 253);
        String name = parts[parts.length - 1];
        if (name.length() > 63 || !name.matches("[a-zA-Z0-9]([a-zA-Z0-9_.-]*[a-zA-Z0-9])?"))
            throw new IllegalArgumentException("Invalid qualified name");
        return value;
    }
    public static String labelValue(String value) {
        if (value == null || value.length() > 63 || !value.matches("([a-zA-Z0-9]([a-zA-Z0-9_.-]*[a-zA-Z0-9])?)?"))
            throw new IllegalArgumentException("Invalid label value");
        return value;
    }
    public static String port(String value) {
        if (value == null || value.length() > 100 || !value.matches("[a-z][a-z0-9]*([._-][a-z0-9]+)*"))
            throw new IllegalArgumentException("Invalid port name");
        return value;
    }
}
