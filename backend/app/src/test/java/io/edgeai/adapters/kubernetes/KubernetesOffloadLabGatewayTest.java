package io.edgeai.adapters.kubernetes;

import com.sun.net.httpserver.HttpServer;
import io.edgeai.domain.runtime.OffloadLabGateway.Load;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Request shape against a loopback fake API server: fixed program, bounded Job, label-scoped lists, UID-preconditioned deletes. */
class KubernetesOffloadLabGatewayTest {
    private static final String IMAGE="registry.test/edgeai-runner-offload-lab@sha256:"+"a".repeat(64);
    private final JsonMapper json=new JsonMapper();
    private final List<String> requests=new CopyOnWriteArrayList<>();
    private final Map<String,Object> responses=new HashMap<>();
    private HttpServer server;
    private KubernetesOffloadLabGateway gateway;
    private final UUID session=UUID.randomUUID(),run=UUID.randomUUID();

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String path=URLDecoder.decode(exchange.getRequestURI().toString(),StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod()+" "+path+(body.isEmpty()?"":" "+body));
            Object reply=responses.getOrDefault(exchange.getRequestMethod()+" "+path.replaceAll("\\?.*",""),Map.of("items",List.of(),"metadata",Map.of()));
            int status=reply instanceof Integer code?code:200;byte[] bytes=status==200?json.writeValueAsBytes(reply):new byte[0];
            exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        gateway=new KubernetesOffloadLabGateway("http://127.0.0.1:"+server.getAddress().getPort(),"","","edgeai-runtimes","edgeai-runner",IMAGE);
    }
    @AfterEach void stop(){server.stop(0);}

    @SuppressWarnings("unchecked") @Test void loadJobRunsOnlyTheFixedBoundedProgramOnTheChosenNode() {
        var job=gateway.job(new Load(session,2,"etri-dev0006-raspi5",3,300));
        var spec=(Map<String,Object>)job.get("spec");var pod=(Map<String,Object>)((Map<String,Object>)spec.get("template")).get("spec");
        var container=(Map<String,Object>)((List<?>)pod.get("containers")).getFirst();
        assertThat(container.get("image")).isEqualTo(IMAGE);
        assertThat(container.get("command")).isEqualTo(List.of("python3","/opt/edgeai/examples/offload_lab_load.py"));
        assertThat(container.get("args")).isEqualTo(List.of("--workers","3","--seconds","300"));
        assertThat(spec.get("activeDeadlineSeconds")).isEqualTo(330);assertThat(spec.get("backoffLimit")).isEqualTo(0);assertThat(spec.get("ttlSecondsAfterFinished")).isEqualTo(600);
        assertThat(pod.get("automountServiceAccountToken")).isEqualTo(false);assertThat(pod).doesNotContainKeys("volumes","hostNetwork","hostPID");
        assertThat(json.writeValueAsString(container.get("securityContext"))).contains("\"allowPrivilegeEscalation\":false","\"readOnlyRootFilesystem\":true","ALL");
        assertThat(json.writeValueAsString(pod.get("affinity"))).contains("metadata.name","etri-dev0006-raspi5");
        assertThat(((Map<String,Object>)job.get("metadata")).get("name")).isEqualTo("edgeai-lab-load-"+session+"-2");
        assertThat(((Map<String,Object>)((Map<String,Object>)job.get("metadata")).get("labels"))).containsEntry(KubernetesOffloadLabGateway.SESSION,session.toString())
            .containsEntry("app.kubernetes.io/managed-by","edgeai-offload-lab");
        for(var invalid:List.of(new Load(session,1,"n",0,300),new Load(session,1,"n",17,300),new Load(session,1,"n",3,29),new Load(session,1,"n",3,1801),
                new Load(session,0,"n",3,300),new Load(session,1,"Bad Node",3,300)))
            assertThatThrownBy(()->gateway.job(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KubernetesOffloadLabGateway("http://127.0.0.1:1","","","edgeai-runtimes","edgeai-runner","registry.test/runner:latest"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ensureRejectsAnExistingJobOfAnotherOwnerOrImage() {
        var name="edgeai-lab-load-"+session+"-1";
        responses.put("GET /apis/batch/v1/namespaces/edgeai-runtimes/jobs/"+name,Map.of("metadata",Map.of("name",name,"namespace","edgeai-runtimes","uid",UUID.randomUUID().toString(),
            "labels",Map.of("app.kubernetes.io/part-of","edgeai","app.kubernetes.io/managed-by","someone-else")),"spec",Map.of()));
        assertThatThrownBy(()->gateway.ensureLoad(new Load(session,1,"edge-a",2,60))).isInstanceOfSatisfying(RuntimeGatewayException.class,
            e->assertThat(e.reason()).isEqualTo(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT));
        assertThat(requests).noneMatch(r->r.startsWith("POST"));
    }
    @Test void deleteTouchesOnlyThisSessionsGenerationsWithUidPreconditionsAndWaitsForPods() {
        var uid=UUID.randomUUID().toString();var name="edgeai-lab-load-"+session+"-1";var newer="edgeai-lab-load-"+session+"-2";
        Map<String,String> labels=Map.of("app.kubernetes.io/part-of","edgeai","app.kubernetes.io/managed-by","edgeai-offload-lab",KubernetesOffloadLabGateway.SESSION,session.toString(),
            KubernetesOffloadLabGateway.GENERATION,"1");
        var labels2=new HashMap<>(labels);labels2.put(KubernetesOffloadLabGateway.GENERATION,"2");
        responses.put("GET /apis/batch/v1/namespaces/edgeai-runtimes/jobs",Map.of("items",List.of(Map.of("metadata",Map.of("name",name,"namespace","edgeai-runtimes","uid",uid,"labels",labels)),
            Map.of("metadata",Map.of("name",newer,"namespace","edgeai-runtimes","uid",UUID.randomUUID().toString(),"labels",labels2))),"metadata",Map.of()));
        assertThat(gateway.deleteLoad(session,1)).isFalse();
        assertThat(requests).anyMatch(r->r.startsWith("GET /apis/batch/v1/namespaces/edgeai-runtimes/jobs?labelSelector=app.kubernetes.io/part-of=edgeai,app.kubernetes.io/managed-by=edgeai-offload-lab,"
            +KubernetesOffloadLabGateway.SESSION+"="+session));
        assertThat(requests).anyMatch(r->r.startsWith("DELETE /apis/batch/v1/namespaces/edgeai-runtimes/jobs/"+name+" ") && r.contains("\"uid\":\""+uid+"\"") && r.contains("Foreground"));
        assertThat(requests).noneMatch(r->r.startsWith("DELETE") && !r.startsWith("DELETE /apis/batch/v1/namespaces/edgeai-runtimes/jobs/"+name+" ")); // Generation 2 untouched.
        // Jobs gone but a Pod of generation 1 still listed: not yet physically stopped. A generation-2 Pod does not count.
        responses.put("GET /apis/batch/v1/namespaces/edgeai-runtimes/jobs",Map.of("items",List.of(),"metadata",Map.of()));
        responses.put("GET /api/v1/namespaces/edgeai-runtimes/pods",Map.of("items",List.of(Map.of("metadata",Map.of("name","p","uid",UUID.randomUUID().toString(),"labels",labels),
            "status",Map.of("phase","Running"))),"metadata",Map.of()));
        assertThat(gateway.deleteLoad(session,1)).isFalse();
        responses.put("GET /api/v1/namespaces/edgeai-runtimes/pods",Map.of("items",List.of(Map.of("metadata",Map.of("name","p2","uid",UUID.randomUUID().toString(),"labels",labels2),
            "status",Map.of("phase","Running"))),"metadata",Map.of()));
        assertThat(gateway.deleteLoad(session,1)).isTrue();
    }
    @Test void podsAreObservedByRunAndSessionLabelsWithRealIdentity() {
        var attempt=UUID.randomUUID();var podUid=UUID.randomUUID();
        responses.put("GET /api/v1/namespaces/edgeai-runtimes/pods",Map.of("items",List.of(Map.of(
            "metadata",Map.of("name","edgeai-x-abc","uid",podUid.toString(),"creationTimestamp","2026-10-29T10:00:00Z","deletionTimestamp","2026-10-29T10:05:00Z",
                "labels",Map.of("edgeai.io/attempt-id",attempt.toString())),
            "spec",Map.of("nodeName","etri-ser0002-cgnmsb"),
            "status",Map.of("phase","Running","startTime","2026-10-29T10:00:03Z","conditions",List.of(Map.of("type","Ready","status","True"))))),"metadata",Map.of()));
        var pods=gateway.pods(session,run);
        assertThat(requests).anyMatch(r->r.contains("labelSelector=app.kubernetes.io/part-of=edgeai,app.kubernetes.io/managed-by=edgeai-runtime-controller,edgeai.io/run-id="+run));
        var model=pods.stream().filter(p->p.role().equals("MODEL")).findFirst().orElseThrow();
        assertThat(model.uid()).isEqualTo(podUid);assertThat(model.attemptId()).isEqualTo(attempt);assertThat(model.nodeName()).isEqualTo("etri-ser0002-cgnmsb");
        assertThat(model.ready()).isTrue();assertThat(model.deletingAt()).isNotNull();
        responses.put("GET /api/v1/namespaces/edgeai-runtimes/pods",503);
        assertThatThrownBy(()->gateway.pods(session,run)).isInstanceOf(RuntimeGatewayException.class);
    }
}
