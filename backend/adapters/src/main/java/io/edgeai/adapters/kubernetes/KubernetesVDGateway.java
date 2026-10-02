package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.vd.*;
import io.edgeai.domain.runtime.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.runtime.RuntimeGatewayException.Reason.*;

/** Namespace-scoped persistent Pods. Runtime credentials never enter diagnostics. */
public final class KubernetesVDGateway implements VDGateway {
    private static final String SELECTOR="app.kubernetes.io/part-of=edgeai,app.kubernetes.io/managed-by=edgeai-vd-controller";
    private final KubernetesRuntimeHttp http;
    private final String namespace,account,pods,secrets;
    private final JsonMapper json=new JsonMapper();
    public KubernetesVDGateway(String url,String tokenFile,String caFile,String namespace,String account) {
        RuntimeNames.dns(namespace,63);RuntimeNames.dns(account,253);
        if(namespace.contains("."))throw new IllegalArgumentException("Namespace must be a DNS label");
        this.namespace=namespace;this.account=account;http=new KubernetesRuntimeHttp(url,tokenFile,caFile);
        pods="/api/v1/namespaces/"+namespace+"/pods";secrets="/api/v1/namespaces/"+namespace+"/secrets";
    }
    private JsonNode decode(byte[] body) { try{return json.readTree(body);}catch(RuntimeException e){throw new RuntimeGatewayException(UNAVAILABLE);} }
    private JsonNode read(String path,boolean missing) {
        var reply=http.request("GET",path,null,Duration.ofSeconds(5));if(missing && reply.status()==404)return null;
        if(reply.status()!=200)throw new RuntimeGatewayException(UNAVAILABLE);return decode(reply.body());
    }
    private void namespace() {
        var m=read("/api/v1/namespaces/"+namespace,false).path("metadata");
        if(!m.path("labels").path("app.kubernetes.io/part-of").asText().equals("edgeai")
            || !m.path("labels").path("app.kubernetes.io/managed-by").asText().equals("edgeai-bootstrap") || m.has("deletionTimestamp"))
            throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
    }
    private void scope(VDRuntime r) { if(!namespace.equals(r.namespace()) || !r.podName().equals("edgeai-vd-"+r.id()))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT); }
    private Map<String,String> labels(VDRuntime r) { return Map.of("app.kubernetes.io/part-of","edgeai","app.kubernetes.io/managed-by","edgeai-vd-controller",
        "edgeai.io/vd-id",r.vdId().toString(),"edgeai.io/vd-runtime-id",r.id().toString(),"edgeai.io/generation",Long.toString(r.generation())); }
    private UUID owned(JsonNode document,VDRuntime r,String name) {
        scope(r);var m=document.path("metadata");
        if(!m.path("namespace").asText().equals(namespace) || !m.path("name").asText().equals(name))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        labels(r).forEach((k,v)->{if(!m.path("labels").path(k).asText().equals(v))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);});
        return uid(m.path("uid").asText());
    }
    private static UUID uid(String text) { try{var id=UUID.fromString(text);if(!id.toString().equals(text))throw new IllegalArgumentException();return id;}catch(RuntimeException e){throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);} }
    @Override public UUID ensurePod(VDRuntime r,Map<String,Object> pod,String token) {
        scope(r);namespace();
        if(!r.desiredState().equals("RUNNING") || r.terminal())throw new RuntimeGatewayException(RUNTIME_LOST);
        var submitted=json.valueToTree(pod);validateTemplate(submitted,r);
        String secretName=r.podName()+"-claim",intent=intent(pod.get("spec"));
        var secret=read(secrets+"/"+secretName,true);
        if(secret==null) {
            var payload=Map.of("apiVersion","v1","kind","Secret","type","Opaque","immutable",true,
                "metadata",Map.of("name",secretName,"namespace",namespace,"labels",labels(r)),
                "data",Map.of("token",Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8))));
            var reply=http.request("POST",secrets,payload,Duration.ofSeconds(5));
            if(reply.status()!=201 && reply.status()!=409)throw new RuntimeGatewayException(UNAVAILABLE);secret=read(secrets+"/"+secretName,false);
        }
        UUID secretUid=owned(secret,r,secretName);byte[] stored;
        try{stored=Base64.getDecoder().decode(secret.path("data").path("token").asText());}catch(RuntimeException e){throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);}
        if(!secret.path("immutable").asBoolean() || secret.path("metadata").has("deletionTimestamp") || !MessageDigest.isEqual(stored,token.getBytes(StandardCharsets.UTF_8)))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        var existing=read(pods+"/"+r.podName(),true);
        if(existing==null) {
            if(r.podUid()!=null)throw new RuntimeGatewayException(RUNTIME_LOST);
            var request=new LinkedHashMap<String,Object>(pod);var metadata=new LinkedHashMap<String,Object>();
            ((Map<?,?>)pod.get("metadata")).forEach((k,v)->metadata.put((String)k,v));
            metadata.put("annotations",Map.of("edgeai.io/intent-sha256",intent));
            metadata.put("ownerReferences",List.of(Map.of("apiVersion","v1","kind","Secret","name",secretName,"uid",secretUid.toString(),"controller",true)));
            request.put("metadata",metadata);
            var reply=http.request("POST",pods,request,Duration.ofSeconds(5));
            if(reply.status()!=201 && reply.status()!=409)throw new RuntimeGatewayException(UNAVAILABLE);existing=read(pods+"/"+r.podName(),false);
        }
        UUID podUid=owned(existing,r,r.podName());
        if(r.podUid()!=null && !r.podUid().equals(podUid))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        if(existing.path("metadata").has("deletionTimestamp"))throw new RuntimeGatewayException(RUNTIME_LOST);
        if(!existing.path("metadata").path("annotations").path("edgeai.io/intent-sha256").asText().equals(intent)
            || !existing.path("spec").path("serviceAccountName").asText().equals(account)
            || !existing.path("spec").path("containers").path(0).path("image").equals(submitted.path("spec").path("containers").path(0).path("image")))
            throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        owner(existing,secretUid,secretName);return podUid;
    }
    private void validateTemplate(JsonNode pod,VDRuntime r) {
        var m=pod.path("metadata");var s=pod.path("spec");
        if(!pod.path("kind").asText().equals("Pod") || !pod.path("apiVersion").asText().equals("v1") || !m.path("name").asText().equals(r.podName())
            || !m.path("namespace").asText().equals(namespace) || !s.path("serviceAccountName").asText().equals(account)
            || !s.path("restartPolicy").asText().equals("Never") || s.has("nodeName") || s.path("hostNetwork").asBoolean()
            || s.path("hostPID").asBoolean() || s.path("hostIPC").asBoolean() || s.path("containers").size()!=1 || s.has("initContainers"))
            throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        labels(r).forEach((k,v)->{if(!m.path("labels").path(k).asText().equals(v))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);});
    }
    private void owner(JsonNode pod,UUID secretUid,String secretName) {
        var refs=pod.path("metadata").path("ownerReferences");
        if(refs.size()!=1 || !refs.path(0).path("apiVersion").asText().equals("v1") || !refs.path(0).path("kind").asText().equals("Secret")
            || !refs.path(0).path("name").asText().equals(secretName) || !refs.path(0).path("uid").asText().equals(secretUid.toString()) || !refs.path(0).path("controller").asBoolean())
            throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
    }
    @Override public PodIdentity authenticatePod(VDRuntime r,String token) {
        scope(r);if(r.terminal() || r.desiredState().equals("STOPPED") || token==null || token.isBlank() || token.length()>16384)throw new RuntimeGatewayException(AUTH_REJECTED);
        var response=http.request("POST","/apis/authentication.k8s.io/v1/tokenreviews",Map.of("apiVersion","authentication.k8s.io/v1","kind","TokenReview",
            "spec",Map.of("token",token,"audiences",List.of(KubernetesVDPodCompiler.AUDIENCE))),Duration.ofSeconds(5));
        if(response.status()!=200 && response.status()!=201)throw new RuntimeGatewayException(UNAVAILABLE);
        var status=decode(response.body()).path("status");var user=status.path("user");boolean audience=false;
        for(var value:status.path("audiences"))if(value.asText().equals(KubernetesVDPodCompiler.AUDIENCE))audience=true;
        if(!status.path("authenticated").asBoolean() || !audience || !user.path("username").asText().equals("system:serviceaccount:"+namespace+":"+account))throw new RuntimeGatewayException(AUTH_REJECTED);
        String name=single(user.path("extra").path("authentication.kubernetes.io/pod-name"));
        UUID podUid=uid(single(user.path("extra").path("authentication.kubernetes.io/pod-uid")));
        if(!name.equals(r.podName()) || r.podUid()!=null && !r.podUid().equals(podUid))throw new RuntimeGatewayException(AUTH_REJECTED);
        var pod=read(pods+"/"+name,true);
        if(pod==null || !owned(pod,r,name).equals(podUid) || pod.path("metadata").has("deletionTimestamp") || !pod.path("spec").path("serviceAccountName").asText().equals(account))throw new RuntimeGatewayException(AUTH_REJECTED);
        String secretName=r.podName()+"-claim";var secret=read(secrets+"/"+secretName,true);
        if(secret==null || !secret.path("immutable").asBoolean() || secret.path("metadata").has("deletionTimestamp"))throw new RuntimeGatewayException(AUTH_REJECTED);owner(pod,owned(secret,r,secretName),secretName);
        String phase=pod.path("status").path("phase").asText();if(phase.equals("Pending"))throw new RuntimeGatewayException(UNAVAILABLE);
        if(!phase.equals("Running"))throw new RuntimeGatewayException(AUTH_REJECTED);
        String nodeName=pod.path("spec").path("nodeName").asText();RuntimeNames.dns(nodeName,253);
        UUID nodeUid=uid(read("/api/v1/nodes/"+nodeName,false).path("metadata").path("uid").asText());
        return new PodIdentity(podUid,nodeUid,nodeName,ready(pod));
    }
    private String single(JsonNode value) { if(!value.isArray() || value.size()!=1 || value.path(0).asText().isBlank())throw new RuntimeGatewayException(AUTH_REJECTED);return value.path(0).asText(); }
    @Override public boolean stop(VDRuntime r) {
        scope(r);if(!r.desiredState().equals("STOPPED"))throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        namespace();String secretName=r.podName()+"-claim";
        var secret=read(secrets+"/"+secretName,true);var pod=read(pods+"/"+r.podName(),true);
        UUID secretUid=secret==null?null:owned(secret,r,secretName);
        if(secret!=null && !secret.path("immutable").asBoolean())throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
        if(pod!=null) {
            UUID actual=owned(pod,r,r.podName());
            // An old generation can receive a delayed/repeated CREATE with a different Pod UID.
            // Only an exact owned immutable Secret relationship permits cleaning that orphan.
            if(secretUid==null)throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);owner(pod,secretUid,secretName);
            remove(pods+"/"+r.podName(),actual);return false;
        }
        if(secret!=null){remove(secrets+"/"+secretName,secretUid);return false;}
        return true;
    }
    private void remove(String path,UUID uid) {
        var reply=http.request("DELETE",path,Map.of("apiVersion","v1","kind","DeleteOptions","propagationPolicy","Foreground","preconditions",Map.of("uid",uid.toString())),Duration.ofSeconds(5));
        if(!Set.of(200,202,404).contains(reply.status()))throw new RuntimeGatewayException(reply.status()==409?OWNERSHIP_CONFLICT:UNAVAILABLE);
    }
    @Override public Snapshot listPods() {
        namespace();String continuation="",version=null;var seen=new HashSet<String>();var values=new HashMap<UUID,Observation>();
        do {
            if(seen.size()>20)throw new RuntimeGatewayException(UNAVAILABLE);
            var list=read(pods+"?limit=500&labelSelector="+encode(SELECTOR)+(continuation.isEmpty()?"":"&continue="+encode(continuation)),false);
            String next=list.path("metadata").path("resourceVersion").asText();
            if(!list.path("kind").asText().equals("PodList") || !list.path("items").isArray() || next.isBlank() || version!=null && !version.equals(next))throw new RuntimeGatewayException(UNAVAILABLE);
            version=next;
            for(var pod:list.path("items")) {
                var m=pod.path("metadata");var l=m.path("labels");UUID runtime=uid(l.path("edgeai.io/vd-runtime-id").asText());
                long generation=l.path("edgeai.io/generation").asLong(-1);
                if(!l.path("app.kubernetes.io/part-of").asText().equals("edgeai") || !l.path("app.kubernetes.io/managed-by").asText().equals("edgeai-vd-controller")
                    || !m.path("namespace").asText().equals(namespace) || !m.path("name").asText().equals("edgeai-vd-"+runtime) || generation<1 || generation>9007199254740991L)throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
                var observation=new Observation(runtime,uid(l.path("edgeai.io/vd-id").asText()),generation,m.path("name").asText(),uid(m.path("uid").asText()),pod.path("status").path("phase").asText(),m.has("deletionTimestamp"),ready(pod));
                if(values.put(runtime,observation)!=null)throw new RuntimeGatewayException(OWNERSHIP_CONFLICT);
            }
            continuation=list.path("metadata").path("continue").asText("");if(!continuation.isEmpty()&&!seen.add(continuation))throw new RuntimeGatewayException(UNAVAILABLE);
        }while(!continuation.isEmpty());return new Snapshot(values,version);
    }
    @Override public String watchPods(String version) {
        if(version==null || version.isBlank() || version.length()>256)throw new IllegalArgumentException("List resourceVersion required");
        var reply=http.request("GET",pods+"?watch=true&allowWatchBookmarks=true&timeoutSeconds=5&labelSelector="+encode(SELECTOR)+"&resourceVersion="+encode(version),null,Duration.ofSeconds(10));
        if(reply.status()==410)return null;if(reply.status()!=200)throw new RuntimeGatewayException(UNAVAILABLE);String result=version;
        for(String line:new String(reply.body(),StandardCharsets.UTF_8).split("\n")) {
            if(line.isBlank())continue;var event=decode(line.getBytes(StandardCharsets.UTF_8));String type=event.path("type").asText();
            if(type.equals("ERROR")){if(event.path("object").path("code").asInt()==410)return null;throw new RuntimeGatewayException(UNAVAILABLE);}
            if(!Set.of("ADDED","MODIFIED","DELETED","BOOKMARK").contains(type))throw new RuntimeGatewayException(UNAVAILABLE);
            result=event.path("object").path("metadata").path("resourceVersion").asText();if(result.isBlank())throw new RuntimeGatewayException(UNAVAILABLE);
        }
        return result;
    }
    private String intent(Object value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(sorted(value))));}
        catch(Exception e){throw new RuntimeGatewayException(UNAVAILABLE);}
    }
    private Object sorted(Object v){if(v instanceof Map<?,?> m){var r=new TreeMap<String,Object>();m.forEach((k,x)->r.put((String)k,sorted(x)));return r;}if(v instanceof List<?> l)return l.stream().map(this::sorted).toList();return v;}
    private boolean ready(JsonNode pod){for(var c:pod.path("status").path("conditions"))if(c.path("type").asText().equals("Ready") && c.path("status").asText().equals("True"))return true;return false;}
    private static String encode(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
    @Override public void close(){http.close();}
}
