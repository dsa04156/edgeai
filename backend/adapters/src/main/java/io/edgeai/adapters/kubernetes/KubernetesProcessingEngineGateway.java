package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.vd.ProcessingEngineGateway;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.runtime.RuntimeGatewayException.Reason.*;

public final class KubernetesProcessingEngineGateway implements ProcessingEngineGateway,AutoCloseable {
    public static final String AUDIENCE="edgeai-processing-engine";
    private final KubernetesRuntimeHttp http;
    private final String namespace,account,podName;
    private final JsonMapper json=new JsonMapper();
    public KubernetesProcessingEngineGateway(String url,String tokenFile,String caFile,String namespace,String account,String podName) {
        for(var name:List.of(namespace,account,podName))if(!name.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"))throw new IllegalArgumentException("Invalid engine identity");
        this.namespace=namespace;this.account=account;this.podName=podName;http=new KubernetesRuntimeHttp(url,tokenFile,caFile);
    }
    private JsonNode decode(byte[] body){try{return json.readTree(body);}catch(RuntimeException e){throw new RuntimeGatewayException(UNAVAILABLE);}}
    public Identity authenticate(String token) {
        if(token==null || token.isBlank() || token.length()>16384)throw new RuntimeGatewayException(AUTH_REJECTED);
        var review=http.request("POST","/apis/authentication.k8s.io/v1/tokenreviews",Map.of("apiVersion","authentication.k8s.io/v1","kind","TokenReview","spec",Map.of("token",token,"audiences",List.of(AUDIENCE))),Duration.ofSeconds(5));
        if(review.status()!=200 && review.status()!=201)throw new RuntimeGatewayException(UNAVAILABLE);
        var status=decode(review.body()).path("status");var user=status.path("user");boolean audience=false;
        for(var value:status.path("audiences"))if(AUDIENCE.equals(value.asText()))audience=true;
        if(!status.path("authenticated").asBoolean() || !audience || !user.path("username").asText().equals("system:serviceaccount:"+namespace+":"+account))throw new RuntimeGatewayException(AUTH_REJECTED);
        String name=single(user.path("extra").path("authentication.kubernetes.io/pod-name"));
        String uid=single(user.path("extra").path("authentication.kubernetes.io/pod-uid"));
        if(!podName.equals(name))throw new RuntimeGatewayException(AUTH_REJECTED);
        UUID identity;try{identity=UUID.fromString(uid);if(!identity.toString().equals(uid))throw new IllegalArgumentException();}catch(RuntimeException e){throw new RuntimeGatewayException(AUTH_REJECTED);}
        var reply=http.request("GET","/api/v1/namespaces/"+namespace+"/pods/"+podName,null,Duration.ofSeconds(5));
        if(reply.status()==404)throw new RuntimeGatewayException(AUTH_REJECTED);if(reply.status()!=200)throw new RuntimeGatewayException(UNAVAILABLE);
        var pod=decode(reply.body());var metadata=pod.path("metadata");var labels=metadata.path("labels");
        if(!uid.equals(metadata.path("uid").asText()) || !name.equals(metadata.path("name").asText()) || !namespace.equals(metadata.path("namespace").asText()) || metadata.has("deletionTimestamp")
            || !account.equals(pod.path("spec").path("serviceAccountName").asText()) || !"Running".equals(pod.path("status").path("phase").asText())
            || !"edgeai".equals(labels.path("app.kubernetes.io/part-of").asText()) || !"central".equals(labels.path("edgeai.io/processing-engine").asText()))throw new RuntimeGatewayException(AUTH_REJECTED);
        return new Identity(identity,name,pod.path("spec").path("nodeName").asText());
    }
    private static String single(JsonNode value){if(!value.isArray() || value.size()!=1 || value.path(0).asText().isBlank())throw new RuntimeGatewayException(AUTH_REJECTED);return value.path(0).asText();}
    public void close(){http.close();}
}
