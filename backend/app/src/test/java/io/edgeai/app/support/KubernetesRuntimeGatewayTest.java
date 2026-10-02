package io.edgeai.app.support;

import com.sun.net.httpserver.*;
import io.edgeai.adapters.kubernetes.*;
import io.edgeai.domain.runtime.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** HTTP boundary fixture only. Actual TokenReview, scheduler and controller behavior require the kind gate. */
class KubernetesRuntimeGatewayTest {
    private final JsonMapper json=new JsonMapper();
    private HttpServer server;
    private KubernetesRuntimeGateway gateway;
    private RuntimeInstance runtime;
    private Map<String,Object> compiled,job,secret,pod;
    private final UUID jobUid=UUID.randomUUID(),podUid=UUID.randomUUID(),nodeUid=UUID.randomUUID();
    private boolean ownedNamespace=true,ambiguousPost,wrongAudience,wrongAccount,expiredWatch;
    private int jobPosts,deletes,reviewCalls;
    private static final String NS="edgeai-test-runtime",ACCOUNT="edgeai-runner";
    @BeforeEach void start() throws Exception {
        var now=Instant.now();UUID attempt=UUID.randomUUID();runtime=new RuntimeInstance(UUID.randomUUID(),attempt,UUID.randomUUID(),UUID.randomUUID(),1,NS,"edgeai-"+attempt,UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,null,null,now,now);
        compiled=new KubernetesJobCompiler().compile(ServiceExecutionInput.parseSpec(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))),
            new RuntimeLaunch(runtime.runId(),runtime.taskId(),runtime.attemptId(),1,NS,ACCOUNT,runtime.jobName()+"-claim",URI.create("http://control-plane.example"),null,null));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::respond);server.start();
        gateway=new KubernetesRuntimeGateway("http://127.0.0.1:"+server.getAddress().getPort(),"","",NS,ACCOUNT);
    }
    @AfterEach void stop(){if(gateway!=null)gateway.close();if(server!=null)server.stop(0);}
    @SuppressWarnings("unchecked") private Map<String,Object> decode(HttpExchange exchange) throws Exception {return json.readValue(exchange.getRequestBody().readAllBytes(),Map.class);}
    @SuppressWarnings("unchecked") private Map<String,Object> metadata(Map<String,Object> object){return (Map<String,Object>)object.get("metadata");}
    private Map<String,Object> meta(String name,UUID uid){return new LinkedHashMap<>(Map.of("name",name,"uid",uid.toString(),"namespace",NS,"labels",metadata(compiled).get("labels")));}
    private void send(HttpExchange exchange,int status,Object body) throws Exception {
        byte[] bytes=body instanceof String s?s.getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    private void respond(HttpExchange exchange) {
        try {
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod(),query=Objects.toString(exchange.getRequestURI().getQuery(),"");
            if(path.equals("/api/v1/namespaces/"+NS)){
                send(exchange,200,Map.of("metadata",Map.of("labels",Map.of("app.kubernetes.io/part-of",ownedNamespace?"edgeai":"other","app.kubernetes.io/managed-by","edgeai-bootstrap"))));return;
            }
            if(path.equals("/apis/authentication.k8s.io/v1/tokenreviews")) {
                reviewCalls++;var request=decode(exchange);var spec=(Map<?,?>)request.get("spec");
                assertThat(spec.get("audiences")).isEqualTo(List.of("edgeai-runner"));
                var status=Map.of("authenticated","pod-bound-fixture".equals(spec.get("token")),"audiences",List.of(wrongAudience?"other":"edgeai-runner"),
                    "user",Map.of("username","system:serviceaccount:"+NS+":"+(wrongAccount?"other":ACCOUNT),"extra",Map.of(
                        "authentication.kubernetes.io/pod-name",List.of(runtime.jobName()+"-pod"),"authentication.kubernetes.io/pod-uid",List.of(podUid.toString()))));
                send(exchange,201,Map.of("status",status));return;
            }
            if(path.equals("/api/v1/nodes/node-a")){send(exchange,200,Map.of("metadata",Map.of("uid",nodeUid.toString())));return;}
            if(path.endsWith("/secrets")&&method.equals("POST")){secret=decode(exchange);metadata(secret).put("uid",UUID.randomUUID().toString());send(exchange,201,secret);return;}
            if(path.contains("/secrets/")){
                if(secret==null){send(exchange,404,Map.of());return;}
                if(method.equals("DELETE")){assertUid(exchange,metadata(secret).get("uid"));secret=null;send(exchange,200,Map.of());return;}
                send(exchange,200,secret);return;
            }
            if(path.endsWith("/jobs")&&method.equals("POST")){
                jobPosts++;job=decode(exchange);metadata(job).put("uid",jobUid.toString());
                send(exchange,ambiguousPost?503:201,ambiguousPost?Map.of("message","fixture lost response"):job);ambiguousPost=false;return;
            }
            if(path.endsWith("/jobs")&&query.contains("watch=true")){
                send(exchange,expiredWatch?410:200,expiredWatch?Map.of("code",410):"{\"type\":\"BOOKMARK\",\"object\":{\"metadata\":{\"resourceVersion\":\"102\"}}}\n");return;
            }
            if(path.endsWith("/jobs")){send(exchange,200,Map.of("kind","JobList","metadata",Map.of("resourceVersion","101"),"items",job==null?List.of():List.of(job)));return;}
            if(path.contains("/jobs/")){
                if(job==null){send(exchange,404,Map.of());return;}
                if(method.equals("DELETE")){assertUid(exchange,metadata(job).get("uid"));job=null;send(exchange,200,Map.of());return;}
                send(exchange,200,job);return;
            }
            if(path.endsWith("/pods")){send(exchange,200,Map.of("metadata",Map.of(),"items",pod==null?List.of():List.of(pod)));return;}
            if(path.contains("/pods/")){send(exchange,pod==null?404:200,pod==null?Map.of():pod);return;}
            send(exchange,404,Map.of());
        } catch(Throwable failure){try{send(exchange,500,Map.of("code","FIXTURE_FAILED"));}catch(Exception ignored){exchange.close();}}
    }
    private void assertUid(HttpExchange exchange,Object uid) throws Exception {
        deletes++;var request=decode(exchange);assertThat(((Map<?,?>)request.get("preconditions")).get("uid")).isEqualTo(uid);
        assertThat(request.get("propagationPolicy")).isEqualTo("Foreground");
    }
    private void pod() {
        var metadata=meta(runtime.jobName()+"-pod",podUid);
        metadata.put("ownerReferences",List.of(Map.of("apiVersion","batch/v1","kind","Job","name",runtime.jobName(),"uid",jobUid.toString(),"controller",true)));
        pod=new LinkedHashMap<>(Map.of("metadata",metadata,"spec",Map.of("serviceAccountName",ACCOUNT,"nodeName","node-a"),"status",Map.of("phase","Running")));
    }
    private void reason(RuntimeGatewayException.Reason expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RuntimeGatewayException.class,e->{assertThat(e.reason()).isEqualTo(expected);assertThat(e.getCause()).isNull();});
    }
    @Test void ambiguousCreateIsRecoveredByIdentityWithoutCreatingAnotherJob() {
        ambiguousPost=true;reason(RuntimeGatewayException.Reason.UNAVAILABLE,()->gateway.ensureJob(runtime,compiled,"fixture-claim"));
        assertThat(gateway.ensureJob(runtime,compiled,"fixture-claim")).isEqualTo(jobUid);
        assertThat(gateway.ensureJob(runtime,compiled,"fixture-claim")).isEqualTo(jobUid);assertThat(jobPosts).isEqualTo(1);
        reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.ensureJob(runtime,compiled,"different-claim"));
        metadata(job).put("annotations",Map.of("edgeai.io/intent-sha256","wrong-intent"));
        reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.ensureJob(runtime,compiled,"fixture-claim"));
    }
    @Test void tokenReviewAudienceAccountPodOwnerAndBoundNodeAreRequired() {
        gateway.ensureJob(runtime,compiled,"fixture-claim");pod();var proof=gateway.authenticatePod(runtime,"pod-bound-fixture");
        assertThat(proof).isEqualTo(new RuntimePod(jobUid,podUid,nodeUid,"node-a"));
        reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"bad-token"));
        wrongAudience=true;reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"pod-bound-fixture"));wrongAudience=false;
        wrongAccount=true;reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"pod-bound-fixture"));wrongAccount=false;
        metadata(pod).put("ownerReferences",List.of());reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"pod-bound-fixture"));
        pod();metadata(pod).put("deletionTimestamp",Instant.now().toString());reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"pod-bound-fixture"));
        assertThat(reviewCalls).isEqualTo(6);
    }
    @Test void deletionWaitsForJobPodsAndSecretAndUsesUidPreconditions() {
        gateway.ensureJob(runtime,compiled,"fixture-claim");pod();
        assertThat(gateway.stop(runtime)).isFalse();assertThat(job).isNull();assertThat(secret).isNotNull();
        assertThat(gateway.stop(runtime)).isFalse();pod=null;
        assertThat(gateway.stop(runtime)).isFalse();assertThat(secret).isNull();
        assertThat(gateway.stop(runtime)).isTrue();assertThat(deletes).isEqualTo(2);
    }
    @Test void kubeletStartupObservationLagIsRetryableButTerminatedPodsStayFenced() {
        gateway.ensureJob(runtime,compiled,"fixture-claim");pod();
        pod.put("status",Map.of("phase","Pending"));
        reason(RuntimeGatewayException.Reason.UNAVAILABLE,()->gateway.authenticatePod(runtime,"pod-bound-fixture"));
        reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"bad-token"));
        pod.put("status",Map.of("phase","Running"));
        assertThat(gateway.authenticatePod(runtime,"pod-bound-fixture").podUid()).isEqualTo(podUid);
        for(String phase:List.of("Succeeded","Failed","Unknown")) {
            pod.put("status",Map.of("phase",phase));
            reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(runtime,"pod-bound-fixture"));
        }
    }
    @Test void foreignNamespaceAndForeignJobAreNeverMutated() {
        ownedNamespace=false;reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.ensureJob(runtime,compiled,"fixture-claim"));assertThat(jobPosts).isZero();
        ownedNamespace=true;gateway.ensureJob(runtime,compiled,"fixture-claim");metadata(job).put("labels",Map.of("app.kubernetes.io/part-of","other"));
        reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.stop(runtime));assertThat(deletes).isZero();
    }
    @Test void listWatchVersionAndExpiredWatchAreExplicit() {
        gateway.ensureJob(runtime,compiled,"fixture-claim");var snapshot=gateway.listJobs();
        assertThat(snapshot.jobs().get(runtime.attemptId()).jobUid()).isEqualTo(jobUid);
        assertThat(gateway.watchJobs(snapshot.resourceVersion())).isEqualTo("102");expiredWatch=true;
        assertThat(gateway.watchJobs("102")).isNull();assertThat(gateway.listJobs().resourceVersion()).isEqualTo("101");
    }
}
