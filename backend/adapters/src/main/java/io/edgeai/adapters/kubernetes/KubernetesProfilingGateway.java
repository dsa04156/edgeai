package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.aimodel.ProfilingGateway;
import io.edgeai.domain.aimodel.ProfilingJob;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import io.edgeai.domain.runtime.RuntimeNames;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.runtime.RuntimeGatewayException.Reason.*;

/**
 * Model profiling Jobs in one dedicated namespace. A Job is created from a validated {@link ProfilingJob} only, always
 * suspended, and started by a UID- and resourceVersion-conditioned resume once the run durably owns it. An existing Job of
 * the run's name is adopted only when its labels, its canonical spec digest annotation and every field of its stored spec
 * are what this run intends (Kubernetes defaults and quantity rewrites excepted). Every list is scoped by the managed-by
 * label and every delete carries the Job UID as precondition. Each call is bounded as a whole exchange.
 */
public final class KubernetesProfilingGateway implements ProfilingGateway, AutoCloseable {
    static final String MANAGED_BY="edgeai-model-profiler",RUN="edgeai.io/model-profiling-run",MODEL="edgeai.io/ai-model",
        SPEC_DIGEST="edgeai.io/model-profiling-spec-digest";
    private static final String OWNED="app.kubernetes.io/part-of=edgeai,app.kubernetes.io/managed-by="+MANAGED_BY;
    public static final String TMP_SIZE="256Mi",EPHEMERAL_STORAGE="1Gi";
    public static final int TTL_SECONDS=300;
    private final KubernetesRuntimeHttp http;
    private final String namespace,serviceAccount,jobs,pods;
    private final Duration timeout;
    private final JsonMapper json=new JsonMapper();

    public KubernetesProfilingGateway(String url,String tokenFile,String caFile,String namespace,String serviceAccount) {
        this(url,tokenFile,caFile,namespace,serviceAccount,Duration.ofSeconds(5));
    }
    KubernetesProfilingGateway(String url,String tokenFile,String caFile,String namespace,String serviceAccount,Duration timeout) {
        RuntimeNames.dns(namespace,63);RuntimeNames.dns(serviceAccount,253);
        this.namespace=namespace;this.serviceAccount=serviceAccount;this.timeout=timeout;
        http=new KubernetesRuntimeHttp(url,tokenFile,caFile);
        jobs="/apis/batch/v1/namespaces/"+namespace+"/jobs";pods="/api/v1/namespaces/"+namespace+"/pods";
    }
    @Override public String namespace(){return namespace;}
    @Override public void close(){http.close();}

    private static Map<String,String> labels(ProfilingJob job) {
        return new TreeMap<>(Map.of("app.kubernetes.io/part-of","edgeai","app.kubernetes.io/managed-by",MANAGED_BY,
            RUN,job.runId().toString(),MODEL,job.modelId().toString()));
    }
    static String cpu(long millicores){return millicores+"m";}
    private static Map<String,Object> quantities(ProfilingJob job) {
        var map=new TreeMap<String,Object>();
        map.put("cpu",cpu(job.cpuMillicores()));map.put("memory",Long.toString(job.memoryBytes()));map.put("ephemeral-storage",EPHEMERAL_STORAGE);
        if(job.acceleratorResource()!=null)map.put(job.acceleratorResource(),Integer.toString(job.acceleratorCount()));
        return map;
    }
    /** The Job exactly as intended, without the digest annotation (which is computed over this map). */
    Map<String,Object> spec(ProfilingJob job) {
        var container=new LinkedHashMap<String,Object>();
        container.put("name",ProfilingJob.CONTAINER);container.put("image",job.image());container.put("imagePullPolicy","IfNotPresent");
        if(job.command()!=null)container.put("command",job.command());
        if(job.args()!=null)container.put("args",job.args());
        // Requests equal limits: the test allocation is exactly what the measured container may use (extended resources require it).
        container.put("resources",Map.of("requests",quantities(job),"limits",quantities(job)));
        if(job.port()!=null)container.put("ports",List.of(Map.of("name","inference","containerPort",job.port(),"protocol","TCP")));
        else container.put("env",List.of(Map.of("name","EDGEAI_PROFILING_DURATION_SECONDS","value",Integer.toString(job.durationSeconds()))));
        container.put("securityContext",Map.of("allowPrivilegeEscalation",false,"readOnlyRootFilesystem",true,"privileged",false,
            "capabilities",Map.of("drop",List.of("ALL"))));
        container.put("volumeMounts",List.of(Map.of("name","tmp","mountPath","/tmp")));
        var pod=new LinkedHashMap<String,Object>();
        pod.put("restartPolicy","Never");pod.put("serviceAccountName",serviceAccount);pod.put("automountServiceAccountToken",false);
        pod.put("enableServiceLinks",false);pod.put("terminationGracePeriodSeconds",10);
        // The image's own (numeric) user runs it; an image that would run as root is refused by the kubelet, not started.
        pod.put("securityContext",Map.of("runAsNonRoot",true,"seccompProfile",Map.of("type","RuntimeDefault")));
        pod.put("nodeSelector",new TreeMap<>(Map.of("kubernetes.io/os","linux","kubernetes.io/arch",job.architecture())));
        // The scheduler places the Pod (resources, taints, device plugins); the affinity only restricts it to the chosen node.
        pod.put("affinity",Map.of("nodeAffinity",Map.of("requiredDuringSchedulingIgnoredDuringExecution",Map.of("nodeSelectorTerms",List.of(Map.of(
            "matchFields",List.of(Map.of("key","metadata.name","operator","In","values",List.of(job.nodeName())))))))));
        pod.put("containers",List.of(container));
        pod.put("volumes",List.of(Map.of("name","tmp","emptyDir",Map.of("sizeLimit",TMP_SIZE))));
        var spec=new LinkedHashMap<String,Object>();
        // Created suspended: the Job controller makes no Pod until resume(), so a create that lands after a cancel runs nothing.
        spec.put("suspend",true);
        spec.put("parallelism",1);spec.put("completions",1);spec.put("backoffLimit",0);
        spec.put("activeDeadlineSeconds",job.activeDeadlineSeconds());spec.put("ttlSecondsAfterFinished",TTL_SECONDS);
        spec.put("template",Map.of("metadata",Map.of("labels",labels(job)),"spec",pod));
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("name",ProfilingJob.jobName(job.runId()));metadata.put("namespace",namespace);metadata.put("labels",labels(job));
        var result=new LinkedHashMap<String,Object>();
        result.put("apiVersion","batch/v1");result.put("kind","Job");result.put("metadata",metadata);result.put("spec",spec);
        return result;
    }
    /** SHA-256 of the canonical JSON (every nested map key-sorted), so every JVM computes the same digest for the same Job. */
    String digest(Map<String,Object> spec) {
        try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(spec))));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static Object canonical(Object value) {
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put((String)k,canonical(v)));return sorted;}
        if(value instanceof List<?> list){var items=new ArrayList<Object>();for(var item:list)items.add(canonical(item));return items;}
        return value;
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> job(ProfilingJob job) {
        var spec=spec(job);String digest=digest(spec);
        ((Map<String,Object>)spec.get("metadata")).put("annotations",Map.of(SPEC_DIGEST,digest));
        return spec;
    }

    private JsonNode read(String path,boolean missing) {
        var response=http.request("GET",path,null,timeout);
        if(missing && response.status()==404)return null;
        if(response.status()!=200)throw new RuntimeGatewayException(UNAVAILABLE);
        try{return json.readTree(response.body());}catch(RuntimeException e){throw new RuntimeGatewayException(UNAVAILABLE);}
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}

    @Override public UUID ensureJob(ProfilingJob job) {
        var intended=job(job);String name=ProfilingJob.jobName(job.runId());
        var existing=read(jobs+"/"+name,true);
        if(existing==null) {
            var response=http.request("POST",jobs,intended,timeout);
            if(response.status()!=201 && response.status()!=409)throw new RuntimeGatewayException(UNAVAILABLE);
            existing=read(jobs+"/"+name,false);
        }
        same(existing,job);
        try{return UUID.fromString(existing.path("metadata").path("uid").asText());}catch(RuntimeException e){throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);}
    }
    /**
     * A Job of this name that is not exactly the intended one is never adopted (nor measured, nor reported as ours). The
     * digest annotation alone proves nothing (it can be copied onto another spec), so every stored field is compared with
     * the intended Job: quantities in base units, and only the fields Kubernetes itself fills in on create may be extra.
     */
    void same(JsonNode existing,ProfilingJob job) {
        var m=existing.path("metadata");
        var intended=job(job);
        if(!m.path("namespace").asText().equals(namespace) || !m.path("name").asText().equals(ProfilingJob.jobName(job.runId())))throw conflict();
        if(!digest(spec(job)).equals(m.path("annotations").path(SPEC_DIGEST).asText()))throw conflict();
        compare(json.valueToTree(canonical(((Map<?,?>)intended.get("metadata")).get("labels"))),m.path("labels"),"metadata","labels",existing);
        compare(json.valueToTree(canonical(intended.get("spec"))),existing.path("spec"),"","spec",existing);
    }
    private void compare(JsonNode intended,JsonNode actual,String parent,String key,JsonNode root) {
        String path=parent.isEmpty()?key:parent+"."+key;
        if(path.equals("spec.suspend")){if(!actual.isBoolean())throw conflict();return;} // true until resumed
        if(intended.isObject()) {
            if(!actual.isObject())throw conflict();
            for(var e:intended.properties())compare(e.getValue(),actual.path(e.getKey()),path,e.getKey(),root);
            for(var e:actual.properties())if(!intended.has(e.getKey()) && !defaulted(path,e.getKey(),e.getValue(),root))throw conflict();
        } else if(intended.isArray()) {
            if(!actual.isArray() || actual.size()!=intended.size())throw conflict();
            for(int i=0;i<intended.size();i++)compare(intended.get(i),actual.get(i),parent,key,root); // elements share the list's path
        } else if(parent.endsWith(".resources.requests") || parent.endsWith(".resources.limits") || path.endsWith(".emptyDir.sizeLimit")) {
            var q=quantity(actual.asText(""));
            if(q.signum()<0 || q.compareTo(quantity(intended.asText()))!=0)throw conflict();
        } else if(intended.isNumber()) {
            if(!actual.isNumber() || actual.decimalValue().compareTo(intended.decimalValue())!=0)throw conflict();
        } else if(!intended.equals(actual))throw conflict();
    }
    /** Fields the API server or the Job controller adds on create, with the only values that leave the run's Pod unchanged. */
    private boolean defaulted(String path,String key,JsonNode value,JsonNode root) {
        String uid=root.path("metadata").path("uid").asText(""),name=root.path("metadata").path("name").asText("");
        return switch(path) {
            case "spec" -> switch(key) {
                case "completionMode" -> value.asText().equals("NonIndexed");
                case "podReplacementPolicy" -> value.asText().equals("TerminatingOrFailed");
                case "manualSelector" -> value.isBoolean() && !value.asBoolean();
                // The generated selector matches only the Pods of this Job UID.
                case "selector" -> value.size()==1 && value.path("matchLabels").isObject() && value.path("matchLabels").size()>0
                    && value.path("matchLabels").properties().stream().allMatch(e->Set.of("controller-uid","batch.kubernetes.io/controller-uid").contains(e.getKey()) && !uid.isEmpty() && e.getValue().asText().equals(uid));
                default -> false;
            };
            case "spec.template.metadata" -> key.equals("creationTimestamp") && value.isNull();
            case "spec.template.metadata.labels" -> switch(key) {
                case "controller-uid","batch.kubernetes.io/controller-uid" -> !uid.isEmpty() && value.asText().equals(uid);
                case "job-name","batch.kubernetes.io/job-name" -> value.asText().equals(name);
                default -> false;
            };
            case "spec.template.spec" -> switch(key) {
                case "dnsPolicy" -> value.asText().equals("ClusterFirst");
                case "schedulerName" -> value.asText().equals("default-scheduler");
                case "serviceAccount" -> value.asText().equals(serviceAccount); // deprecated mirror of serviceAccountName
                default -> false;
            };
            case "spec.template.spec.containers" -> switch(key) {
                case "terminationMessagePath" -> value.asText().equals("/dev/termination-log");
                case "terminationMessagePolicy" -> value.asText().equals("File");
                default -> false;
            };
            default -> false;
        };
    }
    private static RuntimeGatewayException conflict(){return new RuntimeGatewayException(OWNERSHIP_CONFLICT);}
    private static List<String> strings(JsonNode node){var values=new ArrayList<String>();node.forEach(v->values.add(v.asText()));return values;}
    private static final Map<String,Integer> BINARY=Map.of("Ki",10,"Mi",20,"Gi",30,"Ti",40,"Pi",50,"Ei",60);
    private static final Map<String,Integer> DECIMAL=Map.of("n",-9,"u",-6,"m",-3,"k",3,"M",6,"G",9,"T",12,"P",15,"E",18);
    /** Kubernetes quantity in base units (cores, bytes, count); Kubernetes may rewrite 2000m as 2 or 4294967296 as 4Gi. */
    public static BigDecimal quantity(String value) {
        try {
            for(var e:BINARY.entrySet())if(value.endsWith(e.getKey()))
                return new BigDecimal(value.substring(0,value.length()-2)).multiply(BigDecimal.valueOf(2).pow(e.getValue())).stripTrailingZeros();
            for(var e:DECIMAL.entrySet())if(value.endsWith(e.getKey()))
                return new BigDecimal(value.substring(0,value.length()-1)).scaleByPowerOfTen(e.getValue()).stripTrailingZeros();
            return new BigDecimal(value).stripTrailingZeros();
        } catch(RuntimeException e){return BigDecimal.valueOf(-1);}
    }

    @Override public Optional<Observation> observe(UUID runId) {
        var job=read(jobs+"/"+ProfilingJob.jobName(runId),true);
        if(job==null)return Optional.empty();
        var m=job.path("metadata");
        if(!MANAGED_BY.equals(m.path("labels").path("app.kubernetes.io/managed-by").asText()) || !runId.toString().equals(m.path("labels").path(RUN).asText()))
            throw conflict();
        String uid=m.path("uid").asText();
        boolean suspended=job.path("spec").path("suspend").asBoolean(false),complete=false;String failed=null;
        for(var c:job.path("status").path("conditions")) {
            if(!c.path("status").asText().equals("True"))continue;
            if(c.path("type").asText().equals("Complete"))complete=true;
            if(c.path("type").asText().equals("Failed"))failed=c.path("reason").asText("Failed");
        }
        var states=new ArrayList<PodState>();
        for(var item:list(pods,OWNED+","+RUN+"="+runId)) {
            // Only Pods the Job controller created for this Job UID are the run's Pods.
            boolean ours=false;for(var o:item.path("metadata").path("ownerReferences"))if(o.path("uid").asText().equals(uid) && o.path("kind").asText().equals("Job"))ours=true;
            if(ours)states.add(pod(item));
        }
        try{return Optional.of(new Observation(UUID.fromString(uid),suspended,complete,failed,states));}catch(RuntimeException e){throw conflict();}
    }
    @Override public boolean resume(UUID runId,UUID jobUid,BooleanSupplier admitted) {
        String path=jobs+"/"+ProfilingJob.jobName(runId);
        var job=read(path,true);
        if(job==null)return false; // deleted (or never created): a PATCH never recreates it
        var m=job.path("metadata");
        if(!MANAGED_BY.equals(m.path("labels").path("app.kubernetes.io/managed-by").asText()) || !runId.toString().equals(m.path("labels").path(RUN).asText())
            || !jobUid.toString().equals(m.path("uid").asText()))throw conflict();
        if(m.has("deletionTimestamp"))return false;
        if(!job.path("spec").path("suspend").asBoolean(false))return true;
        String version=m.path("resourceVersion").asText("");
        if(version.isEmpty())throw new RuntimeGatewayException(UNAVAILABLE);
        // Asked after the read: a cancel recorded later is followed by a delete, which changes the resourceVersion first.
        if(!admitted.getAsBoolean())return false;
        var response=http.request("PATCH",path,Map.of("metadata",Map.of("uid",jobUid.toString(),"resourceVersion",version),"spec",Map.of("suspend",false)),
            timeout,"application/merge-patch+json");
        if(response.status()==200)return true;
        if(response.status()==404 || response.status()==409)return false;
        throw new RuntimeGatewayException(UNAVAILABLE);
    }
    private PodState pod(JsonNode item) {
        var m=item.path("metadata");var status=item.path("status");
        boolean ready=false;for(var c:status.path("conditions"))if(c.path("type").asText().equals("Ready") && c.path("status").asText().equals("True"))ready=true;
        JsonNode container=null;for(var c:status.path("containerStatuses"))if(c.path("name").asText().equals(ProfilingJob.CONTAINER))container=c;
        String waiting=null,terminated=null,imageId=null;Integer exit=null;Instant started=null,finished=null;int restarts=0;
        if(container!=null) {
            restarts=container.path("restartCount").asInt(0);
            imageId=container.path("imageID").asText("");if(imageId.isEmpty() || imageId.length()>512)imageId=null;
            var s=container.path("state");
            if(s.has("waiting"))waiting=s.path("waiting").path("reason").asText(null);
            if(s.has("running"))started=time(s.path("running").path("startedAt"));
            if(s.has("terminated")) {
                var t=s.path("terminated");terminated=t.path("reason").asText(null);exit=t.path("exitCode").isNumber()?t.path("exitCode").asInt():null;
                started=time(t.path("startedAt"));finished=time(t.path("finishedAt"));
            }
            if(terminated==null && container.path("lastState").has("terminated"))terminated=container.path("lastState").path("terminated").path("reason").asText(null);
        }
        if(waiting==null && status.path("phase").asText().equals("Pending"))
            for(var c:status.path("conditions"))if(c.path("type").asText().equals("PodScheduled") && c.path("status").asText().equals("False"))waiting=c.path("reason").asText("Unschedulable");
        String ip=status.path("podIP").asText(null);
        if(ip!=null && !ip.matches("[0-9a-fA-F:.]{2,45}"))ip=null;
        return new PodState(m.path("name").asText(),uuid(m.path("uid").asText()),item.path("spec").path("nodeName").asText(null),ip,status.path("phase").asText("Unknown"),
            ready,restarts,waiting,terminated,exit,started,finished,m.has("deletionTimestamp"),imageId);
    }
    private List<JsonNode> list(String collection,String selector) {
        var root=read(collection+"?labelSelector="+encode(selector)+"&limit=200",false);
        if(!root.path("items").isArray() || !root.path("metadata").path("continue").asText("").isEmpty())throw new RuntimeGatewayException(UNAVAILABLE);
        var items=new ArrayList<JsonNode>();root.path("items").forEach(items::add);return items;
    }
    @Override public boolean delete(UUID runId) {
        String selector=OWNED+","+RUN+"="+runId;
        var remaining=list(jobs,selector);
        for(var job:remaining) {
            var m=job.path("metadata");
            if(!m.path("namespace").asText().equals(namespace) || !runId.toString().equals(m.path("labels").path(RUN).asText()))throw conflict();
            if(m.has("deletionTimestamp"))continue;
            var response=http.request("DELETE",jobs+"/"+encode(m.path("name").asText()),Map.of("apiVersion","v1","kind","DeleteOptions","propagationPolicy","Foreground",
                "gracePeriodSeconds",10,"preconditions",Map.of("uid",m.path("uid").asText())),timeout);
            if(!Set.of(200,202,404,409).contains(response.status()))throw new RuntimeGatewayException(UNAVAILABLE);
        }
        // Done only when Kubernetes reports neither the Job nor any Pod of the run: the measured container has physically stopped.
        return remaining.isEmpty() && list(pods,selector).isEmpty();
    }
    @Override public Set<UUID> ownedRuns() {
        var result=new HashSet<UUID>();
        for(var collection:List.of(jobs,pods))
            for(var item:list(collection,OWNED)){var id=uuid(item.path("metadata").path("labels").path(RUN).asText(null));if(id!=null)result.add(id);}
        return result;
    }
    private static UUID uuid(String value){try{return value==null?null:UUID.fromString(value);}catch(RuntimeException e){return null;}}
    private static Instant time(JsonNode value){try{return value.isMissingNode() || value.isNull()?null:Instant.parse(value.asText());}catch(RuntimeException e){return null;}}
}
