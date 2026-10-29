package io.edgeai.domain.resourceprofile;

import java.util.List;
import java.util.Set;

/**
 * A user-confirmed GPU/NPU requirement expressed as the Kubernetes extended resource the executor reserves.
 * {@code deviceMemoryBytes} is the device memory the model needs; it is evidence for the executor and is not
 * itself a reservable Kubernetes resource. Utilisation percentages are never converted into these quantities.
 */
public record AcceleratorRequirement(Kind kind, String resourceName, Unit unit, ResourceRange range,
                                     Long deviceMemoryBytes, String vendor, List<String> models) {
    public enum Kind { GPU, NPU }
    /** COUNT = whole devices or vendor slices, MEBIBYTES/PERCENT = vendor-partitioned memory or core share. */
    public enum Unit { COUNT, MEBIBYTES, PERCENT }
    private static final Set<String> RESERVED = Set.of("cpu", "memory", "ephemeral-storage", "pods");

    public AcceleratorRequirement {
        if (kind == null || unit == null || range == null) throw new IllegalArgumentException("Accelerator kind, unit and range are required");
        if (resourceName == null || resourceName.length() > 253
            || !resourceName.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)+/[A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?")
            || RESERVED.contains(resourceName) || resourceName.startsWith("kubernetes.io/") || resourceName.contains(".kubernetes.io/"))
            throw new IllegalArgumentException("Accelerator resource requires a vendor-domain extended resource name");
        long limit = unit == Unit.PERCENT ? 100 : ResourceRange.MAX_ACCELERATOR_QUANTITY;
        if (range.max() > limit) throw new IllegalArgumentException("Accelerator maximum exceeds " + limit);
        if (deviceMemoryBytes != null && (deviceMemoryBytes <= 0 || deviceMemoryBytes > ResourceRange.MAX_MEMORY_BYTES))
            throw new IllegalArgumentException("Device memory requirement must be positive");
        vendor = vendor == null || vendor.isBlank() ? null : label(vendor);
        models = models == null ? List.of() : List.copyOf(models);
        if (models.size() > 16) throw new IllegalArgumentException("At most 16 compatible models");
        models.forEach(AcceleratorRequirement::label);
    }
    private static String label(String value) {
        if (value.length() > 100 || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Accelerator vendor/model must be 1-100 printable characters");
        return value;
    }
}
