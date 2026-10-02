package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.runtime.RuntimeLaunch;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
import java.util.*;

/** Pure compilation: no node ranking, bind calls, or network side effects. */
public final class KubernetesJobCompiler {
    public Map<String, Object> compile(ServiceExecutionSpec service, RuntimeLaunch launch) {
        var labels = new TreeMap<String, String>();
        labels.put("app.kubernetes.io/part-of", "edgeai"); labels.put("app.kubernetes.io/managed-by", "edgeai-runtime-controller");
        labels.put("edgeai.io/run-id", launch.runId().toString()); labels.put("edgeai.io/task-id", launch.taskId().toString());
        labels.put("edgeai.io/attempt-id", launch.attemptId().toString()); labels.put("edgeai.io/epoch", Long.toString(launch.epoch()));
        if (launch.targetNodeId() != null) labels.put("edgeai.io/requested-node-uid", launch.targetNodeId().toString());
        var expressions = List.of(
            Map.of("key", "kubernetes.io/os", "operator", "In", "values", List.of("linux")),
            Map.of("key", "kubernetes.io/arch", "operator", "In", "values", service.architectures()));
        var term = new LinkedHashMap<String, Object>(); term.put("matchExpressions", expressions);
        if (launch.targetNodeName() != null)
            term.put("matchFields", List.of(Map.of("key", "metadata.name", "operator", "In", "values", List.of(launch.targetNodeName()))));
        var pod = new LinkedHashMap<String, Object>();
        pod.put("restartPolicy", "Never"); pod.put("serviceAccountName", launch.serviceAccount()); pod.put("automountServiceAccountToken", false);
        pod.put("enableServiceLinks", false); pod.put("terminationGracePeriodSeconds", 10);
        pod.put("securityContext", Map.of("runAsNonRoot", true, "runAsUser", 10001, "runAsGroup", 10001, "fsGroup", 10001,
            "seccompProfile", Map.of("type", "RuntimeDefault")));
        pod.put("affinity", Map.of("nodeAffinity", Map.of("requiredDuringSchedulingIgnoredDuringExecution", Map.of("nodeSelectorTerms", List.of(term)))));
        if (!service.nodeSelector().isEmpty()) pod.put("nodeSelector", service.nodeSelector());
        if (service.runtimeClassName() != null) pod.put("runtimeClassName", service.runtimeClassName());
        if (!service.tolerations().isEmpty()) pod.put("tolerations", service.tolerations().stream().map(t -> {
            var value = new LinkedHashMap<String, Object>(); value.put("key", t.key()); value.put("operator", t.operator());
            value.put("value", t.value()); value.put("effect", t.effect());
            if (t.seconds() != null) value.put("tolerationSeconds", t.seconds()); return value;
        }).toList());
        pod.put("volumes", List.of(Map.of("name", "work", "emptyDir", Map.of("sizeLimit", Long.toString(service.workBytes()))),
            Map.of("name", "claim", "secret", Map.of("secretName", launch.claimSecret(), "defaultMode", 288, "items", List.of(Map.of("key", "token", "path", "token")))),
            Map.of("name","identity","projected",Map.of("defaultMode",288,"sources",List.of(Map.of("serviceAccountToken",
                Map.of("audience",KubernetesRuntimeGateway.AUDIENCE,"expirationSeconds",600,"path","token")))))));
        var container = new LinkedHashMap<String, Object>();
        container.put("name", "runner"); container.put("image", service.image()); container.put("imagePullPolicy", "IfNotPresent");
        container.put("command", List.of("python3", "/opt/edgeai/runner.py"));
        container.put("resources", Map.of("requests", service.resources().requests(), "limits", service.resources().limits()));
        container.put("securityContext", Map.of("allowPrivilegeEscalation", false, "readOnlyRootFilesystem", true, "capabilities", Map.of("drop", List.of("ALL"))));
        container.put("env", List.of(
            Map.of("name", "EDGEAI_CONTROL_PLANE_URL", "value", launch.controlPlane().toString()),
            Map.of("name", "EDGEAI_ATTEMPT_ID", "value", launch.attemptId().toString()),
            Map.of("name", "EDGEAI_ATTEMPT_EPOCH", "value", Long.toString(launch.epoch())),
            Map.of("name", "EDGEAI_CLAIM_FILE", "value", "/var/run/edgeai/token"),
            Map.of("name", "EDGEAI_POD_TOKEN_FILE", "value", "/var/run/edgeai-identity/token"),
            Map.of("name", "EDGEAI_WORK_DIR", "value", "/work"),
            Map.of("name", "TMPDIR", "value", "/work/tmp"),
            Map.of("name", "PYTHONDONTWRITEBYTECODE", "value", "1"),
            Map.of("name", "EDGEAI_POD_UID", "valueFrom", Map.of("fieldRef", Map.of("fieldPath", "metadata.uid")))));
        container.put("volumeMounts", List.of(Map.of("name", "work", "mountPath", "/work"), Map.of("name", "claim", "mountPath", "/var/run/edgeai", "readOnly", true),
            Map.of("name","identity","mountPath","/var/run/edgeai-identity","readOnly",true)));
        pod.put("containers", List.of(container));
        return Map.of("apiVersion", "batch/v1", "kind", "Job",
            "metadata", Map.of("name", launch.jobName(), "namespace", launch.namespace(), "labels", labels),
            "spec", Map.of("parallelism", 1, "completions", 1, "backoffLimit", 0, "activeDeadlineSeconds", service.timeoutSeconds(),
                "template", Map.of("metadata", Map.of("labels", labels), "spec", pod)));
    }
}
