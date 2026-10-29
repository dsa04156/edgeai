package io.edgeai.domain.resourceprofile;

/** A Pod observed for the target during the window; node is empty when kube-state-metrics did not report it. */
public record ObservedPod(String name, String uid, String node) {}
