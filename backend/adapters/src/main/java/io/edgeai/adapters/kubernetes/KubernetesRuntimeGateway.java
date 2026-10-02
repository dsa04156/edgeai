package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.runtime.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.runtime.RuntimeGatewayException.Reason.*;

/** Operates only in an explicitly owned runtime namespace. Every object mutation checks identity/UID. */
public final class KubernetesRuntimeGateway implements RuntimeGateway {
    public static final String AUDIENCE="edgeai-runner";
    private static final String SELECTOR="app.kubernetes.io/part-of=edgeai,app.kubernetes.io/managed-by=edgeai-runtime-controller";
    private final KubernetesRuntimeHttp http;
    private final String namespace,serviceAccount,jobs,pods,secrets;
    private final JsonMapper json=new JsonMapper();
    public KubernetesRuntimeGateway(String url,String tokenFile,String caFile,String namespace,String serviceAccount) {
        RuntimeNames.dns(namespace,63);RuntimeNames.dns(serviceAccount,253);
        if(namespace.contains("."))throw new IllegalArgumentException("Namespace requires a DNS label");
        this.namespace=namespace;this.serviceAccount=serviceAccount;
        http=new KubernetesRuntimeHttp(url,tokenFile,caFile);
        jobs="/apis/batch/v1/namespaces/"+namespace+"/jobs";
        pods="/api/v1/namespaces/"+namespace+"/pods";
        secrets="/api/v1/namespaces/"+namespace+"/secrets";
    }
    private JsonNode read(String path,boolean missing) {
        var response=http.request("GET",path,null,Duration.ofSeconds(5));
        if(missing && response.status()==404)return null;
        if(response.status()!=200)throw new RuntimeGatewayException(UNAVAILABLE);
        return decode(response.body());
    }
    private JsonNode decode(byte[] bytes) {
        try{return json.readTree(bytes);}catch(RuntimeException e){throw new RuntimeGatewayException(UNAVAILABLE);}
    }
    private void namespace() {
        var value=read("/api/v1/namespaces/"+namespace,false);var labels=value.path("metadata").path("labels");
        if(!labels.path("app.kubernetes.io/part-of").asText().equals("edgeai") ||
                !labels.path("app.kubernetes.io/managed-by").asText().equals("edgeai-bootstrap") || value.path("metadata").has("deletionTimestamp"))
            throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
    }
    private Map<String,String> labels(RuntimeInstance r) {
        return Map.of("app.kubernetes.io/part-of","edgeai","app.kubernetes.io/managed-by","edgeai-runtime-controller",
            "edgeai.io/run-id",r.runId().toString(),"edgeai.io/task-id",r.taskId().toString(),"edgeai.io/attempt-id",r.attemptId().toString(),"edgeai.io/epoch",Long.toString(r.epoch()));
    }
    private void scope(RuntimeInstance r) {
        if(!namespace.equals(r.namespace()) || !r.jobName().equals("edgeai-"+r.attemptId()))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
    }
    private UUID owned(JsonNode value,RuntimeInstance r,String name) {
        scope(r);var m=value.path("metadata");
        if(!m.path("name").asText().equals(name) || !m.path("namespace").asText().equals(namespace))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        labels(r).forEach((key,expected)->{if(!expected.equals(m.path("labels").path(key).asText()))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);});
        return uid(m.path("uid").asText());
    }
    private static UUID uid(String value) { try{return UUID.fromString(value);}catch(RuntimeException e){throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);} }
    private void jobIdentity(JsonNode job,RuntimeInstance r) {
        UUID actual=owned(job,r,r.jobName());
        if(r.jobUid()!=null&&!r.jobUid().equals(actual))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
    }
    @Override public UUID ensureJob(RuntimeInstance r,Map<String,Object> job,String token) {
        scope(r);namespace();String secretName=r.jobName()+"-claim";
        String intent=intent(job.get("spec"));
        var secret=read(secrets+"/"+secretName,true);
        if(secret==null) {
            var payload=Map.of("apiVersion","v1","kind","Secret","immutable",true,"type","Opaque",
                "metadata",Map.of("name",secretName,"namespace",namespace,"labels",labels(r)),
                "data",Map.of("token",Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8))));
            var response=http.request("POST",secrets,payload,Duration.ofSeconds(5));
            if(response.status()!=201&&response.status()!=409)throw new RuntimeGatewayException(UNAVAILABLE);
            secret=read(secrets+"/"+secretName,false);
        }
        owned(secret,r,secretName);
        byte[] stored;
        try{stored=Base64.getDecoder().decode(secret.path("data").path("token").asText());}catch(RuntimeException e){throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);}
        if(!secret.path("immutable").asBoolean() || !MessageDigest.isEqual(stored,token.getBytes(StandardCharsets.UTF_8)))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        var existing=read(jobs+"/"+r.jobName(),true);
        if(existing==null) {
            if(r.jobUid()!=null)throw new RuntimeGatewayException(RUNTIME_LOST);
            var submitted=new LinkedHashMap<String,Object>(job);
            var metadata=new LinkedHashMap<String,Object>();
            ((Map<?,?>)job.get("metadata")).forEach((key,value)->metadata.put((String)key,value));
            metadata.put("annotations",Map.of("edgeai.io/intent-sha256",intent));submitted.put("metadata",metadata);
            var response=http.request("POST",jobs,submitted,Duration.ofSeconds(5));
            if(response.status()!=201&&response.status()!=409)throw new RuntimeGatewayException(UNAVAILABLE);
            existing=read(jobs+"/"+r.jobName(),false);
        }
        jobIdentity(existing,r);
        if(existing.path("metadata").has("deletionTimestamp"))throw new RuntimeGatewayException(RUNTIME_LOST);
        if(!existing.path("metadata").path("annotations").path("edgeai.io/intent-sha256").asText().equals(intent))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        // Digest-pinned image, service account and Pod ownership must match the submitted execution.
        var expected=json.valueToTree(job).path("spec").path("template").path("spec");
        var actual=existing.path("spec").path("template").path("spec");
        if(!actual.path("containers").path(0).path("image").equals(expected.path("containers").path(0).path("image")) ||
                !actual.path("serviceAccountName").asText().equals(serviceAccount))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        return uid(existing.path("metadata").path("uid").asText());
    }
    @Override public RuntimePod authenticatePod(RuntimeInstance r,String podToken) {
        scope(r);
        if(podToken==null || podToken.isBlank() || podToken.length()>16384)throw new RuntimeGatewayException(AUTH_REJECTED);
        var response=http.request("POST","/apis/authentication.k8s.io/v1/tokenreviews",
            Map.of("apiVersion","authentication.k8s.io/v1","kind","TokenReview","spec",Map.of("token",podToken,"audiences",List.of(AUDIENCE))),Duration.ofSeconds(5));
        if(response.status()!=201&&response.status()!=200)throw new RuntimeGatewayException(UNAVAILABLE);
        var status=decode(response.body()).path("status");var user=status.path("user");
        boolean audience=false;for(var value:status.path("audiences"))if(value.asText().equals(AUDIENCE))audience=true;
        if(!status.path("authenticated").asBoolean() || !audience || !user.path("username").asText().equals("system:serviceaccount:"+namespace+":"+serviceAccount))
            throw new RuntimeGatewayException(AUTH_REJECTED);
        var extras=user.path("extra");String name=single(extras.path("authentication.kubernetes.io/pod-name"));
        UUID podUid=uid(single(extras.path("authentication.kubernetes.io/pod-uid")));RuntimeNames.dns(name,253);
        var pod=read(pods+"/"+name,true);if(pod==null)throw new RuntimeGatewayException(AUTH_REJECTED);
        if(!owned(pod,r,name).equals(podUid) || pod.path("metadata").has("deletionTimestamp") ||
                !pod.path("spec").path("serviceAccountName").asText().equals(serviceAccount))
            throw new RuntimeGatewayException(AUTH_REJECTED);
        // The process can start before kubelet publishes Running. Keep auth closed, but
        // distinguish that observation lag from revoked/foreign credentials so claim can retry.
        String phase=pod.path("status").path("phase").asText();
        if(phase.equals("Pending"))throw new RuntimeGatewayException(UNAVAILABLE);
        if(!phase.equals("Running"))throw new RuntimeGatewayException(AUTH_REJECTED);
        var job=read(jobs+"/"+r.jobName(),true);if(job==null)throw new RuntimeGatewayException(AUTH_REJECTED);jobIdentity(job,r);
        UUID jobUid=uid(job.path("metadata").path("uid").asText());boolean owner=false;
        for(var reference:pod.path("metadata").path("ownerReferences"))
            if(reference.path("kind").asText().equals("Job") && reference.path("apiVersion").asText().equals("batch/v1") && reference.path("controller").asBoolean() &&
                    reference.path("name").asText().equals(r.jobName()) && reference.path("uid").asText().equals(jobUid.toString()))owner=true;
        if(!owner)throw new RuntimeGatewayException(AUTH_REJECTED);
        String nodeName=pod.path("spec").path("nodeName").asText();RuntimeNames.dns(nodeName,253);
        var node=read("/api/v1/nodes/"+nodeName,false);
        return new RuntimePod(jobUid,podUid,uid(node.path("metadata").path("uid").asText()),nodeName);
    }
    private String single(JsonNode array) { if(!array.isArray()||array.size()!=1||array.path(0).asText().isBlank())throw new RuntimeGatewayException(AUTH_REJECTED);return array.path(0).asText(); }
    @Override public boolean stop(RuntimeInstance r) {
        scope(r);namespace();var job=read(jobs+"/"+r.jobName(),true);
        UUID jobUid=r.jobUid();
        if(job!=null) {jobIdentity(job,r);jobUid=uid(job.path("metadata").path("uid").asText());remove(jobs+"/"+r.jobName(),jobUid);return false;}
        var remaining=read(pods+"?labelSelector="+encode("edgeai.io/attempt-id="+r.attemptId())+"&limit=500",false);
        if(!remaining.path("items").isArray() || !remaining.path("metadata").path("continue").asText("").isEmpty())throw new RuntimeGatewayException(UNAVAILABLE);
        if(!remaining.path("items").isEmpty())return false;
        String name=r.jobName()+"-claim";var secret=read(secrets+"/"+name,true);
        if(secret!=null){remove(secrets+"/"+name,owned(secret,r,name));return false;}
        return true;
    }
    private void remove(String path,UUID uid) {
        var response=http.request("DELETE",path,Map.of("apiVersion","v1","kind","DeleteOptions","propagationPolicy","Foreground","preconditions",Map.of("uid",uid.toString())),Duration.ofSeconds(5));
        if(!Set.of(200,202,404).contains(response.status()))throw new RuntimeGatewayException(response.status()==409?OWNERSHIP_CONFLICT:UNAVAILABLE);
    }
    @Override public Snapshot listJobs() {
        namespace();var result=new HashMap<UUID,JobObservation>();String version=null,continuation="";var seen=new HashSet<String>();
        do {
            if(seen.size()>20)throw new RuntimeGatewayException(UNAVAILABLE);
            var root=read(jobs+"?labelSelector="+encode(SELECTOR)+"&limit=500"+(continuation.isEmpty()?"":"&continue="+encode(continuation)),false);
            String nextVersion=root.path("metadata").path("resourceVersion").asText();
            if(!root.path("kind").asText().equals("JobList") || !root.path("items").isArray() || nextVersion.isBlank() || (version!=null&&!version.equals(nextVersion)))
                throw new RuntimeGatewayException(UNAVAILABLE);
            version=nextVersion;
            for(var job:root.path("items")) {
                var meta=job.path("metadata");var label=meta.path("labels");UUID attempt=uid(label.path("edgeai.io/attempt-id").asText());
                if(!meta.path("namespace").asText().equals(namespace) || !meta.path("name").asText().equals("edgeai-"+attempt))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
                long epoch=label.path("edgeai.io/epoch").asLong(-1);if(epoch<1)throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
                String state="ACTIVE";
                for(var condition:job.path("status").path("conditions"))if(condition.path("status").asText().equals("True")) {
                    if(condition.path("type").asText().equals("Failed"))state="FAILED";
                    else if(condition.path("type").asText().equals("Complete")&&!state.equals("FAILED"))state="COMPLETE";
                }
                var item=new JobObservation(attempt,uid(label.path("edgeai.io/task-id").asText()),uid(label.path("edgeai.io/run-id").asText()),epoch,meta.path("name").asText(),uid(meta.path("uid").asText()),state);
                if(result.put(attempt,item)!=null)throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
            }
            continuation=root.path("metadata").path("continue").asText("");
            if(!continuation.isEmpty()&&!seen.add(continuation))throw new RuntimeGatewayException(UNAVAILABLE);
        }while(!continuation.isEmpty());
        return new Snapshot(result,version);
    }
    @Override public String watchJobs(String version) {
        if(version==null||version.isBlank()||version.length()>256)throw new IllegalArgumentException("A list resourceVersion is required");
        var reply=http.request("GET",jobs+"?watch=true&allowWatchBookmarks=true&timeoutSeconds=5&labelSelector="+encode(SELECTOR)+"&resourceVersion="+encode(version),null,Duration.ofSeconds(10));
        if(reply.status()==410)return null;
        if(reply.status()!=200)throw new RuntimeGatewayException(UNAVAILABLE);
        String result=version;
        for(String line:new String(reply.body(),StandardCharsets.UTF_8).split("\n")) {
            if(line.isBlank())continue;var event=decode(line.getBytes(StandardCharsets.UTF_8));String type=event.path("type").asText();
            if(type.equals("ERROR")){if(event.path("object").path("code").asInt()==410)return null;throw new RuntimeGatewayException(UNAVAILABLE);}
            if(!Set.of("ADDED","MODIFIED","DELETED","BOOKMARK").contains(type))throw new RuntimeGatewayException(UNAVAILABLE);
            String next=event.path("object").path("metadata").path("resourceVersion").asText();if(next.isBlank())throw new RuntimeGatewayException(UNAVAILABLE);result=next;
        }
        return result;
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private String intent(Object value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(sorted(value))));}
        catch(Exception e){throw new RuntimeGatewayException(UNAVAILABLE);}
    }
    private Object sorted(Object value){
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put((String)k,sorted(v)));return result;}
        if(value instanceof List<?> list)return list.stream().map(this::sorted).toList();return value;
    }
    @Override public void close(){http.close();}
}
