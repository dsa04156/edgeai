package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.runtime.ServiceExecutionSpec;
import io.edgeai.domain.vd.VDRuntimeLaunch;
import java.util.*;

/** A persistent VD Pod, independent of any Workflow/Task/Attempt or Kubernetes Job. */
public final class KubernetesVDPodCompiler {
    public static final String AUDIENCE="edgeai-vd";
    public Map<String,Object> compile(ServiceExecutionSpec service,VDRuntimeLaunch launch) {
        var labels=new TreeMap<String,String>();
        labels.put("app.kubernetes.io/part-of","edgeai");labels.put("app.kubernetes.io/managed-by","edgeai-vd-controller");
        labels.put("edgeai.io/vd-id",launch.vdId().toString());labels.put("edgeai.io/vd-runtime-id",launch.runtimeId().toString());
        labels.put("edgeai.io/generation",Long.toString(launch.generation()));
        if(launch.targetNodeId()!=null)labels.put("edgeai.io/requested-node-uid",launch.targetNodeId().toString());
        var term=new LinkedHashMap<String,Object>();
        term.put("matchExpressions",List.of(Map.of("key","kubernetes.io/os","operator","In","values",List.of("linux")),
            Map.of("key","kubernetes.io/arch","operator","In","values",service.architectures())));
        if(launch.targetNodeName()!=null)term.put("matchFields",List.of(Map.of("key","metadata.name","operator","In","values",List.of(launch.targetNodeName()))));
        var pod=new LinkedHashMap<String,Object>();
        pod.put("restartPolicy","Never");pod.put("serviceAccountName",launch.serviceAccount());pod.put("automountServiceAccountToken",false);
        pod.put("enableServiceLinks",false);pod.put("terminationGracePeriodSeconds",10);
        pod.put("securityContext",Map.of("runAsNonRoot",true,"runAsUser",10001,"runAsGroup",10001,"fsGroup",10001,"seccompProfile",Map.of("type","RuntimeDefault")));
        pod.put("affinity",Map.of("nodeAffinity",Map.of("requiredDuringSchedulingIgnoredDuringExecution",Map.of("nodeSelectorTerms",List.of(term)))));
        if(!service.nodeSelector().isEmpty())pod.put("nodeSelector",service.nodeSelector());
        if(service.runtimeClassName()!=null)pod.put("runtimeClassName",service.runtimeClassName());
        if(!service.tolerations().isEmpty())pod.put("tolerations",service.tolerations().stream().map(t->{
            var item=new LinkedHashMap<String,Object>();item.put("key",t.key());item.put("operator",t.operator());item.put("value",t.value());item.put("effect",t.effect());
            if(t.seconds()!=null)item.put("tolerationSeconds",t.seconds());return item;
        }).toList());
        pod.put("volumes",List.of(
            Map.of("name","work","emptyDir",Map.of("sizeLimit",Long.toString(16777216L+service.workBytes()*launch.maxConcurrentTasks()))),
            Map.of("name","claim","secret",Map.of("secretName",launch.claimSecret(),"defaultMode",288,"items",List.of(Map.of("key","token","path","token")))),
            Map.of("name","identity","projected",Map.of("defaultMode",288,"sources",List.of(Map.of("serviceAccountToken",Map.of("audience",AUDIENCE,"expirationSeconds",600,"path","token")))))));
        var container=new LinkedHashMap<String,Object>();
        container.put("name","vd-supervisor");container.put("image",service.image());container.put("imagePullPolicy","IfNotPresent");
        container.put("command",List.of("python3","/opt/edgeai/vd.py"));
        container.put("resources",Map.of("requests",service.resources().requests(),"limits",service.resources().limits()));
        container.put("securityContext",Map.of("allowPrivilegeEscalation",false,"readOnlyRootFilesystem",true,"capabilities",Map.of("drop",List.of("ALL"))));
        container.put("env",List.of(
            env("EDGEAI_VD_ID",launch.vdId().toString()),env("EDGEAI_VD_RUNTIME_ID",launch.runtimeId().toString()),
            env("EDGEAI_VD_GENERATION",Long.toString(launch.generation())),env("EDGEAI_VD_MAX_CONCURRENT_TASKS",Integer.toString(launch.maxConcurrentTasks())),
            env("EDGEAI_VD_STARTUP_SECONDS",Integer.toString(launch.startupSeconds())),env("EDGEAI_VD_DRAIN_SECONDS",Integer.toString(launch.drainSeconds())),
            env("EDGEAI_CONTROL_PLANE_URL",launch.controlPlane().toString()),env("EDGEAI_VD_CLAIM_FILE","/var/run/edgeai/token"),
            env("EDGEAI_POD_TOKEN_FILE","/var/run/edgeai-identity/token"),env("EDGEAI_WORK_DIR","/work"),env("PYTHONDONTWRITEBYTECODE","1"),
            Map.of("name","EDGEAI_POD_UID","valueFrom",Map.of("fieldRef",Map.of("fieldPath","metadata.uid")))));
        container.put("volumeMounts",List.of(Map.of("name","work","mountPath","/work"),Map.of("name","claim","mountPath","/var/run/edgeai","readOnly",true),
            Map.of("name","identity","mountPath","/var/run/edgeai-identity","readOnly",true)));
        var check=Map.of("command",List.of("python3","/opt/edgeai/vd.py","--ready"));
        container.put("readinessProbe",Map.of("exec",check,"periodSeconds",2,"timeoutSeconds",2,"failureThreshold",1));
        container.put("startupProbe",Map.of("exec",check,"periodSeconds",2,"timeoutSeconds",2,"failureThreshold",(launch.startupSeconds()+1)/2+1));
        KubernetesTrustBundle.mount(pod,container,launch.caConfigMap());
        pod.put("containers",List.of(container));
        return Map.of("apiVersion","v1","kind","Pod","metadata",Map.of("name",launch.podName(),"namespace",launch.namespace(),"labels",labels),"spec",pod);
    }
    private Map<String,String> env(String name,String value) { return Map.of("name",name,"value",value); }
}
