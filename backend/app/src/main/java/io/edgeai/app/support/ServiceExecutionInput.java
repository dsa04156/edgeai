package io.edgeai.app.support;

import io.edgeai.domain.runtime.ResourceRequirements;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
import java.math.BigDecimal;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

/** Strict consumer contract. The Profile registry still preserves previously published arbitrary JSON. */
public final class ServiceExecutionInput {
    private ServiceExecutionInput() {}
    public static ServiceExecutionSpec parseSpec(String document) {
        var root = fields(JSON.parse(document, 65536),
            Set.of("apiVersion", "image", "command", "args", "resources", "platform", "inputs", "outputs", "timeoutSeconds"),
            Set.of("nodeSelector", "tolerations", "runtimeClassName", "qos"));
        JSON.boundedCanonical(root, 65536);
        if (!"edgeai/v1".equals(root.get("apiVersion"))) throw new IllegalArgumentException("SERVICE execution apiVersion must be edgeai/v1");
        var resources = object(root.get("resources"), "requests", "limits");
        var requirements = new ResourceRequirements(strings(resources.get("requests")), strings(resources.get("limits")));
        if (root.containsKey("qos") && !requirements.qos().equals(root.get("qos")))
            throw new IllegalArgumentException("Declared QoS differs from CPU/memory requests and limits");
        var platform = object(root.get("platform"), "os", "architectures");
        if (!"linux".equals(platform.get("os"))) throw new IllegalArgumentException("Runner supports Linux");
        var inputs = new TreeMap<String, ServiceExecutionSpec.InputPort>();
        parameters(root.get("inputs")).forEach((key, value) -> {
            var port = object(value, "mediaType", "maxBytes", "required");
            if (!(port.get("required") instanceof Boolean required)) throw new IllegalArgumentException("Input required must be boolean");
            inputs.put((String) key, new ServiceExecutionSpec.InputPort(text(port.get("mediaType"), 128), integer(port.get("maxBytes")), required));
        });
        var outputs = new TreeMap<String, ServiceExecutionSpec.OutputPort>();
        parameters(root.get("outputs")).forEach((key, value) -> {
            var port = object(value, "mediaType", "maxBytes");
            outputs.put((String) key, new ServiceExecutionSpec.OutputPort(text(port.get("mediaType"), 128), integer(port.get("maxBytes"))));
        });
        var tolerations = new ArrayList<ServiceExecutionSpec.Toleration>();
        Object rawTolerations = root.containsKey("tolerations") ? root.get("tolerations") : List.of();
        if (!(rawTolerations instanceof List<?> list)) throw new IllegalArgumentException("tolerations must be an array");
        for (Object value : list) {
            var t = fields(value, Set.of("key", "operator", "value", "effect"), Set.of("tolerationSeconds"));
            if (!(t.get("value") instanceof String v)) throw new IllegalArgumentException("Toleration value must be a string");
            tolerations.add(new ServiceExecutionSpec.Toleration(text(t.get("key"), 317), text(t.get("operator"), 8), v,
                text(t.get("effect"), 16), t.containsKey("tolerationSeconds") ? integer(t.get("tolerationSeconds")) : null));
        }
        long timeout = integer(root.get("timeoutSeconds"));
        if (timeout < 1 || timeout > 86400) throw new IllegalArgumentException("Timeout must be 1..86400 seconds");
        return new ServiceExecutionSpec(text(root.get("image"), 512), array(root.get("command")), array(root.get("args")), requirements,
            array(platform.get("architectures")), inputs, outputs, (int) timeout,
            root.containsKey("nodeSelector") ? strings(root.get("nodeSelector")) : Map.of(), tolerations,
            root.containsKey("runtimeClassName") ? text(root.get("runtimeClassName"), 253) : null);
    }
    private static Map<?, ?> fields(Object value, Set<String> required, Set<String> optional) {
        var map = parameters(value); var allowed = new HashSet<>(required); allowed.addAll(optional);
        if (!map.keySet().containsAll(required) || !allowed.containsAll(map.keySet())) throw new IllegalArgumentException("Unexpected execution spec fields");
        return map;
    }
    private static Map<String, String> strings(Object value) {
        var result = new TreeMap<String, String>();
        parameters(value).forEach((key, item) -> {
            if (!(item instanceof String s)) throw new IllegalArgumentException("String value required");
            result.put((String) key, s);
        });
        return result;
    }
    private static List<String> array(Object value) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalArgumentException("String array required");
        return list.stream().map(String.class::cast).toList();
    }
    private static long integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Integer required");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Integer out of range"); }
    }
}
