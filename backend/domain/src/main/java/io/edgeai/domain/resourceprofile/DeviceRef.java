package io.edgeai.domain.resourceprofile;

/** One physical accelerator selected from the existing node inventory (node name + exporter device identity). */
public record DeviceRef(String node, AcceleratorRequirement.Kind kind, String device) {
    public DeviceRef {
        node = WorkloadTarget.name(node, "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*", 253, "node");
        if (kind == null) throw new IllegalArgumentException("Device kind is required");
        if (device == null || device.length() > 128 || !device.matches("[A-Za-z0-9][A-Za-z0-9:._-]*"))
            throw new IllegalArgumentException("Invalid device identity");
    }
}
