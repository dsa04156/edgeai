package io.edgeai.app.support;

import com.sun.net.httpserver.*;
import io.edgeai.adapters.kubernetes.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.vd.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Fault-injected HTTP boundary. Does not replace real-cluster scheduler/TokenReview tests. */
class KubernetesVDGatewayTest {
    private static final String NS="edgeai-test-vd",ACCOUNT="edgeai-runner";
    private final JsonMapper json=new JsonMapper();
    private HttpServer server;
    private KubernetesVDGateway gateway;
    private VDRuntime runtime;
    private Map<String,Object> compiled,pod,secret;
    private UUID podUid=UUID.randomUUID();
    private final UUID nodeUid=UUID.randomUUID();
    private boolean ambiguousPost,ownedNamespace=true,wrongAudience,unbound,expiredWatch,eventExpiry,badPageVersion,secondPage;
    private int posts,deletes;
    private volatile Throwable fixtureFailure;
    @BeforeEach void start()throws Exception {
        var now=Instant.now();UUID id=UUID.randomUUID();
        runtime=new VDRuntime(id,UUID.randomUUID(),1,0,"{}","fixture",NS,"edgeai-vd-"+id,UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,null,null,now.plusSeconds(60),null,null,now,now);
        compiled=new KubernetesVDPodCompiler().compile(ServiceExecutionInput.parseSpec(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))),
            new VDRuntimeLaunch(runtime.vdId(),id,1,NS,ACCOUNT,URI.create("http://control-plane.example"),null,null,2,60,10));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::respond);server.start();
        gateway=new KubernetesVDGateway("http://127.0.0.1:"+server.getAddress().getPort(),"","",NS,ACCOUNT);
    }
    @AfterEach void stop(){if(gateway!=null)gateway.close();if(server!=null)server.stop(0);assertThat(fixtureFailure).isNull();}
    private VDRuntime stopped(UUID knownUid) {
        var r=runtime;return new VDRuntime(r.id(),r.vdId(),r.generation(),r.requestedRevision(),r.configurationJson(),r.configurationDigest(),r.namespace(),r.podName(),r.claimNonce(),
            "STOPPED",r.observedState(),knownUid,null,null,null,null,null,r.startupDeadline(),null,null,r.createdAt(),r.updatedAt());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> decode(HttpExchange e)throws Exception{return json.readValue(e.getRequestBody().readAllBytes(),Map.class);}
    @SuppressWarnings("unchecked") private Map<String,Object> meta(Map<String,Object> v){return (Map<String,Object>)v.get("metadata");}
    private void send(HttpExchange e,int status,Object value)throws Exception {
        byte[] bytes=value instanceof String s?s.getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(value);
        e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
    }
    private void respond(HttpExchange e) {
        try {
            String path=e.getRequestURI().getPath(),method=e.getRequestMethod(),query=Objects.toString(e.getRequestURI().getQuery(),"");
            if(path.equals("/api/v1/namespaces/"+NS)){send(e,200,Map.of("metadata",Map.of("labels",Map.of("app.kubernetes.io/part-of",ownedNamespace?"edgeai":"other","app.kubernetes.io/managed-by","edgeai-bootstrap"))));return;}
            if(path.equals("/apis/authentication.k8s.io/v1/tokenreviews")) {
                var spec=(Map<?,?>)decode(e).get("spec");assertThat(spec.get("audiences")).isEqualTo(List.of("edgeai-vd"));
                send(e,201,Map.of("status",Map.of("authenticated","bound-fixture".equals(spec.get("token")),"audiences",List.of(wrongAudience?"edgeai-runner":"edgeai-vd"),
                    "user",Map.of("username","system:serviceaccount:"+NS+":"+ACCOUNT,"extra",unbound?Map.of():Map.of("authentication.kubernetes.io/pod-name",List.of(runtime.podName()),"authentication.kubernetes.io/pod-uid",List.of(podUid.toString()))))));return;
            }
            if(path.equals("/api/v1/nodes/node-a")){send(e,200,Map.of("metadata",Map.of("uid",nodeUid.toString())));return;}
            if(path.endsWith("/secrets") && method.equals("POST")){secret=decode(e);meta(secret).put("uid",UUID.randomUUID().toString());send(e,201,secret);return;}
            if(path.contains("/secrets/")) {
                if(secret==null){send(e,404,Map.of());return;}
                if(method.equals("DELETE")){deletion(e,meta(secret).get("uid"));secret=null;send(e,200,Map.of());return;}send(e,200,secret);return;
            }
            if(path.endsWith("/pods") && method.equals("POST")) {
                posts++;pod=decode(e);meta(pod).put("uid",podUid.toString());send(e,ambiguousPost?503:201,Map.of());ambiguousPost=false;return;
            }
            if(path.endsWith("/pods") && query.contains("watch=true")) {
                send(e,expiredWatch?410:200,expiredWatch?Map.of("code",410):eventExpiry?"{\"type\":\"ERROR\",\"object\":{\"code\":410}}\n":"{\"type\":\"BOOKMARK\",\"object\":{\"metadata\":{\"resourceVersion\":\"102\"}}}\n");return;
            }
            if(path.endsWith("/pods")) {
                boolean continued=query.contains("continue=");var m=new HashMap<String,Object>();m.put("resourceVersion",badPageVersion&&continued?"102":"101");
                if(secondPage&&!continued)m.put("continue","next-page");
                send(e,200,Map.of("kind","PodList","metadata",m,"items",pod==null||continued?List.of():List.of(pod)));return;
            }
            if(path.contains("/pods/")) {
                if(pod==null){send(e,404,Map.of());return;}
                if(method.equals("DELETE")){deletion(e,meta(pod).get("uid"));pod=null;send(e,200,Map.of());return;}send(e,200,pod);return;
            }
            send(e,404,Map.of());
        }catch(Throwable failure){fixtureFailure=failure;try{send(e,500,Map.of("code","FIXTURE_FAILED"));}catch(Exception ignored){e.close();}}
    }
    private void deletion(HttpExchange e,Object uid)throws Exception {var data=decode(e);assertThat(((Map<?,?>)data.get("preconditions")).get("uid")).isEqualTo(uid);assertThat(data.get("propagationPolicy")).isEqualTo("Foreground");deletes++;}
    @SuppressWarnings("unchecked") private void running(boolean ready){((Map<String,Object>)pod.get("spec")).put("nodeName","node-a");pod.put("status",Map.of("phase","Running","conditions",List.of(Map.of("type","Ready","status",ready?"True":"False"))));}
    private void reason(RuntimeGatewayException.Reason expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(RuntimeGatewayException.class,e->assertThat(e.reason()).isEqualTo(expected));}
    @Test void lostPostResponseReusesExactPodAndRejectsCredentialAndIntentMismatch() {
        ambiguousPost=true;reason(RuntimeGatewayException.Reason.UNAVAILABLE,()->gateway.ensurePod(runtime,compiled,"fixture-claim"));
        assertThat(gateway.ensurePod(runtime,compiled,"fixture-claim")).isEqualTo(podUid);assertThat(posts).isEqualTo(1);
        reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.ensurePod(runtime,compiled,"wrong-claim"));
        meta(pod).put("annotations",Map.of("edgeai.io/intent-sha256","wrong"));reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.ensurePod(runtime,compiled,"fixture-claim"));
    }
    @Test void identityRequiresAudienceBoundPodOwnerAndLivePhaseWhileReadinessIsSeparate() {
        gateway.ensurePod(runtime,compiled,"fixture-claim");pod.put("status",Map.of("phase","Pending"));
        reason(RuntimeGatewayException.Reason.UNAVAILABLE,()->gateway.authenticatePod(runtime,"bound-fixture"));
        running(false);assertThat(gateway.authenticatePod(runtime,"bound-fixture")).isEqualTo(new VDGateway.PodIdentity(podUid,nodeUid,"node-a",false));
        running(true);assertThat(gateway.authenticatePod(runtime,"bound-fixture").ready()).isTrue();
        wrongAudience=true;reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"bound-fixture"));wrongAudience=false;
        unbound=true;reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"bound-fixture"));unbound=false;
        pod.put("status",Map.of("phase","Succeeded"));reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"bound-fixture"));running(true);
        meta(pod).put("ownerReferences",List.of());reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.authenticatePod(runtime,"bound-fixture"));
        reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(stopped(podUid),"bound-fixture"));
    }
    @Test void stopDeletesLateUidUnderExactSecretOwnerAndWaitsForBothResources() {
        UUID historical=UUID.randomUUID();gateway.ensurePod(runtime,compiled,"fixture-claim");
        reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.stop(runtime));assertThat(deletes).isZero();
        assertThat(gateway.stop(stopped(historical))).isFalse();assertThat(pod).isNull();assertThat(secret).isNotNull();
        assertThat(gateway.stop(stopped(historical))).isFalse();assertThat(secret).isNull();assertThat(gateway.stop(stopped(historical))).isTrue();assertThat(deletes).isEqualTo(2);
    }
    @Test void namespaceGenerationAndOwnerConflictsNeverDelete() {
        ownedNamespace=false;reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.ensurePod(runtime,compiled,"fixture-claim"));assertThat(posts).isZero();ownedNamespace=true;
        gateway.ensurePod(runtime,compiled,"fixture-claim");var owner=meta(pod).get("ownerReferences");meta(pod).put("ownerReferences",List.of());
        reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.stop(stopped(podUid)));meta(pod).put("ownerReferences",owner);
        var labels=new HashMap<Object,Object>((Map<?,?>)meta(pod).get("labels"));labels.put("edgeai.io/generation","2");meta(pod).put("labels",labels);
        reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.stop(stopped(podUid)));assertThat(deletes).isZero();
    }
    @Test void paginatedSnapshotAndWatchExpiryRequireConsistentVersions() {
        gateway.ensurePod(runtime,compiled,"fixture-claim");running(true);secondPage=true;
        var snapshot=gateway.listPods();assertThat(snapshot.pods()).hasSize(1);assertThat(snapshot.pods().get(runtime.id()).ready()).isTrue();
        assertThat(gateway.watchPods(snapshot.resourceVersion())).isEqualTo("102");
        expiredWatch=true;assertThat(gateway.watchPods("102")).isNull();expiredWatch=false;eventExpiry=true;assertThat(gateway.watchPods("102")).isNull();
        badPageVersion=true;reason(RuntimeGatewayException.Reason.UNAVAILABLE,()->gateway.listPods());
    }
}
