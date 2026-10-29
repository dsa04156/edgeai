package io.edgeai.domain.resourceprofile;

/** One Pod's own container usage in the window (never summed across replicas); null when the Pod had no samples. */
public record PodUsage(String pod, Double cpuP95Millicores, Double cpuMaxMillicores, Double memoryMaxBytes, int samples) {}
