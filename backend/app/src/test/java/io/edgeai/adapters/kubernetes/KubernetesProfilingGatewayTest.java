package io.edgeai.adapters.kubernetes;

import com.sun.net.httpserver.HttpServer;
import io.edgeai.domain.aimodel.ProfilingJob;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Job shape and ownership against a loopback fake API server: bounded spec, exact adoption, label-scoped lists, UID-preconditioned deletes. */
class KubernetesProfilingGatewayTest {
    private static final String IMAGE="registry.test:5000/models/digits@sha256:"+"b".repeat(64);
    private final JsonMapper json=new JsonMapper();
    private final List<String> requests=new CopyOnWriteArrayList<>();
    private final List<String> contentTypes=new CopyOnWriteArrayList<>();
    private final Map<String,Object> responses=new HashMap<>();
    private HttpServer server;
    private KubernetesProfilingGateway gateway;
    private final UUID run=UUID.randomUUID(),model=UUID.randomUUID();
    private String jobs;

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String path=URLDecoder.decode(exchange.getRequestURI().toString(),StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod()+" "+path+(body.isEmpty()?"":" "+body));
            contentTypes.add(exchange.getRequestMethod()+" "+exchange.getRequestHeaders().getFirst("Content-Type"));
            Object reply=responses.getOrDefault(exchange.getRequestMethod()+" "+path.replaceAll("\\?.*",""),Map.of("items",List.of(),"metadata",Map.of()));
            int status=reply instanceof Integer code?code:exchange.getRequestMethod().equals("POST")?201:200;
            byte[] bytes=reply instanceof Integer?new byte[0]:json.writeValueAsBytes(reply);
            exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        gateway=new KubernetesProfilingGateway("http://127.0.0.1:"+server.getAddress().getPort(),"","","edgeai-model-profiling","edgeai-model-profiler");
        jobs="/apis/batch/v1/namespaces/edgeai-model-profiling/jobs";
    }
    @AfterEach void stop(){server.stop(0);gateway.close();}

    private ProfilingJob http(){return new ProfilingJob(run,model,"etri-ser0004-cgnmsb","amd64",IMAGE,List.of("python"),List.of("/opt/demo/server.py"),2000,4L<<30,null,0,8080,300,1020);}

    @SuppressWarnings("unchecked") @Test void jobRunsOnlyThePinnedImageBoundedAndUnprivilegedOnTheChosenNodeThroughTheScheduler() {
        var job=gateway.job(http());
        var metadata=(Map<String,Object>)job.get("metadata");
        assertThat(metadata.get("name")).isEqualTo("edgeai-mp-"+run);
        assertThat((Map<String,Object>)metadata.get("labels")).containsEntry("app.kubernetes.io/managed-by","edgeai-model-profiler")
            .containsEntry(KubernetesProfilingGateway.RUN,run.toString()).containsEntry(KubernetesProfilingGateway.MODEL,model.toString());
        assertThat(((Map<String,Object>)metadata.get("annotations")).get(KubernetesProfilingGateway.SPEC_DIGEST).toString()).matches("sha256:[0-9a-f]{64}");
        var spec=(Map<String,Object>)job.get("spec");
        assertThat(spec).containsEntry("suspend",true).containsEntry("backoffLimit",0).containsEntry("activeDeadlineSeconds",1020).containsEntry("ttlSecondsAfterFinished",300)
            .containsEntry("parallelism",1).containsEntry("completions",1);
        var pod=(Map<String,Object>)((Map<String,Object>)spec.get("template")).get("spec");
        assertThat(pod).containsEntry("restartPolicy","Never").containsEntry("automountServiceAccountToken",false).containsEntry("serviceAccountName","edgeai-model-profiler")
            .containsEntry("enableServiceLinks",false).doesNotContainKeys("nodeName","hostNetwork","hostPID","hostIPC","runtimeClassName","initContainers");
        assertThat(json.writeValueAsString(pod.get("affinity"))).contains("matchFields","metadata.name","etri-ser0004-cgnmsb");
        assertThat((Map<String,Object>)pod.get("nodeSelector")).containsEntry("kubernetes.io/arch","amd64").containsEntry("kubernetes.io/os","linux");
        assertThat(json.writeValueAsString(pod.get("securityContext"))).contains("\"runAsNonRoot\":true","RuntimeDefault");
        assertThat(pod.get("volumes")).isEqualTo(List.of(Map.of("name","tmp","emptyDir",Map.of("sizeLimit","256Mi"))));
        var container=(Map<String,Object>)((List<?>)pod.get("containers")).getFirst();
        assertThat(container).containsEntry("name","model").containsEntry("image",IMAGE).containsEntry("command",List.of("python")).containsEntry("args",List.of("/opt/demo/server.py"));
        assertThat(json.writeValueAsString(container.get("securityContext"))).contains("\"allowPrivilegeEscalation\":false","\"readOnlyRootFilesystem\":true","\"privileged\":false","ALL");
        var resources=(Map<String,Object>)container.get("resources");
        assertThat(resources.get("requests")).isEqualTo(resources.get("limits"));
        assertThat((Map<String,Object>)resources.get("limits")).containsEntry("cpu","2000m").containsEntry("memory",Long.toString(4L<<30)).containsEntry("ephemeral-storage","1Gi");
        assertThat(container.get("ports")).isEqualTo(List.of(Map.of("name","inference","containerPort",8080,"protocol","TCP")));
        assertThat(json.writeValueAsString(job)).doesNotContain("hostPath","privileged\":true","NVIDIA_VISIBLE_DEVICES");

        // COMMAND benchmark: no port, the duration is announced; an accelerator is requested exactly as allocated.
        var bench=gateway.job(new ProfilingJob(run,model,"jetson","arm64",IMAGE,List.of("python"),List.of("/bench.py"),1000,1L<<30,"nvidia.com/gpu.shared",1,null,300,1020));
        var c=(Map<String,Object>)((List<?>)((Map<String,Object>)((Map<String,Object>)((Map<String,Object>)bench.get("spec")).get("template")).get("spec")).get("containers")).getFirst();
        assertThat(c).doesNotContainKey("ports");
        assertThat(c.get("env")).isEqualTo(List.of(Map.of("name","EDGEAI_PROFILING_DURATION_SECONDS","value","300")));
        assertThat((Map<String,Object>)((Map<String,Object>)c.get("resources")).get("limits")).containsEntry("nvidia.com/gpu.shared","1");

        for(var invalid:List.<Runnable>of(
                ()->new ProfilingJob(run,model,"n","amd64","registry.test/models/digits:latest",null,null,100,1<<26,null,0,8080,300,1020),
                ()->new ProfilingJob(run,model,"Bad Node","amd64",IMAGE,null,null,100,1<<26,null,0,8080,300,1020),
                ()->new ProfilingJob(run,model,"n","s390x",IMAGE,null,null,100,1<<26,null,0,8080,300,1020),
                ()->new ProfilingJob(run,model,"n","amd64",IMAGE,null,null,100,1<<26,"cpu",1,8080,300,1020),
                ()->new ProfilingJob(run,model,"n","amd64",IMAGE,null,null,100,1<<26,null,0,8080,300,300)))
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * What a Kubernetes 1.31 API server stores: the submitted object with quantities rewritten into canonical form and the
     * fields defaulting and the Job controller add (selector, completion mode, template labels, DNS/scheduler, messages).
     */
    @SuppressWarnings("unchecked")
    private Map<String,Object> stored(ProfilingJob job,String uid) {
        var copy=(Map<String,Object>)json.readValue(json.writeValueAsString(gateway.job(job)),Map.class);
        var metadata=(Map<String,Object>)copy.get("metadata");
        metadata.put("uid",uid);metadata.put("resourceVersion","4711");metadata.put("generation",1);metadata.put("creationTimestamp","2026-10-30T01:00:00Z");
        var spec=(Map<String,Object>)copy.get("spec");
        spec.put("completionMode","NonIndexed");spec.put("podReplacementPolicy","TerminatingOrFailed");spec.put("manualSelector",false);
        spec.put("selector",Map.of("matchLabels",Map.of("batch.kubernetes.io/controller-uid",uid)));
        var template=(Map<String,Object>)spec.get("template");
        ((Map<String,Object>)template.get("metadata")).put("creationTimestamp",null);
        var labels=(Map<String,Object>)((Map<String,Object>)template.get("metadata")).get("labels");
        labels.put("batch.kubernetes.io/controller-uid",uid);labels.put("controller-uid",uid);
        labels.put("batch.kubernetes.io/job-name",metadata.get("name"));labels.put("job-name",metadata.get("name"));
        var pod=(Map<String,Object>)template.get("spec");
        pod.put("dnsPolicy","ClusterFirst");pod.put("schedulerName","default-scheduler");pod.put("serviceAccount","edgeai-model-profiler");
        var container=(Map<String,Object>)((List<?>)pod.get("containers")).getFirst();
        var canonical=Map.of("cpu","2","memory","4Gi","ephemeral-storage","1Gi");
        container.put("resources",new HashMap<>(Map.of("requests",new HashMap<>(canonical),"limits",new HashMap<>(canonical))));
        container.put("terminationMessagePath","/dev/termination-log");container.put("terminationMessagePolicy","File");
        copy.put("status",Map.of("startTime","2026-10-30T01:00:01Z","active",1));
        return copy;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> at(Map<String,Object> root,String... keys) {
        Object node=root;
        for(String key:keys)node=key.matches("\\d+")?((List<?>)node).get(Integer.parseInt(key)):((Map<String,Object>)node).get(key);
        return (Map<String,Object>)node;
    }
    @Test void ensureCreatesOnceAndAdoptsOnlyAnIdenticalJob() {
        var uid=UUID.randomUUID();
        responses.put("GET "+jobs+"/edgeai-mp-"+run,404);
        assertThatThrownBy(()->gateway.ensureJob(http())).isInstanceOf(RuntimeGatewayException.class); // created, then unreadable: not assumed
        assertThat(requests).anyMatch(r->r.startsWith("POST "+jobs+" "));
        requests.clear();
        responses.put("GET "+jobs+"/edgeai-mp-"+run,stored(http(),uid.toString()));
        assertThat(gateway.ensureJob(http())).isEqualTo(uid);
        assertThat(requests).noneMatch(r->r.startsWith("POST"));

        // Same name, different command or node: never adopted.
        var other=new ProfilingJob(run,model,"etri-ser0004-cgnmsb","amd64",IMAGE,List.of("sh"),List.of("-c","sleep 1"),2000,4L<<30,null,0,8080,300,1020);
        responses.put("GET "+jobs+"/edgeai-mp-"+run,stored(other,uid.toString()));
        assertThatThrownBy(()->gateway.ensureJob(http())).isInstanceOfSatisfying(RuntimeGatewayException.class,
            e->assertThat(e.reason()).isEqualTo(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT));
        var moved=new ProfilingJob(run,model,"etri-ser0002-cgnmsb","amd64",IMAGE,List.of("python"),List.of("/opt/demo/server.py"),2000,4L<<30,null,0,8080,300,1020);
        responses.put("GET "+jobs+"/edgeai-mp-"+run,stored(moved,uid.toString()));
        assertThatThrownBy(()->gateway.ensureJob(http())).isInstanceOf(RuntimeGatewayException.class);
        // A resumed Job (suspend=false) is still the same Job.
        var resumed=stored(http(),uid.toString());at(resumed,"spec").put("suspend",false);
        responses.put("GET "+jobs+"/edgeai-mp-"+run,resumed);
        assertThat(gateway.ensureJob(http())).isEqualTo(uid);
    }

    /** A digest annotation copied onto a changed spec does not help: every declared field is compared, defaults excepted. */
    @Test void adoptionComparesEveryDeclaredFieldNotJustTheDigestAnnotation() {
        var uid=UUID.randomUUID().toString();
        responses.put("GET "+jobs+"/edgeai-mp-"+run,stored(http(),uid));
        assertThat(gateway.ensureJob(http())).isEqualTo(UUID.fromString(uid));
        var tampers=new LinkedHashMap<String,java.util.function.Consumer<Map<String,Object>>>();
        tampers.put("completions",j->at(j,"spec").put("completions",10));
        tampers.put("parallelism",j->at(j,"spec").put("parallelism",2));
        tampers.put("ttl",j->at(j,"spec").put("ttlSecondsAfterFinished",86400));
        tampers.put("deadline",j->at(j,"spec").put("activeDeadlineSeconds",4000));
        tampers.put("env",j->at(j,"spec","template","spec","containers","0").put("env",List.of(Map.of("name","LD_PRELOAD","value","/tmp/x.so"))));
        tampers.put("capabilities",j->at(j,"spec","template","spec","containers","0","securityContext").put("capabilities",Map.of("drop",List.of("ALL"),"add",List.of("NET_ADMIN"))));
        tampers.put("seccomp",j->at(j,"spec","template","spec","securityContext").put("seccompProfile",Map.of("type","Unconfined")));
        tampers.put("runAsUser",j->at(j,"spec","template","spec","securityContext").put("runAsUser",0));
        tampers.put("tmpSize",j->at(j,"spec","template","spec","volumes","0","emptyDir").put("sizeLimit","10Gi"));
        tampers.put("mount",j->at(j,"spec","template","spec","containers","0").put("volumeMounts",List.of(Map.of("name","tmp","mountPath","/"))));
        tampers.put("ephemeral",j->at(j,"spec","template","spec","containers","0","resources","limits").put("ephemeral-storage","100Gi"));
        tampers.put("gpu",j->at(j,"spec","template","spec","containers","0","resources","limits").put("nvidia.com/gpu","1"));
        tampers.put("hostNetwork",j->at(j,"spec","template","spec").put("hostNetwork",true));
        tampers.put("runtimeClass",j->at(j,"spec","template","spec").put("runtimeClassName","nvidia"));
        tampers.put("tolerations",j->at(j,"spec","template","spec").put("tolerations",List.of(Map.of("operator","Exists"))));
        tampers.put("dnsPolicy",j->at(j,"spec","template","spec").put("dnsPolicy","None"));
        tampers.put("pullPolicy",j->at(j,"spec","template","spec","containers","0").put("imagePullPolicy","Always"));
        tampers.put("templateLabel",j->at(j,"spec","template","metadata","labels").put("edgeai.io/extra","1"));
        tampers.put("foreignSelector",j->at(j,"spec").put("selector",Map.of("matchLabels",Map.of("app","other"))));
        tampers.put("jobLabel",j->at(j,"metadata","labels").put("app.kubernetes.io/managed-by","someone"));
        tampers.put("sidecar",j->at(j,"spec","template","spec").put("initContainers",List.of(Map.of("name","x","image","busybox"))));
        for(var tamper:tampers.entrySet()) {
            var job=stored(http(),uid);tamper.getValue().accept(job);
            responses.put("GET "+jobs+"/edgeai-mp-"+run,job);
            assertThatThrownBy(()->gateway.ensureJob(http())).as(tamper.getKey()).isInstanceOfSatisfying(RuntimeGatewayException.class,
                e->assertThat(e.reason()).isEqualTo(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT));
        }
    }

    /** Map.of iteration order is salted per JVM; the digest must not be. */
    @Test void specDigestIsTheSameInFreshProcesses() throws Exception {
        String expected=DigestMain.digest();
        String launcher=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        var seen=new HashSet<String>();
        for(int i=0;i<4;i++) {
            var process=new ProcessBuilder(launcher,"-cp",System.getProperty("java.class.path"),DigestMain.class.getName()).redirectErrorStream(true).start();
            String out=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
            assertThat(process.waitFor()).as(out).isZero();
            seen.add(out);
        }
        assertThat(seen).containsExactly(expected);
        assertThat(expected).matches("sha256:[0-9a-f]{64}");
    }
    static final class DigestMain {
        static String digest() {
            var job=new ProfilingJob(UUID.fromString("00000000-0000-0000-0000-000000000001"),UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "etri-ser0004-cgnmsb","amd64",IMAGE,List.of("python"),List.of("/opt/demo/server.py"),2000,4L<<30,"nvidia.com/gpu",1,8080,300,1020);
            try(var g=new KubernetesProfilingGateway("http://127.0.0.1:1","","","edgeai-model-profiling","edgeai-model-profiler")){return g.digest(g.spec(job));}
        }
        public static void main(String[] args){System.out.println(digest());}
    }

    @Test void resumeIsConditionedOnUidAndResourceVersionAndNeverRecreates() {
        var uid=UUID.randomUUID().toString();
        String path=jobs+"/edgeai-mp-"+run;
        responses.put("GET "+path,stored(http(),uid));
        assertThat(gateway.resume(run,UUID.fromString(uid),()->false)).isFalse();
        assertThat(requests).noneMatch(r->r.startsWith("PATCH")); // not admitted: no patch at all
        assertThat(gateway.resume(run,UUID.fromString(uid),()->true)).isTrue();
        assertThat(requests).anyMatch(r->r.startsWith("PATCH "+path+" ") && r.contains("\"resourceVersion\":\"4711\"") && r.contains("\"uid\":\""+uid+"\"")
            && r.contains("\"suspend\":false"));
        assertThat(contentTypes).contains("PATCH application/merge-patch+json");
        responses.put("PATCH "+path,409); // changed since read (e.g. a delete started): refused, retried later
        assertThat(gateway.resume(run,UUID.fromString(uid),()->true)).isFalse();
        requests.clear();
        responses.put("GET "+path,404); // deleted: nothing is patched, nothing recreated
        assertThat(gateway.resume(run,UUID.fromString(uid),()->true)).isFalse();
        assertThat(requests).noneMatch(r->r.startsWith("PATCH") || r.startsWith("POST"));
        var deleting=stored(http(),uid);at(deleting,"metadata").put("deletionTimestamp","2026-10-30T01:05:00Z");
        responses.put("GET "+path,deleting);
        assertThat(gateway.resume(run,UUID.fromString(uid),()->true)).isFalse();
        var running=stored(http(),uid);at(running,"spec").put("suspend",false);
        responses.put("GET "+path,running);
        assertThat(gateway.resume(run,UUID.fromString(uid),()->{throw new AssertionError("not asked");})).isTrue();
        assertThat(requests).noneMatch(r->r.startsWith("PATCH"));
        assertThatThrownBy(()->gateway.resume(run,UUID.randomUUID(),()->true)).isInstanceOfSatisfying(RuntimeGatewayException.class,
            e->assertThat(e.reason()).isEqualTo(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT)); // another Job of the name
        var suspended=gateway.job(http());
        responses.put("GET "+path,stored(http(),uid));
        assertThat(gateway.observe(run).orElseThrow().suspended()).isTrue();
        assertThat(at(suspended,"spec")).containsEntry("suspend",true);
    }

    /** Headers then a stalled body: every call ends at the whole-exchange deadline, and close does not wait for the server. */
    @Test @Timeout(60) void everyCallIsBoundedAsAWholeExchangeEvenWhenTheBodyStalls() throws Exception {
        try(var stall=new java.net.ServerSocket(0,50,java.net.InetAddress.getLoopbackAddress())) {
            var held=new CopyOnWriteArrayList<java.net.Socket>();
            Thread.ofVirtual().start(()->{
                try {
                    while(true) {
                        var socket=stall.accept();held.add(socket);
                        Thread.ofVirtual().start(()->{
                            try {
                                var in=socket.getInputStream();byte[] buffer=new byte[8192];int n;var seen=new StringBuilder();
                                while(!seen.toString().contains("\r\n\r\n") && (n=in.read(buffer))>0)seen.append(new String(buffer,0,n,StandardCharsets.ISO_8859_1));
                                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{".getBytes(StandardCharsets.ISO_8859_1));
                                socket.getOutputStream().flush(); // and never more
                            } catch(java.io.IOException ignored){}
                        });
                    }
                } catch(java.io.IOException ignored){}
            });
            var slow=new KubernetesProfilingGateway("http://127.0.0.1:"+stall.getLocalPort(),"","","edgeai-model-profiling","edgeai-model-profiler",java.time.Duration.ofMillis(800));
            var calls=new LinkedHashMap<String,Runnable>();
            calls.put("ensureJob",()->slow.ensureJob(http()));
            calls.put("observe",()->slow.observe(run));
            calls.put("resume",()->slow.resume(run,UUID.randomUUID(),()->true));
            calls.put("delete",()->slow.delete(run));
            calls.put("ownedRuns",slow::ownedRuns);
            for(var call:calls.entrySet()) {
                long t0=System.nanoTime();
                assertThatThrownBy(call.getValue()::run).as(call.getKey()).isInstanceOfSatisfying(RuntimeGatewayException.class,
                    e->assertThat(e.reason()).isEqualTo(RuntimeGatewayException.Reason.UNAVAILABLE));
                assertThat(java.time.Duration.ofNanos(System.nanoTime()-t0)).as(call.getKey()).isLessThan(java.time.Duration.ofSeconds(3));
            }
            var pending=Thread.ofVirtual().start(()->{try{slow.observe(run);}catch(RuntimeException ignored){}});
            Thread.sleep(200);
            long t0=System.nanoTime();
            slow.close();
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-t0)).isLessThan(java.time.Duration.ofSeconds(3));
            pending.join(java.time.Duration.ofSeconds(3));
            assertThat(pending.isAlive()).isFalse();
            for(var socket:held)socket.close();
        }
    }

    @Test void observeReportsOnlyPodsOwnedByTheJobUid() {
        var uid=UUID.randomUUID();var podUid=UUID.randomUUID();
        responses.put("GET "+jobs+"/edgeai-mp-"+run,Map.of("metadata",Map.of("name","edgeai-mp-"+run,"uid",uid.toString(),
            "labels",Map.of("app.kubernetes.io/managed-by","edgeai-model-profiler",KubernetesProfilingGateway.RUN,run.toString())),
            "status",Map.of("conditions",List.of(Map.of("type","Failed","status","True","reason","DeadlineExceeded")))));
        responses.put("GET /api/v1/namespaces/edgeai-model-profiling/pods",Map.of("metadata",Map.of(),"items",List.of(
            Map.of("metadata",Map.of("name","p1","uid",podUid.toString(),"ownerReferences",List.of(Map.of("kind","Job","uid",uid.toString()))),
                "spec",Map.of("nodeName","edge-a"),
                "status",Map.of("phase","Failed","podIP","10.42.0.7","containerStatuses",List.of(Map.of("name","model","restartCount",0,"imageID","registry.test/models@sha256:"+"c".repeat(64),
                    "state",Map.of("terminated",Map.of("reason","OOMKilled","exitCode",137,"startedAt","2026-10-30T01:00:00Z","finishedAt","2026-10-30T01:02:00Z")))))),
            Map.of("metadata",Map.of("name","stranger","uid",UUID.randomUUID().toString(),"ownerReferences",List.of(Map.of("kind","Job","uid",UUID.randomUUID().toString())))))));
        var o=gateway.observe(run).orElseThrow();
        assertThat(o.jobUid()).isEqualTo(uid);assertThat(o.jobFailedReason()).isEqualTo("DeadlineExceeded");
        assertThat(o.pods()).hasSize(1);
        var p=o.pods().getFirst();
        assertThat(p.uid()).isEqualTo(podUid);assertThat(p.podIp()).isEqualTo("10.42.0.7");assertThat(p.terminatedReason()).isEqualTo("OOMKilled");
        assertThat(p.exitCode()).isEqualTo(137);assertThat(p.imageId()).endsWith("c".repeat(64));
        assertThat(requests).anyMatch(r->r.contains("labelSelector=app.kubernetes.io/part-of=edgeai,app.kubernetes.io/managed-by=edgeai-model-profiler,"+KubernetesProfilingGateway.RUN+"="+run));
        responses.put("GET "+jobs+"/edgeai-mp-"+run,404);
        assertThat(gateway.observe(run)).isEmpty();
        responses.put("GET "+jobs+"/edgeai-mp-"+run,Map.of("metadata",Map.of("uid",uid.toString(),"labels",Map.of("app.kubernetes.io/managed-by","someone-else"))));
        assertThatThrownBy(()->gateway.observe(run)).isInstanceOf(RuntimeGatewayException.class);
    }

    @Test void deleteUsesUidPreconditionAndWaitsUntilNoJobOrPodRemains() {
        var uid=UUID.randomUUID().toString();
        var labels=Map.of("app.kubernetes.io/part-of","edgeai","app.kubernetes.io/managed-by","edgeai-model-profiler",KubernetesProfilingGateway.RUN,run.toString());
        responses.put("GET "+jobs,Map.of("metadata",Map.of(),"items",List.of(Map.of("metadata",Map.of("name","edgeai-mp-"+run,"namespace","edgeai-model-profiling","uid",uid,"labels",labels)))));
        assertThat(gateway.delete(run)).isFalse();
        assertThat(requests).anyMatch(r->r.startsWith("DELETE "+jobs+"/edgeai-mp-"+run+" ") && r.contains("\"uid\":\""+uid+"\"") && r.contains("Foreground"));
        responses.put("GET "+jobs,Map.of("metadata",Map.of(),"items",List.of()));
        responses.put("GET /api/v1/namespaces/edgeai-model-profiling/pods",Map.of("metadata",Map.of(),"items",List.of(Map.of("metadata",Map.of("name","p","labels",labels)))));
        assertThat(gateway.delete(run)).isFalse(); // the Pod is still terminating
        responses.put("GET /api/v1/namespaces/edgeai-model-profiling/pods",Map.of("metadata",Map.of(),"items",List.of()));
        assertThat(gateway.delete(run)).isTrue();
        responses.put("GET "+jobs,503);
        assertThatThrownBy(()->gateway.delete(run)).isInstanceOf(RuntimeGatewayException.class); // unknown is never "gone"
    }

    @Test void quantitiesAreComparedInBaseUnits() {
        assertThat(KubernetesProfilingGateway.quantity("2")).isEqualByComparingTo(KubernetesProfilingGateway.quantity("2000m"));
        assertThat(KubernetesProfilingGateway.quantity("4Gi")).isEqualByComparingTo(new BigDecimal(4L<<30));
        assertThat(KubernetesProfilingGateway.quantity("500M")).isEqualByComparingTo(new BigDecimal(500_000_000));
        assertThat(KubernetesProfilingGateway.quantity("bogus").signum()).isNegative();
    }
}
