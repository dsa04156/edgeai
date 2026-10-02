package io.edgeai.app.support;

import io.edgeai.adapters.kubernetes.KubernetesJobCompiler;
import io.edgeai.domain.runtime.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionSpecTest {
    private static final JsonDocuments JSON = new JsonDocuments();
    private String example() throws Exception { return Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")); }
    @SuppressWarnings("unchecked") private Map<String, Object> document() throws Exception {
        return (Map<String, Object>) JSON.decode(example());
    }
    private ServiceExecutionSpec parse(Map<String, Object> value) { return ServiceExecutionInput.parseSpec(JSON.canonical(value)); }
    private RuntimeLaunch launch(boolean node) {
        return new RuntimeLaunch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
            "edgeai-runtimes", "edgeai-runner", "edgeai-claim-test", URI.create("http://edgeai-api.edgeai.svc:18080"),
            node ? UUID.randomUUID() : null, node ? "worker-1" : null);
    }
    @Test void strictConsumerContractPreservesCommandAndComputesResourceSemantics() throws Exception {
        var spec = ServiceExecutionInput.parseSpec(example());
        assertEquals("Burstable", spec.resources().qos()); assertEquals(17825792, spec.workBytes());
        assertEquals(List.of("python3", "/opt/edgeai/examples/linear.py"), spec.command());
        assertThrows(UnsupportedOperationException.class, () -> spec.architectures().add("arm"));
        var root = document(); root.put("qos", "Guaranteed"); assertThrows(IllegalArgumentException.class, () -> parse(root));
        root.remove("qos"); root.put("resources", Map.of("requests", Map.of("cpu", "1", "memory", "1024Mi", "nvidia.com/gpu", "1"),
            "limits", Map.of("cpu", "1000m", "memory", "1Gi", "nvidia.com/gpu", "1")));
        assertEquals("Guaranteed", parse(root).resources().qos());
        root.put("platform", Map.of("os", "windows", "architectures", List.of("amd64")));
        assertThrows(IllegalArgumentException.class, () -> parse(root));
    }
    @Test void malformedExecutionDefinitionsDoNotCompile() throws Exception {
        for (var entry : Map.<String, Object>ofEntries(
                Map.entry("apiVersion", "edgeai/v2"), Map.entry("image", "runner:latest"), Map.entry("command", List.of()),
                Map.entry("timeoutSeconds", 1.5), Map.entry("outputs", Map.of()), Map.entry("args", List.of(1)),
                Map.entry("nodeName", "worker-1"), Map.entry("env", Map.of("SECRET", "forbidden")),
                Map.entry("platform", Map.of("os", "linux", "architectures", List.of("amd64", "amd64"))),
                Map.entry("nodeSelector", Map.of("kubernetes.io/os", "windows")),
                Map.entry("tolerations", List.of(Map.of("key", "gpu", "operator", "Exists", "value", "x", "effect", "NoSchedule"))))
                .entrySet()) {
            var root = document(); root.put(entry.getKey(), entry.getValue());
            assertThrows(IllegalArgumentException.class, () -> parse(root), entry.getKey());
        }
        assertThrows(IllegalArgumentException.class, () -> ServiceExecutionInput.parseSpec("{\"source\":\"synthetic\"}"));
        assertThrows(IllegalArgumentException.class, () -> ServiceExecutionInput.parseSpec(example().replace("\"edgeai/v1\"", "\"edgeai/v1\",\"apiVersion\":\"edgeai/v1\"")));
        var oversized = document(); oversized.put("outputs", Map.of("output", Map.of("mediaType", "application/json", "maxBytes", 268435457)));
        assertThrows(IllegalArgumentException.class, () -> parse(oversized));
    }
    @Test void resourceArithmeticAndExtendedResourcesAreValidatedWithoutFloatingPoint() {
        assertEquals(0, ResourceRequirements.quantity("cpu", "100m").compareTo(ResourceRequirements.quantity("cpu", "0.1")));
        for (String cpu : List.of("-1", "0", "0.0001", "1e4", "Infinity", "NaN"))
            assertThrows(IllegalArgumentException.class, () -> ResourceRequirements.quantity("cpu", cpu));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRequirements(Map.of("cpu", "2", "memory", "1Gi"), Map.of("cpu", "1", "memory", "1Gi")));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRequirements(Map.of("cpu", "1", "memory", "1Gi", "vendor.io/npu", "1"), Map.of("cpu", "1", "memory", "1Gi", "vendor.io/npu", "2")));
        assertThrows(IllegalArgumentException.class, () -> ResourceRequirements.quantity("nvidia.com/gpu", "0.5"));
        assertThrows(IllegalArgumentException.class, () -> ResourceRequirements.quantity("kubernetes.io/gpu", "1"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeNames.qualified("bad_prefix/name"));
    }
    @Test void autoAndNodeRetainSchedulerConstraintsAndNeverAcceptRawPodPrivileges() throws Exception {
        var service = ServiceExecutionInput.parseSpec(example()); var compiler = new KubernetesJobCompiler();
        var auto = launch(false); var node = launch(true);
        var autoJson = JSON.canonical(compiler.compile(service, auto));
        var nodeJson = JSON.canonical(compiler.compile(service, node));
        assertFalse(autoJson.contains("nodeName")); assertFalse(nodeJson.contains("nodeName"));
        assertFalse(autoJson.contains("matchFields")); assertTrue(nodeJson.contains("metadata.name")); assertTrue(nodeJson.contains("worker-1"));
        assertTrue(nodeJson.contains(node.targetNodeId().toString()));
        var job = JSON.decode(nodeJson); var spec = map(map(job).get("spec")); var pod = map(map(spec.get("template")).get("spec"));
        assertEquals("0", spec.get("backoffLimit").toString()); assertEquals("Never", pod.get("restartPolicy"));
        assertEquals(false, pod.get("automountServiceAccountToken")); assertFalse(pod.containsKey("hostNetwork"));
        var identity = ((List<?>) pod.get("volumes")).stream().map(this::map).filter(v -> v.get("name").equals("identity")).findFirst().orElseThrow();
        var projection = map(identity.get("projected"));
        var token = map(map(((List<?>) projection.get("sources")).getFirst()).get("serviceAccountToken"));
        assertEquals("edgeai-runner", token.get("audience"));
        assertEquals("600", token.get("expirationSeconds").toString());
        assertEquals("288", projection.get("defaultMode").toString());
        var container = map(((List<?>) pod.get("containers")).getFirst());
        assertEquals(true, map(container.get("securityContext")).get("readOnlyRootFilesystem"));
        assertEquals(false, map(container.get("securityContext")).get("allowPrivilegeEscalation"));
        assertEquals(List.of("python3", "/opt/edgeai/runner.py"), container.get("command"));
        assertEquals("edgeai-" + node.attemptId(), map(map(job).get("metadata")).get("name"));
        assertEquals(compiler.compile(service, node), compiler.compile(service, node));
        // Retain the exact tested documents for a separate Kubernetes server dry-run gate.
        Files.createDirectories(Path.of("build/runtime-fixtures"));
        Files.writeString(Path.of("build/runtime-fixtures/auto-job.json"), autoJson);
        Files.writeString(Path.of("build/runtime-fixtures/node-job.json"), nodeJson);
    }
    @Test void optionalSchedulingFieldsSurviveCompilationAndOriginsCannotContainSecrets() throws Exception {
        var root = document(); root.put("runtimeClassName", "nvidia"); root.put("nodeSelector", Map.of("edgeai.io/pool", "compute"));
        root.put("tolerations", List.of(Map.of("key", "vendor.io/accelerator", "operator", "Equal", "value", "present", "effect", "NoExecute", "tolerationSeconds", 60)));
        var compiled = JSON.canonical(new KubernetesJobCompiler().compile(parse(root), launch(false)));
        assertTrue(compiled.contains("\"runtimeClassName\":\"nvidia\"")); assertTrue(compiled.contains("\"tolerationSeconds\":60"));
        assertTrue(compiled.contains("edgeai.io/pool"));
        for (String origin : List.of("http://user:password@localhost", "http://localhost/path", "http://localhost?token=secret", "file:///tmp/api"))
            assertThrows(IllegalArgumentException.class, () -> new RuntimeLaunch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "edgeai-runtimes", "edgeai-runner", "claim", URI.create(origin), null, null));
    }
    @Test void automaticTransferExcludesOldNodesWithoutBypassingTheScheduler() throws Exception {
        var original=launch(false);var launch=new RuntimeLaunch(original.runId(),original.taskId(),original.attemptId(),1,original.namespace(),
            original.serviceAccount(),original.claimSecret(),original.controlPlane(),null,null,List.of("old-worker-1","old-worker-2"));
        var job=new KubernetesJobCompiler().compile(ServiceExecutionInput.parseSpec(example()),launch);
        var pod=map(map(map(job.get("spec")).get("template")).get("spec"));assertFalse(pod.containsKey("nodeName"));
        var required=map(map(map(pod.get("affinity")).get("nodeAffinity")).get("requiredDuringSchedulingIgnoredDuringExecution"));
        var terms=(List<?>)required.get("nodeSelectorTerms");assertEquals(1,terms.size());var term=map(terms.getFirst());
        assertEquals(List.of(Map.of("key","metadata.name","operator","NotIn","values",List.of("old-worker-1")),Map.of("key","metadata.name","operator","NotIn","values",List.of("old-worker-2"))),term.get("matchFields"));
        assertEquals(2,((List<?>)term.get("matchExpressions")).size());
        Files.createDirectories(Path.of("build/runtime-fixtures"));Files.writeString(Path.of("build/runtime-fixtures/offload-auto-job.json"),JSON.canonical(job));
    }
    private Map<?, ?> map(Object value) { return (Map<?, ?>) value; }
}
