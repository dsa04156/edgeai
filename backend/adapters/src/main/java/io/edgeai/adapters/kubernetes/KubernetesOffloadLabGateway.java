package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.runtime.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.runtime.RuntimeGatewayException.Reason.*;

/**
 * Offload test console objects in the owned runtime namespace. The load Job always runs the fixed CPU or GPU program of the
 * configured, digest-pinned image (a GPU load requests exactly one unit of the inventory-advertised NVIDIA resource); every list/delete is scoped by the managed-by and session labels and deletes carry UID preconditions.
 */
public final class KubernetesOffloadLabGateway implements OffloadLabGateway {
    static final String MANAGED_BY="edgeai-offload-lab",SESSION="edgeai.io/offload-lab-session",GENERATION="edgeai.io/offload-lab-generation";
    private static final String OWNED="app.kubernetes.io/part-of=edgeai,app.kubernetes.io/managed-by="+MANAGED_BY;
    private final KubernetesRuntimeHttp http;
    private final String namespace,serviceAccount,image,jobs,pods;
    private final JsonMapper json=new JsonMapper();
    public KubernetesOffloadLabGateway(String url,String tokenFile,String caFile,String namespace,String serviceAccount,String image) {
        RuntimeNames.dns(namespace,63);RuntimeNames.dns(serviceAccount,253);
        if(!image.matches("[a-z0-9][a-z0-9._:/-]{0,200}@sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("Offload lab image must be pinned by digest");
        this.namespace=namespace;this.serviceAccount=serviceAccount;this.image=image;
        http=new KubernetesRuntimeHttp(url,tokenFile,caFile);
        jobs="/apis/batch/v1/namespaces/"+namespace+"/jobs";pods="/api/v1/namespaces/"+namespace+"/pods";
    }
    public static String jobName(UUID session,int generation){return "edgeai-lab-load-"+session+"-"+generation;}
    @Override public String loadImage(){return image;}
    private JsonNode read(String path,boolean missing) {
        var response=http.request("GET",path,null,Duration.ofSeconds(5));
        if(missing && response.status()==404)return null;
        if(response.status()!=200)throw new RuntimeGatewayException(UNAVAILABLE);
        try{return json.readTree(response.body());}catch(RuntimeException e){throw new RuntimeGatewayException(UNAVAILABLE);}
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private Map<String,String> labels(Load load) {
        return new TreeMap<>(Map.of("app.kubernetes.io/part-of","edgeai","app.kubernetes.io/managed-by",MANAGED_BY,"edgeai.io/offload-lab-role","load",
            SESSION,load.sessionId().toString(),GENERATION,Integer.toString(load.generation())));
    }
    Map<String,Object> job(Load load) {
        RuntimeNames.dns(load.nodeName(),253);
        if(load.workers()<1 || load.workers()>16 || load.seconds()<30 || load.seconds()>1800 || load.generation()<1)throw new IllegalArgumentException("Load out of range");
        boolean gpu=load.gpuResource()!=null;
        if(gpu && (!GPU_RESOURCES.contains(load.gpuResource()) || load.workers()!=1))throw new IllegalArgumentException("GPU load needs one NVIDIA GPU resource and one worker");
        var container=new LinkedHashMap<String,Object>();
        container.put("name","load");container.put("image",image);container.put("imagePullPolicy","IfNotPresent");
        if(gpu) {
            container.put("command",List.of("python3","/opt/edgeai/examples/offload_lab_gpu_load.py"));
            container.put("args",List.of("--seconds",Integer.toString(load.seconds())));
            container.put("resources",Map.of("requests",Map.of("cpu","100m","memory","64Mi",load.gpuResource(),"1"),"limits",Map.of("cpu","1","memory","512Mi",load.gpuResource(),"1")));
        } else {
            container.put("command",List.of("python3","/opt/edgeai/examples/offload_lab_load.py"));
            container.put("args",List.of("--workers",Integer.toString(load.workers()),"--seconds",Integer.toString(load.seconds())));
            container.put("resources",Map.of("requests",Map.of("cpu","100m","memory","32Mi"),"limits",Map.of("cpu",Integer.toString(load.workers()),"memory","128Mi")));
        }
        container.put("securityContext",Map.of("allowPrivilegeEscalation",false,"readOnlyRootFilesystem",true,"capabilities",Map.of("drop",List.of("ALL"))));
        container.put("env",List.of(Map.of("name","PYTHONDONTWRITEBYTECODE","value","1")));
        var pod=new LinkedHashMap<String,Object>();
        pod.put("restartPolicy","Never");pod.put("serviceAccountName",serviceAccount);pod.put("automountServiceAccountToken",false);
        pod.put("enableServiceLinks",false);pod.put("terminationGracePeriodSeconds",5);
        pod.put("securityContext",Map.of("runAsNonRoot",true,"runAsUser",10001,"runAsGroup",10001,"seccompProfile",Map.of("type","RuntimeDefault")));
        pod.put("affinity",Map.of("nodeAffinity",Map.of("requiredDuringSchedulingIgnoredDuringExecution",Map.of("nodeSelectorTerms",List.of(Map.of(
            "matchFields",List.of(Map.of("key","metadata.name","operator","In","values",List.of(load.nodeName())))))))));
        pod.put("containers",List.of(container));
        // The program exits on its own at `seconds`; activeDeadlineSeconds is the cluster-side bound if it does not.
        return Map.of("apiVersion","batch/v1","kind","Job",
            "metadata",Map.of("name",jobName(load.sessionId(),load.generation()),"namespace",namespace,"labels",labels(load)),
            "spec",Map.of("parallelism",1,"completions",1,"backoffLimit",0,"activeDeadlineSeconds",load.seconds()+30,"ttlSecondsAfterFinished",600,
                "template",Map.of("metadata",Map.of("labels",labels(load)),"spec",pod)));
    }
    private void owned(JsonNode m,Map<String,String> expected) {
        expected.forEach((key,value)->{if(!value.equals(m.path("labels").path(key).asText()))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);});
        if(!m.path("namespace").asText().equals(namespace))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
    }
    @Override public UUID ensureLoad(Load load) {
        var spec=job(load);String name=jobName(load.sessionId(),load.generation());
        var existing=read(jobs+"/"+name,true);
        if(existing==null) {
            var response=http.request("POST",jobs,spec,Duration.ofSeconds(5));
            if(response.status()!=201 && response.status()!=409)throw new RuntimeGatewayException(UNAVAILABLE);
            existing=read(jobs+"/"+name,false);
        }
        owned(existing.path("metadata"),labels(load));
        var container=existing.path("spec").path("template").path("spec").path("containers").path(0);
        if(!container.path("image").asText().equals(image) || !existing.path("spec").path("template").path("spec").path("serviceAccountName").asText().equals(serviceAccount))
            throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        try{return UUID.fromString(existing.path("metadata").path("uid").asText());}catch(RuntimeException e){throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);}
    }
    private List<JsonNode> list(String collection,String selector) {
        var root=read(collection+"?labelSelector="+encode(selector)+"&limit=200",false);
        if(!root.path("items").isArray() || !root.path("metadata").path("continue").asText("").isEmpty())throw new RuntimeGatewayException(UNAVAILABLE);
        var items=new ArrayList<JsonNode>();root.path("items").forEach(items::add);return items;
    }
    @Override public boolean deleteLoad(UUID sessionId,int throughGeneration) {
        String selector=OWNED+","+SESSION+"="+sessionId;
        var remaining=list(jobs,selector).stream().filter(j->covered(j,throughGeneration)).toList();
        for(var job:remaining) {
            var m=job.path("metadata");owned(m,Map.of(SESSION,sessionId.toString(),"app.kubernetes.io/managed-by",MANAGED_BY));
            if(m.has("deletionTimestamp"))continue;
            var response=http.request("DELETE",jobs+"/"+encode(m.path("name").asText()),Map.of("apiVersion","v1","kind","DeleteOptions","propagationPolicy","Foreground",
                "gracePeriodSeconds",5,"preconditions",Map.of("uid",m.path("uid").asText())),Duration.ofSeconds(5));
            if(!Set.of(200,202,404,409).contains(response.status()))throw new RuntimeGatewayException(UNAVAILABLE);
        }
        // Done only when Kubernetes no longer reports any Job or Pod of those generations: the load has physically stopped.
        return remaining.isEmpty() && list(pods,selector).stream().noneMatch(p->covered(p,throughGeneration));
    }
    /** Our objects always carry the generation label; one without a valid label is treated as covered (cleaned up). */
    private static boolean covered(JsonNode item,int throughGeneration) {
        try{return Integer.parseInt(item.path("metadata").path("labels").path(GENERATION).asText())<=throughGeneration;}
        catch(NumberFormatException e){return true;}
    }
    @Override public List<Pod> pods(UUID sessionId,UUID runId) {
        var result=new ArrayList<Pod>();
        for(var item:list(pods,"app.kubernetes.io/part-of=edgeai,app.kubernetes.io/managed-by=edgeai-runtime-controller,edgeai.io/run-id="+runId))result.add(pod("MODEL",item));
        for(var item:list(pods,OWNED+","+SESSION+"="+sessionId))result.add(pod("LOAD",item));
        return result;
    }
    private Pod pod(String role,JsonNode item) {
        var m=item.path("metadata");var status=item.path("status");var labels=m.path("labels");
        boolean ready=false;for(var c:status.path("conditions"))if(c.path("type").asText().equals("Ready") && c.path("status").asText().equals("True"))ready=true;
        String reason=status.path("reason").asText(null);
        for(var c:status.path("containerStatuses")){var s=c.path("state");
            if(s.has("waiting"))reason=s.path("waiting").path("reason").asText(reason);else if(s.has("terminated"))reason=s.path("terminated").path("reason").asText(reason);}
        return new Pod(role,m.path("name").asText(),uuid(m.path("uid").asText()),item.path("spec").path("nodeName").asText(null),status.path("phase").asText("Unknown"),ready,
            uuid(labels.path("edgeai.io/attempt-id").asText(null)),labels.has(GENERATION)?labels.path(GENERATION).asInt():null,
            time(m.path("creationTimestamp")),time(status.path("startTime")),time(m.path("deletionTimestamp")),reason);
    }
    private static UUID uuid(String value){try{return value==null?null:UUID.fromString(value);}catch(RuntimeException e){return null;}}
    private static Instant time(JsonNode value){try{return value.isMissingNode() || value.isNull()?null:Instant.parse(value.asText());}catch(RuntimeException e){return null;}}
    @Override public Set<UUID> loadSessions() {
        var result=new HashSet<UUID>();
        for(var job:list(jobs,OWNED)){var id=uuid(job.path("metadata").path("labels").path(SESSION).asText(null));if(id!=null)result.add(id);}
        return result;
    }
}
