package io.edgeai.domain.resourceprofile;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResourceRequirementTest {
    @Test void rangesRequirePositiveOrderedBoundedIntegers() {
        assertThat(ResourceRange.cpu(100, 250, 2000).request()).isEqualTo(250);
        assertThat(ResourceRange.cpu(250, 250, 250).max()).isEqualTo(250);
        assertThatThrownBy(() -> ResourceRange.cpu(300, 250, 2000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResourceRange.cpu(100, 250, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResourceRange.cpu(0, 250, 2000)).as("absent is not zero").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResourceRange.cpu(1, 1, ResourceRange.MAX_CPU_MILLICORES + 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResourceRange.memory(1, 1, ResourceRange.MAX_MEMORY_BYTES + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceleratorRequirementsAreExtendedResourcesAndDeviceMemoryIsSeparate() {
        var gpu = new AcceleratorRequirement(AcceleratorRequirement.Kind.GPU, "nvidia.com/gpu", AcceleratorRequirement.Unit.COUNT,
            new ResourceRange(1, 1, 2), 8L << 30, "NVIDIA", List.of("RTX 5080"));
        assertThat(gpu.deviceMemoryBytes()).isEqualTo(8L << 30);
        var share = new AcceleratorRequirement(AcceleratorRequirement.Kind.GPU, "nvidia.com/gpucores", AcceleratorRequirement.Unit.PERCENT,
            new ResourceRange(10, 30, 100), null, null, null);
        assertThat(share.models()).isEmpty();
        for (String name : List.of("memory", "cpu", "gpu", "kubernetes.io/gpu", "example.kubernetes.io/x", "Nvidia.com/gpu", "nvidia.com/"))
            assertThatThrownBy(() -> new AcceleratorRequirement(AcceleratorRequirement.Kind.GPU, name, AcceleratorRequirement.Unit.COUNT,
                new ResourceRange(1, 1, 1), null, null, null)).as(name).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcceleratorRequirement(AcceleratorRequirement.Kind.GPU, "nvidia.com/gpucores", AcceleratorRequirement.Unit.PERCENT,
            new ResourceRange(10, 30, 101), null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcceleratorRequirement(AcceleratorRequirement.Kind.NPU, "mobilint.com/npu", AcceleratorRequirement.Unit.COUNT,
            new ResourceRange(1, 1, 1), 0L, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void observedDistributionsCarryValuesOnlyWhenUsable() {
        assertThat(UsageSummary.missing(ObservationStatus.NO_DATA, "bytes", 0, "none").max()).isNull();
        assertThatThrownBy(() -> new UsageSummary(ObservationStatus.NO_DATA, "bytes", 0.0, 0.0, 0.0, 0.0, 0, null, null, null))
            .as("no data cannot be represented by zeros").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageSummary(ObservationStatus.AVAILABLE, "bytes", 1.0, Double.NaN, 2.0, 3.0, 10, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageSummary(ObservationStatus.AVAILABLE, "bytes", 5.0, 4.0, 2.0, 3.0, 10, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void workloadTargetsAreKubernetesNames() {
        var target = new WorkloadTarget("edgeai", WorkloadTarget.Kind.StatefulSet, "edgeai-postgres", "postgres", "edgeai-postgres-0", java.util.UUID.randomUUID());
        assertThat(target.kind().recommendable()).isTrue();
        assertThat(new WorkloadTarget("edgeai", WorkloadTarget.Kind.Pod, "solo", "c", "solo", null).kind().recommendable()).isFalse();
        assertThatThrownBy(() -> new WorkloadTarget("Edge AI", WorkloadTarget.Kind.Deployment, "a", "b", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkloadTarget("edgeai", WorkloadTarget.Kind.Pod, "solo", "c", "other", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkloadTarget("edgeai", WorkloadTarget.Kind.Deployment, "a", "b", null, java.util.UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}
