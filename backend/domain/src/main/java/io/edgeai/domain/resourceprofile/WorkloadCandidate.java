package io.edgeai.domain.resourceprofile;

import java.util.List;

/** A workload and its containers observed through kube-state-metrics/cAdvisor; selectable, never inferred. */
public record WorkloadCandidate(String namespace, WorkloadTarget.Kind kind, String name, List<String> containers) {
    public WorkloadCandidate { containers = List.copyOf(containers); }
}
