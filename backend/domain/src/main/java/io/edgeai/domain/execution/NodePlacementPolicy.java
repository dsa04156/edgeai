package io.edgeai.domain.execution;

/**
 * Opt-in edge-to-worker thresholds on node utilisation percentages; a filter and simple ranking, not a scheduler.
 * returnToEdge (optional) moves the task back to its ORIGINAL edge once that edge stays below strictly lower thresholds.
 */
public record NodePlacementPolicy(String strategy,Integer sourceCpuPercent,Integer sourceMemoryPercent,Integer sourceGpuPercent,
        int targetMaxCpuPercent,int targetMaxMemoryPercent,int targetMaxGpuPercent,ReturnToEdge returnToEdge) {
    public static final String EDGE_TO_WORKER="EDGE_TO_WORKER";
    /** Every configured source threshold needs a strictly lower return threshold (hysteresis band). */
    public record ReturnToEdge(Integer cpuPercent,Integer memoryPercent,Integer gpuPercent) {}
    public NodePlacementPolicy {
        if(!EDGE_TO_WORKER.equals(strategy))throw new IllegalArgumentException("Node placement strategy must be EDGE_TO_WORKER");
        if(sourceCpuPercent==null && sourceMemoryPercent==null && sourceGpuPercent==null)throw new IllegalArgumentException("At least one source node threshold required");
        for(Integer value:new Integer[]{sourceCpuPercent,sourceMemoryPercent,sourceGpuPercent,targetMaxCpuPercent,targetMaxMemoryPercent,targetMaxGpuPercent})
            if(value!=null && (value<1 || value>100))throw new IllegalArgumentException("Node placement percentage out of range");
        if(returnToEdge!=null) {
            var r=returnToEdge;
            if(r.cpuPercent()==null && r.memoryPercent()==null && r.gpuPercent()==null)throw new IllegalArgumentException("At least one return threshold required");
            for(Integer value:new Integer[]{r.cpuPercent(),r.memoryPercent(),r.gpuPercent()})
                if(value!=null && (value<1 || value>100))throw new IllegalArgumentException("Return percentage out of range");
            band(sourceCpuPercent,r.cpuPercent());band(sourceMemoryPercent,r.memoryPercent());band(sourceGpuPercent,r.gpuPercent());
        }
    }
    public NodePlacementPolicy(String strategy,Integer sourceCpuPercent,Integer sourceMemoryPercent,Integer sourceGpuPercent,
            int targetMaxCpuPercent,int targetMaxMemoryPercent,int targetMaxGpuPercent) {
        this(strategy,sourceCpuPercent,sourceMemoryPercent,sourceGpuPercent,targetMaxCpuPercent,targetMaxMemoryPercent,targetMaxGpuPercent,null);
    }
    private static void band(Integer source,Integer back) {
        if(source!=null && (back==null || back>=source))throw new IllegalArgumentException("Return threshold must be below its source threshold");
    }
}
