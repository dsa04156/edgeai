package io.edgeai.domain.vd;

import io.edgeai.domain.runtime.RuntimeNames;
import java.net.URI;
import java.util.UUID;

public record VDRuntimeLaunch(UUID vdId, UUID runtimeId, long generation, String namespace, String serviceAccount,
                              URI controlPlane, UUID targetNodeId, String targetNodeName, int maxConcurrentTasks,
                              int startupSeconds, int drainSeconds) {
    public VDRuntimeLaunch {
        if(vdId==null || runtimeId==null || generation<1 || generation>9007199254740991L
            || maxConcurrentTasks<1 || maxConcurrentTasks>16 || startupSeconds<1 || startupSeconds>600 || drainSeconds<1 || drainSeconds>600)
            throw new IllegalArgumentException("Invalid VD runtime identity or policy");
        RuntimeNames.dns(namespace,63);RuntimeNames.dns(serviceAccount,253);
        if(namespace.contains("."))throw new IllegalArgumentException("Namespace must be a DNS label");
        if(controlPlane==null || controlPlane.getHost()==null || controlPlane.getUserInfo()!=null || controlPlane.getQuery()!=null
            || controlPlane.getFragment()!=null || !controlPlane.getPath().isEmpty()
            || !(controlPlane.getScheme().equals("http") || controlPlane.getScheme().equals("https")))
            throw new IllegalArgumentException("Control Plane must be an HTTP(S) origin");
        if((targetNodeId==null)!=(targetNodeName==null))throw new IllegalArgumentException("NODE requires UID and name");
        if(targetNodeName!=null)RuntimeNames.dns(targetNodeName,253);
    }
    public String podName() { return "edgeai-vd-"+runtimeId; }
    public String claimSecret() { return podName()+"-claim"; }
}
