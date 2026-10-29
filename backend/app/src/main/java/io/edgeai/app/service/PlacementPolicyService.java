package io.edgeai.app.service;

import io.edgeai.domain.execution.*;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.node.NodeMetricsSource.*;
import io.edgeai.domain.repository.NodeRepository;
import io.edgeai.domain.runtime.ResourceRequirements;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
import io.edgeai.domain.workflow.TaskDefinition;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.service.WorkflowService.error;

/**
 * EDGE_TO_WORKER node policy: initial edge placement check, node observation windows and a deterministic worker filter.
 * Lowest-load ranking only; it reserves nothing and kube-scheduler/claim stay authoritative.
 */
@Service
public class PlacementPolicyService {
    public static final String EDGE="node-role.kubernetes.io/edge",WORKER="node-role.kubernetes.io/worker",CONTROL_PLANE="node-role.kubernetes.io/control-plane";
    private static final Set<String> CORE=Set.of("cpu","memory","ephemeral-storage");
    private final NodeRepository nodes;
    private final Clock clock;
    // In memory: an API restart discards sampling history, so a decision again needs consecutiveSamples new observations.
    private final Map<UUID,NodeSampleWindow> windows=new ConcurrentHashMap<>();
    // GPU series ever reported per observed node (bounded by node and GPU count); outlives windows so a vanished GPU stays required.
    private final Map<String,Set<String>> gpus=new ConcurrentHashMap<>();
    public PlacementPolicyService(NodeRepository nodes,Clock clock){this.nodes=nodes;this.clock=clock;}
    public record Selection(ExecutionNode target,List<Map<String,Object>> candidates) {}

    private static Map<?,?> labels(ExecutionNode node){return parameters(JSON.decode(node.labelsJson()));}
    public static boolean edge(ExecutionNode node){return labels(node).containsKey(EDGE);}
    public static boolean worker(ExecutionNode node){var l=labels(node);return l.containsKey(WORKER) && !l.containsKey(EDGE) && !l.containsKey(CONTROL_PLANE);}

    /** Every SERVICE task must start on an explicit, recently READY, edge-labelled NODE; AUTO/REMOTE/worker placements are rejected. */
    public void validateInitial(List<TaskDefinition> definitions,Map<String,Object> taskExecutions,Map<?,?> execution) {
        var now=clock.instant();
        for(var definition:definitions) {
            var placement=(Map<?,?>)taskExecutions.getOrDefault(definition.key(),execution);
            var node="NODE".equals(placement.get("mode"))?nodes.find(uuid(placement.get("nodeId"))).orElse(null):null;
            if(node==null || !edge(node) || !node.status(now).equals("READY") || !node.operatingSystem().equals("linux"))
                throw error(409,"OFFLOAD_INITIAL_PLACEMENT_INVALID","노드 기반 전환은 모든 작업을 최근 READY 상태의 edge 노드에 NODE로 지정해야 합니다.");
        }
    }

    /** Records only new observations of the source node and evaluates the node trigger; resets on Attempt, node or eligibility change. */
    public Optional<String> observe(UUID taskId,UUID attemptId,String nodeName,Instant eligibleSince,OffloadPolicy policy,Snapshot snapshot,Instant now) {
        var metrics=snapshot==null || !snapshot.status().equals("AVAILABLE")?null:metrics(snapshot,nodeName);
        if(metrics==null) { // Outage or missing source: no evidence now, and no continuity across the gap.
            windows.put(taskId,new NodeSampleWindow(attemptId,nodeName,eligibleSince));return Optional.empty();
        }
        var window=windows.compute(taskId,(k,w)->w!=null && w.matches(attemptId,nodeName,eligibleSince)?w:new NodeSampleWindow(attemptId,nodeName,eligibleSince));
        synchronized(window) {
            var present=new HashSet<String>();
            for(var m:metrics.measurements()) {
                // Each GPU is its own series so an updating cool GPU never re-dates an unchanged hot sample.
                String key=Set.of("CPU_USAGE","MEMORY_USAGE").contains(m.key()) && m.device().isEmpty()?m.key():m.key().equals("GPU_USAGE")?"GPU_USAGE/"+m.device():null;
                if(key!=null && present.add(key))window.observe(key,m.value(),m.observedAt(),m.fresh(),now,policy);
                if(key!=null && key.startsWith("GPU_USAGE/"))gpus.computeIfAbsent(nodeName,n->ConcurrentHashMap.newKeySet()).add(key);
            }
            window.retain(present);
            return policy.nodeTrigger(window.recent(),now,eligibleSince);
        }
    }
    /**
     * GPU series a return must see cool: every GPU ever reported for the node since API start plus every GPU it reports now;
     * null when the node advertises more installed GPUs than that (an unobserved GPU is not idle).
     */
    public Set<String> requiredGpu(Snapshot snapshot,String nodeName) {
        var required=new TreeSet<String>(gpus.getOrDefault(nodeName,Set.of()));
        var metrics=snapshot==null?null:metrics(snapshot,nodeName);
        if(metrics!=null) {
            metrics.measurements().stream().filter(m->m.key().equals("GPU_USAGE")).forEach(m->required.add("GPU_USAGE/"+m.device()));
            if(metrics.accelerators().stream().filter(a->a.kind().equalsIgnoreCase("GPU")).count()>required.size())return null;
        }
        return required;
    }
    public Map<String,List<NodeSampleWindow.Sample>> recent(UUID taskId){var w=windows.get(taskId);if(w==null)return Map.of();synchronized(w){return w.recent();}}
    public void forget(UUID taskId){windows.remove(taskId);}
    /** Bounds history to the current automatic candidates. */
    public void retain(Collection<UUID> taskIds){windows.keySet().retainAll(Set.copyOf(taskIds));}

    /** Recent READY, compatible, worker-labelled nodes with fresh observations under the headroom thresholds, lowest max utilisation first. */
    public Optional<Selection> select(OffloadPolicy policy,ServiceExecutionSpec spec,ExecutionNode source,Snapshot snapshot,Instant now) {
        if(snapshot==null || !snapshot.status().equals("AVAILABLE"))return Optional.empty();
        var p=policy.nodePlacement();var requested=spec.resources().requests();
        boolean gpu=requested.keySet().stream().anyMatch(k->!CORE.contains(k) && k.toLowerCase(Locale.ROOT).contains("gpu"));
        var candidates=new ArrayList<Map<String,Object>>();ExecutionNode best=null;Map<String,Object> bestEntry=null;double bestScore=0;
        for(int offset=0;;offset+=1000) {
            var page=nodes.list(1000,offset);
            for(var node:page) {
                if(node.id().equals(source.id()) || !worker(node) || node.status(now).equals("REMOVED"))continue;
                var entry=new TreeMap<String,Object>();entry.put("nodeId",node.id().toString());entry.put("name",node.name());
                var metrics=metrics(snapshot,node.name());
                Double cpu=fresh(metrics,"CPU_USAGE",policy,now),memory=fresh(metrics,"MEMORY_USAGE",policy,now),gpuUsage=gpu?freshGpu(metrics,policy,now):null;
                entry.put("cpuPercent",cpu);entry.put("memoryPercent",memory);entry.put("gpuPercent",gpuUsage);
                String reason=blocker(spec,node,now);
                if(reason!=null)entry.remove("score"); // Static incompatibility wins over any observation.
                else if(cpu==null || memory==null)reason="UNOBSERVED";
                else if(gpu && gpuUsage==null)reason="GPU_UNOBSERVED";
                else if(cpu>=p.targetMaxCpuPercent() || memory>=p.targetMaxMemoryPercent() || (gpu && gpuUsage>=p.targetMaxGpuPercent()))reason="NO_HEADROOM";
                else {
                    reason="ELIGIBLE";double score=Math.max(cpu,Math.max(memory,gpu?gpuUsage:0));entry.put("score",score);
                    if(best==null || score<bestScore || (score==bestScore && node.name().compareTo(best.name())<0)){best=node;bestEntry=entry;bestScore=score;}
                }
                entry.put("reason",reason);if(candidates.size()<32)candidates.add(entry);
            }
            if(page.size()<1000)break;
        }
        var winner=bestEntry; // Evidence stays bounded but always keeps the chosen worker's metrics.
        if(winner!=null && candidates.stream().noneMatch(e->e==winner))candidates.set(candidates.size()-1,winner);
        return best==null?Optional.empty():Optional.of(new Selection(best,candidates));
    }
    /** NOT_SCHEDULABLE or RESOURCE_UNAVAILABLE for a node the SERVICE can never start on now; null when it is a feasible placement. */
    public static String blocker(ServiceExecutionSpec spec,ExecutionNode node,Instant now) {
        if(!node.status(now).equals("READY") || !node.operatingSystem().equals("linux") || !spec.architectures().contains(node.architecture()) ||
            spec.nodeSelector().entrySet().stream().anyMatch(e->!e.getValue().equals(labels(node).get(e.getKey()))))return "NOT_SCHEDULABLE";
        return fits(spec.resources().requests(),parameters(JSON.decode(node.allocatableJson())))?null:"RESOURCE_UNAVAILABLE";
    }
    /** Static fit against total advertised allocatable (core and extended); not a reservation of free capacity. */
    private static boolean fits(Map<String,String> requested,Map<?,?> allocatable) {
        for(var e:requested.entrySet()) {
            if(!(allocatable.get(e.getKey()) instanceof String available))return false;
            try{if(ResourceRequirements.quantity(e.getKey(),e.getValue()).compareTo(ResourceRequirements.quantity(e.getKey(),available))>0)return false;}
            catch(IllegalArgumentException unparsable){return false;}
        }
        return true;
    }
    private static NodeMetrics metrics(Snapshot snapshot,String nodeName){return snapshot.items().stream().filter(n->n.nodeName().equals(nodeName)).findFirst().orElse(null);}
    private static boolean current(Measurement m,OffloadPolicy policy,Instant now) {
        return m.fresh() && Double.isFinite(m.value()) && m.value()>=0 && m.value()<=100 && !m.observedAt().isBefore(now.minusSeconds(policy.maxSampleAgeSeconds())) && !m.observedAt().isAfter(now.plusSeconds(5));
    }
    private static Double fresh(NodeMetrics metrics,String key,OffloadPolicy policy,Instant now) {
        if(metrics==null)return null;
        return metrics.measurements().stream().filter(m->m.key().equals(key) && m.device().isEmpty() && current(m,policy,now)).map(Measurement::value).findFirst().orElse(null);
    }
    /** Every reported GPU must be currently observed; the busiest one counts. A GPU without observations is not idle. */
    private static Double freshGpu(NodeMetrics metrics,OffloadPolicy policy,Instant now) {
        if(metrics==null)return null;
        var gpus=metrics.measurements().stream().filter(m->m.key().equals("GPU_USAGE")).toList();
        long installed=metrics.accelerators().stream().filter(a->a.kind().equalsIgnoreCase("GPU")).count();
        if(gpus.isEmpty() || gpus.size()<installed || gpus.stream().anyMatch(m->!current(m,policy,now)))return null;
        return gpus.stream().mapToDouble(Measurement::value).max().orElseThrow();
    }
}
