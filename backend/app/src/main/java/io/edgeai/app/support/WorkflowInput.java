package io.edgeai.app.support;

import io.edgeai.domain.workflow.Dag;
import io.edgeai.domain.execution.RetryPolicy;
import io.edgeai.domain.execution.OffloadPolicy;
import java.math.BigDecimal;
import java.util.*;

public final class WorkflowInput {
    public static final JsonDocuments JSON = new JsonDocuments();
    private WorkflowInput() {}
    public static Map<?, ?> parse(String body, String... fields) {
        var value = object(JSON.parse(body, 65536), fields);
        JSON.boundedCanonical(value, 65536); return value;
    }
    public static Map<?, ?> object(Object value, String... fields) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(fields)))
            throw new IllegalArgumentException("Unexpected object fields");
        return map;
    }
    public static Map<?, ?> runRequest(String body) {
        var map=parameters(JSON.parse(body,65536));
        if(!map.keySet().containsAll(Set.of("workflowVersionId","execution","parameters")) ||
                !Set.of("workflowVersionId","execution","parameters","retry","offload","streamInputs","taskExecutions").containsAll(map.keySet()))
            throw new IllegalArgumentException("Unexpected Run fields");
        JSON.boundedCanonical(map,65536);return map;
    }
    public static Map<String,Object> taskExecutions(Object value) {
        var input=parameters(value);if(input.size()>128)throw new IllegalArgumentException("At most 128 Task placements allowed");
        var result=new TreeMap<String,Object>();
        for(var entry:input.entrySet()) {
            String key=text(entry.getKey(),100);
            if(!key.matches("[a-z][a-z0-9]*([._-][a-z0-9]+)*"))throw new IllegalArgumentException("Invalid Task key");
            var placement=parameters(entry.getValue());String mode=text(placement.get("mode"),8);
            if(mode.equals("AUTO")) {object(placement,"mode");result.put(key,Map.of("mode",mode));}
            else if(mode.equals("NODE")) {object(placement,"mode","nodeId");result.put(key,Map.of("mode",mode,"nodeId",uuid(placement.get("nodeId")).toString()));}
            else throw new IllegalArgumentException("Task placement must be AUTO or NODE");
        }
        return result;
    }
    public static RetryPolicy retryPolicy(Object value) {
        var map=object(value,"maxAttempts","backoffSeconds","maxElapsedSeconds","retryOn");
        if(!(map.get("retryOn") instanceof List<?> list) || list.size()>RetryPolicy.ALLOWED.size())throw new IllegalArgumentException("Retry codes required");
        var codes=new HashSet<String>();
        for(var code:list)if(!codes.add(text(code,32)))throw new IllegalArgumentException("Duplicate retry code");
        return new RetryPolicy(integer(map.get("maxAttempts")),integer(map.get("backoffSeconds")),integer(map.get("maxElapsedSeconds")),codes);
    }
    public static Map<String,Object> document(RetryPolicy policy) {
        return Map.of("maxAttempts",policy.maxAttempts(),"backoffSeconds",policy.backoffSeconds(),
            "maxElapsedSeconds",policy.maxElapsedSeconds(),"retryOn",policy.retryOn().stream().sorted().toList());
    }
    public static OffloadPolicy offloadPolicy(Object value) {
        if(value==null)return null;
        var m=object(value,"cpuPercent","memoryPercent","latencyMicros","consecutiveSamples","maxSampleAgeSeconds","maxGapSeconds",
            "minRunningSeconds","cooldownSeconds","maxTransfers","drainTimeoutSeconds","startTimeoutSeconds");
        return new OffloadPolicy(m.get("cpuPercent")==null?null:integer(m.get("cpuPercent")),m.get("memoryPercent")==null?null:integer(m.get("memoryPercent")),
            m.get("latencyMicros")==null?null:Long.valueOf(integer(m.get("latencyMicros"))),integer(m.get("consecutiveSamples")),integer(m.get("maxSampleAgeSeconds")),integer(m.get("maxGapSeconds")),
            integer(m.get("minRunningSeconds")),integer(m.get("cooldownSeconds")),integer(m.get("maxTransfers")),integer(m.get("drainTimeoutSeconds")),integer(m.get("startTimeoutSeconds")));
    }
    private static int integer(Object value) {
        if(!(value instanceof Number))throw new IllegalArgumentException("Integer required");
        try{return new BigDecimal(value.toString()).intValueExact();}
        catch(ArithmeticException e){throw new IllegalArgumentException("Integer out of range");}
    }
    public static Map<?, ?> parameters(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("parameters must be an object");
        return map;
    }
    public static String text(Object value, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.length()>max || s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid text");
        return s;
    }
    public static UUID uuid(Object value) {
        String s=text(value,36); UUID id=UUID.fromString(s);
        if (!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException("Canonical UUID required");
        return id;
    }
    public static String version(Object value) {
        String version=text(value,32);
        if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("Version must be MAJOR.MINOR.PATCH");
        return version;
    }
    public static Dag dag(Map<?, ?> input) {
        if (!(input.get("tasks") instanceof List<?> tasks) || !(input.get("dependencies") instanceof List<?> edges))
            throw new IllegalArgumentException("DAG arrays required");
        var nodes=new ArrayList<Dag.Node>(); var dependencies=new ArrayList<Dag.Edge>();
        for (var value:tasks) {
            var task=object(value,"key","serviceProfileVersionId","parameters");
            nodes.add(new Dag.Node(text(task.get("key"),100),uuid(task.get("serviceProfileVersionId")),JSON.canonical(parameters(task.get("parameters")))));
        }
        for (var value:edges) {
            var edge=object(value,"fromTask","toTask","fromPort","toPort","mode");
            dependencies.add(new Dag.Edge(text(edge.get("fromTask"),100),text(edge.get("toTask"),100),
                text(edge.get("fromPort"),100),text(edge.get("toPort"),100),Dag.Mode.valueOf(text(edge.get("mode"),8))));
        }
        nodes.sort(Comparator.comparing(Dag.Node::key));
        dependencies.sort(Comparator.comparing(Dag.Edge::toTask).thenComparing(Dag.Edge::toPort));
        return new Dag(nodes,dependencies);
    }
    public static Dag storedDag(String json) { return dag(object(JSON.decode(json),"tasks","dependencies")); }
    public static Map<String,Object> document(Dag dag) {
        return Map.of("tasks",dag.tasks().stream().map(n->Map.of("key",n.key(),"serviceProfileVersionId",n.serviceProfileVersionId().toString(),"parameters",JSON.decode(n.parametersJson()))).toList(),
            "dependencies",dag.dependencies().stream().map(e->Map.of("fromTask",e.fromTask(),"toTask",e.toTask(),"fromPort",e.fromPort(),"toPort",e.toPort(),"mode",e.mode().name())).toList());
    }
}
