package io.edgeai.domain.vd;

import io.edgeai.domain.runtime.RuntimeNames;
import java.util.*;

/** Authenticated supervisor identity for a child Runner; there is no Kubernetes Job identity. */
public record VDTaskProducer(UUID runtimeId,long generation,UUID sessionId,UUID podUid,UUID nodeUid,String nodeName) {
    public VDTaskProducer {
        Objects.requireNonNull(runtimeId);Objects.requireNonNull(sessionId);Objects.requireNonNull(podUid);Objects.requireNonNull(nodeUid);
        if(generation<1 || generation>9007199254740991L)throw new IllegalArgumentException("Invalid VD generation");
        RuntimeNames.dns(nodeName,253);
    }
}
