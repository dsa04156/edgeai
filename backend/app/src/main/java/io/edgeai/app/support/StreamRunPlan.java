package io.edgeai.app.support;

import io.edgeai.domain.execution.Task;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
import io.edgeai.domain.stream.DataRoute;
import io.edgeai.domain.workflow.Dag;
import java.util.*;

/** Complete immutable route membership, including Device fanout, before any execution assignment. */
public record StreamRunPlan(Map<UUID,ServiceExecutionSpec> specs,Map<UUID,UUID> components,List<DataRoute> routes) {
    public static final int MAX_ROUTES=128*16; // DAG tasks * distinct SERVICE stream input ports.
    public StreamRunPlan { specs=Map.copyOf(specs);components=Map.copyOf(components);routes=List.copyOf(routes); }
    public List<DataRoute> taskRoutes(UUID task) {
        return routes.stream().filter(r->task.equals(r.sourceTaskId()) || task.equals(r.consumerTaskId()))
            .sorted(Comparator.comparing(r->r.id().toString())).toList();
    }
    public Set<UUID> componentTasks(UUID task) {
        var id=components.get(task);var result=new HashSet<UUID>();
        components.forEach((key,value)->{if(value.equals(id))result.add(key);});return Set.copyOf(result);
    }
    public List<DataRoute> componentRoutes(UUID task) {
        var tasks=componentTasks(task);return routes.stream().filter(r->tasks.contains(r.consumerTaskId())).toList();
    }
    public static Optional<StreamRunPlan> compile(List<Task> tasks,Map<UUID,ServiceExecutionSpec> specs,Dag dag,List<DataRoute> routes) {
        if(routes.size()>MAX_ROUTES)throw new IllegalArgumentException("Too many Run stream routes");
        var keys=new HashMap<String,UUID>();tasks.forEach(t->keys.put(t.key(),t.id()));
        var expected=new HashSet<String>();
        for(var edge:dag.dependencies())if(edge.mode()==Dag.Mode.STREAM) {
            var source=specs.get(keys.get(edge.fromTask())).stream();var target=specs.get(keys.get(edge.toTask())).stream();
            if(source==null || target==null)throw new IllegalArgumentException("STREAM dependencies require stream SERVICE ports");
            var output=source.outputs().get(edge.fromPort());var input=target.inputs().get(edge.toPort());
            if(output==null || input==null || !output.mediaType().equals(input.mediaType()) || output.maxPayloadBytes()>input.maxPayloadBytes())
                throw new IllegalArgumentException("Incompatible STREAM dependency");
            expected.add(edge(keys.get(edge.fromTask()),edge.fromPort(),keys.get(edge.toTask()),edge.toPort()));
        }
        var actual=new HashSet<String>();var adjacency=new HashMap<String,Set<String>>();
        for(var task:tasks)adjacency.put("task:"+task.id(),new HashSet<>());
        for(var route:routes) {
            var target=specs.get(route.consumerTaskId());
            if(target==null || target.stream()==null)throw new IllegalArgumentException("Route requires a streaming consumer");
            var input=target.stream().inputs().get(route.consumerPort());
            if(input==null || !input.mediaType().equals(route.mediaType()) || route.maxPayloadBytes()>input.maxPayloadBytes())
                throw new IllegalArgumentException("Route differs from SERVICE stream input");
            String source;
            if(route.deviceSource()) {
                source="device:"+route.sourceDeviceId();
                if(dag.dependencies().stream().anyMatch(e->keys.get(e.toTask()).equals(route.consumerTaskId()) && e.toPort().equals(route.consumerPort())))
                    throw new IllegalArgumentException("Device cannot replace a published dependency");
            } else {
                var service=specs.get(route.sourceTaskId());
                var output=service==null || service.stream()==null?null:service.stream().outputs().get(route.sourcePort());
                if(output==null || !output.mediaType().equals(route.mediaType()) || route.maxPayloadBytes()>output.maxPayloadBytes())
                    throw new IllegalArgumentException("Route differs from SERVICE stream output");
                String key=edge(route.sourceTaskId(),route.sourcePort(),route.consumerTaskId(),route.consumerPort());
                if(!expected.contains(key) || !actual.add(key))throw new IllegalArgumentException("Route differs from published STREAM dependency");
                source="task:"+route.sourceTaskId();
            }
            String targetId="task:"+route.consumerTaskId();
            adjacency.computeIfAbsent(source,k->new HashSet<>()).add(targetId);adjacency.get(targetId).add(source);
        }
        boolean complete=actual.equals(expected);
        for(var entry:specs.entrySet()) {
            var stream=entry.getValue().stream();if(stream==null)continue;
            var input=new HashSet<String>();var output=new HashSet<String>();int count=0,outCount=0;
            for(var route:routes) {
                if(entry.getKey().equals(route.consumerTaskId())) { if(!input.add(route.consumerPort()))throw new IllegalArgumentException("Duplicate stream input");count++; }
                if(entry.getKey().equals(route.sourceTaskId())) { output.add(route.sourcePort());count++;outCount++; }
            }
            complete &= input.equals(stream.inputs().keySet()) && output.equals(stream.outputs().keySet());
            if(count>32 || outCount>16 || stream.limits().maxFrames()<count || stream.limits().maxBufferBytes()<count
                    || outCount>0 && stream.limits().maxFrames()/Math.max(1,count)<2)
                throw new IllegalArgumentException("SERVICE cannot hold the assigned stream routes");
        }
        if(!complete)return Optional.empty();
        var component=new HashMap<UUID,UUID>();var visited=new HashSet<String>();
        for(var task:tasks) {
            String start="task:"+task.id();if(visited.contains(start))continue;
            var queue=new ArrayDeque<String>();var members=new HashSet<UUID>();queue.add(start);
            while(!queue.isEmpty()) {
                var node=queue.removeFirst();if(!visited.add(node))continue;
                if(node.startsWith("task:"))members.add(UUID.fromString(node.substring(5)));
                queue.addAll(adjacency.getOrDefault(node,Set.of()));
            }
            var key=members.stream().min(Comparator.comparing(UUID::toString)).orElseThrow();members.forEach(id->component.put(id,key));
        }
        // A BATCH dependency within or cyclically between simultaneous stream components deadlocks.
        var outgoing=new HashMap<UUID,Set<UUID>>();var degrees=new HashMap<UUID,Integer>();
        component.values().forEach(id->{outgoing.putIfAbsent(id,new HashSet<>());degrees.putIfAbsent(id,0);});
        for(var edge:dag.dependencies())if(edge.mode()==Dag.Mode.BATCH) {
            var from=component.get(keys.get(edge.fromTask()));var to=component.get(keys.get(edge.toTask()));
            if(from.equals(to))throw new IllegalArgumentException("BATCH dependency inside a simultaneous stream component");
            if(outgoing.get(from).add(to))degrees.merge(to,1,Integer::sum);
        }
        var queue=new ArrayDeque<UUID>();degrees.forEach((id,n)->{if(n==0)queue.add(id);});int seen=0;
        while(!queue.isEmpty()) {var id=queue.removeFirst();seen++;for(var next:outgoing.get(id))if(degrees.merge(next,-1,Integer::sum)==0)queue.add(next);}
        if(seen!=degrees.size())throw new IllegalArgumentException("Cyclic BATCH dependencies between stream components");
        return Optional.of(new StreamRunPlan(specs,component,routes));
    }
    private static String edge(UUID source,String output,UUID target,String input) { return source+"/"+output+"/"+target+"/"+input; }
}
