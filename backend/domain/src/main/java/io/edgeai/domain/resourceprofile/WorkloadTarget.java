package io.edgeai.domain.resourceprofile;

import java.util.UUID;

/**
 * An explicit Kubernetes workload container whose observed usage is profiled. Values are validated Kubernetes
 * names, so adapters may embed them in fixed queries without accepting expressions from callers.
 * {@code pod}/{@code podUid} optionally narrow the measurement to one Pod instance of the workload.
 */
public record WorkloadTarget(String namespace, Kind kind, String name, String container, String pod, UUID podUid) {
    /** Workload kinds whose Pods are resolved through kube-state-metrics owner series. */
    public enum Kind { Deployment, StatefulSet, DaemonSet, Job, CronJob, Pod;
        public boolean recommendable() { return this != Pod; }
    }
    private static final String LABEL = "[a-z0-9]([-a-z0-9]*[a-z0-9])?";
    private static final String SUBDOMAIN = LABEL + "(\\." + LABEL + ")*";

    public WorkloadTarget {
        if (kind == null) throw new IllegalArgumentException("Workload kind is required");
        namespace = name(namespace, LABEL, 63, "namespace");
        name = name(name, SUBDOMAIN, 253, "workload name");
        container = name(container, LABEL, 63, "container");
        if (pod != null) pod = name(pod, SUBDOMAIN, 253, "pod");
        if (podUid != null && pod == null) throw new IllegalArgumentException("Pod UID requires the Pod name");
        if (kind == Kind.Pod && (pod == null || !pod.equals(name))) throw new IllegalArgumentException("Pod targets name the Pod itself");
    }
    public static String name(String value, String pattern, int max, String field) {
        if (value == null || value.isEmpty() || value.length() > max || !value.matches(pattern))
            throw new IllegalArgumentException("Invalid Kubernetes " + field);
        return value;
    }
    public static String namespace(String value) { return name(value, LABEL, 63, "namespace"); }
}
