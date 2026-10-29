package io.edgeai.app.support;

import io.edgeai.domain.resourceprofile.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

/** Strict request parsing for resource profiles and analyses; unknown fields, fractions and NaN are rejected. */
public final class ResourceProfileInput {
    public static final int MAX_BYTES = 65536;
    private ResourceProfileInput() {}
    public enum Basis { MEASURED, RECOMMENDED, MANUAL }
    public record Confirmed(ResourceRange range, Basis basis) {}
    public record Save(UUID serviceProfileVersionId, UUID analysisId, Map<String, String> inputConditions,
                       List<String> architectures, Map<String, String> runtime, Confirmed cpu, Confirmed memory,
                       List<AcceleratorRequirement> accelerators, String note) {}
    public record Analyze(UUID serviceProfileVersionId, WorkloadTarget target, String executionRef, Instant start, Instant end,
                          List<DeviceRef> devices, Integer historyHours) {}

    public static Save save(String body) {
        var map = fields(JSON.parse(body, MAX_BYTES), Set.of("serviceProfileVersionId", "resources"),
            Set.of("analysisId", "inputConditions", "compatibility", "note"));
        JSON.boundedCanonical(map, MAX_BYTES);
        var resources = fields(map.get("resources"), Set.of("cpu", "memory"), Set.of("accelerators"));
        var cpu = fields(resources.get("cpu"), Set.of("minMillicores", "requestMillicores", "maxMillicores", "basis"), Set.of());
        var memory = fields(resources.get("memory"), Set.of("minBytes", "requestBytes", "maxBytes", "basis"), Set.of());
        var accelerators = new ArrayList<AcceleratorRequirement>();
        for (Object item : list(orEmpty(resources.get("accelerators")), 8)) {
            var a = fields(item, Set.of("kind", "resourceName", "unit", "min", "request", "max"), Set.of("deviceMemoryBytes", "vendor", "models"));
            var models = new ArrayList<String>();
            for (Object model : list(orEmpty(a.get("models")), 16)) models.add(text(model, 100));
            accelerators.add(new AcceleratorRequirement(AcceleratorRequirement.Kind.valueOf(text(a.get("kind"), 8)), text(a.get("resourceName"), 253),
                AcceleratorRequirement.Unit.valueOf(text(a.get("unit"), 16)),
                new ResourceRange(integer(a.get("min")), integer(a.get("request")), integer(a.get("max"))),
                a.get("deviceMemoryBytes") == null ? null : integer(a.get("deviceMemoryBytes")),
                a.get("vendor") == null ? null : text(a.get("vendor"), 100), models));
        }
        var names = new HashSet<String>();
        for (var a : accelerators) if (!names.add(a.resourceName())) throw new IllegalArgumentException("Accelerator resource names must be unique");
        var conditions = new TreeMap<String, String>();
        var rawConditions = map.get("inputConditions") == null ? Map.of() : object(map.get("inputConditions"));
        if (rawConditions.size() > 32) throw new IllegalArgumentException("At most 32 input conditions");
        for (var entry : rawConditions.entrySet()) {
            String key = text(entry.getKey(), 64);
            if (!key.matches("[A-Za-z][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("Input condition keys are identifiers");
            conditions.put(key, text(entry.getValue(), 200));
        }
        var architectures = new ArrayList<String>();
        Map<String, String> runtime = null;
        if (map.get("compatibility") != null) {
            var compatibility = fields(map.get("compatibility"), Set.of(), Set.of("architectures", "runtime"));
            for (Object arch : list(orEmpty(compatibility.get("architectures")), 2)) {
                String value = text(arch, 8);
                if (!Set.of("amd64", "arm64").contains(value) || architectures.contains(value)) throw new IllegalArgumentException("Architectures are amd64/arm64");
                architectures.add(value);
            }
            if (compatibility.get("runtime") != null) {
                var r = fields(compatibility.get("runtime"), Set.of("name"), Set.of("version"));
                runtime = new TreeMap<>();
                runtime.put("name", text(r.get("name"), 64));
                if (r.get("version") != null) runtime.put("version", text(r.get("version"), 64));
            }
        }
        Collections.sort(architectures);
        return new Save(WorkflowInput.uuid(map.get("serviceProfileVersionId")),
            map.get("analysisId") == null ? null : WorkflowInput.uuid(map.get("analysisId")), conditions, architectures, runtime,
            new Confirmed(ResourceRange.cpu(integer(cpu.get("minMillicores")), integer(cpu.get("requestMillicores")), integer(cpu.get("maxMillicores"))), basis(cpu.get("basis"))),
            new Confirmed(ResourceRange.memory(integer(memory.get("minBytes")), integer(memory.get("requestBytes")), integer(memory.get("maxBytes"))), basis(memory.get("basis"))),
            List.copyOf(accelerators), map.get("note") == null ? null : text(map.get("note"), 500));
    }

    public static Analyze analyze(String body) {
        var map = fields(JSON.parse(body, MAX_BYTES), Set.of("serviceProfileVersionId", "target", "window"),
            Set.of("executionRef", "devices", "recommendation"));
        var t = fields(map.get("target"), Set.of("namespace", "kind", "name", "container"), Set.of("pod", "podUid"));
        var target = new WorkloadTarget(text(t.get("namespace"), 63), WorkloadTarget.Kind.valueOf(text(t.get("kind"), 16)),
            text(t.get("name"), 253), text(t.get("container"), 63), t.get("pod") == null ? null : text(t.get("pod"), 253),
            t.get("podUid") == null ? null : WorkflowInput.uuid(t.get("podUid")));
        var window = fields(map.get("window"), Set.of("start", "end"), Set.of());
        var devices = new ArrayList<DeviceRef>();
        for (Object item : list(orEmpty(map.get("devices")), 8)) {
            var d = fields(item, Set.of("node", "kind", "device"), Set.of());
            var device = new DeviceRef(text(d.get("node"), 253), AcceleratorRequirement.Kind.valueOf(text(d.get("kind"), 8)), text(d.get("device"), 128));
            if (devices.contains(device)) throw new IllegalArgumentException("Duplicate device");
            devices.add(device);
        }
        Integer history = null;
        if (map.get("recommendation") != null) {
            long hours = integer(fields(map.get("recommendation"), Set.of("historyHours"), Set.of()).get("historyHours"));
            if (hours < 1 || hours > 336) throw new IllegalArgumentException("KRR history must be 1-336 hours");
            history = (int) hours;
        }
        String executionRef = map.get("executionRef") == null ? null : text(map.get("executionRef"), 128);
        if (executionRef != null && !executionRef.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) throw new IllegalArgumentException("Invalid execution reference");
        return new Analyze(WorkflowInput.uuid(map.get("serviceProfileVersionId")), target, executionRef,
            Instant.parse(text(window.get("start"), 40)), Instant.parse(text(window.get("end"), 40)), List.copyOf(devices), history);
    }

    static Object orEmpty(Object value) { return value == null ? List.of() : value; }
    static Basis basis(Object value) { return Basis.valueOf(text(value, 16)); }
    static long integer(Object value) {
        // JSON integers only: fractions, strings ("NaN", "100m") and non-finite tokens never become quantities.
        if (!(value instanceof BigInteger || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Integer quantity required");
        try { return new BigInteger(value.toString()).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Quantity out of range"); }
    }
    static String text(Object value, int max) { return WorkflowInput.text(value, max); }
    static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Object required");
        return map;
    }
    static List<?> list(Object value, int max) {
        if (!(value instanceof List<?> list) || list.size() > max) throw new IllegalArgumentException("Bounded array required");
        return list;
    }
    static Map<?, ?> fields(Object value, Set<String> required, Set<String> optional) {
        var map = object(value);
        for (Object key : map.keySet()) if (!required.contains(key) && !optional.contains(key)) throw new IllegalArgumentException("Unexpected field");
        for (String key : required) if (map.get(key) == null) throw new IllegalArgumentException("Missing field");
        return map;
    }
}
