package io.edgeai.app.integration;

import io.edgeai.adapters.kubernetes.*;
import io.edgeai.app.support.*;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import io.edgeai.domain.vd.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual Kubernetes scheduler/TokenReview/ownership/deletion, with a sleeping workload and controlled readiness. */
class KubernetesVDIntegrationTest {
    private static final String NS="edgeai-runtimes";
    private final JsonDocuments json=new JsonDocuments();
    private String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Explicit environment required: "+name);return value;}
    private String kubectl(String...args) {
        var command=new ArrayList<>(List.of("kubectl","--context",required("EDGEAI_RUNTIME_TEST_CONTEXT"),"--request-timeout=15s"));command.addAll(List.of(args));
        try {
            var process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var bytes=process.getInputStream().readNBytes(1048577);
            if(bytes.length>1048576 || !process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS) || process.exitValue()!=0){process.destroyForcibly();throw new IllegalStateException();}
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8).strip();
        }catch(Exception e){throw new IllegalStateException("Kubernetes fixture operation failed; response suppressed");}
    }
    private KubernetesVDGateway gateway(){return new KubernetesVDGateway(required("EDGEAI_RUNTIME_TEST_URL"),required("EDGEAI_RUNTIME_TEST_TOKEN_FILE"),required("EDGEAI_RUNTIME_TEST_CA_FILE"),NS,"edgeai-runner");}
    private Map<?,?> map(Object v){return (Map<?,?>)v;}
    private Map<?,?> pod(VDRuntime r){return map(json.decode(kubectl("-n",NS,"get","pod",r.podName(),"-o","json")));}
    private VDRuntime runtime() {
        UUID id=UUID.randomUUID();var now=Instant.now();
        return new VDRuntime(id,UUID.randomUUID(),1,0,"{}","fixture",NS,"edgeai-vd-"+id,UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,null,null,now.plusSeconds(240),null,null,now,now);
    }
    private VDRuntime state(VDRuntime r,String desired,UUID podUid,long generation) {
        return new VDRuntime(r.id(),r.vdId(),generation,r.requestedRevision(),r.configurationJson(),r.configurationDigest(),r.namespace(),r.podName(),r.claimNonce(),
            desired,r.observedState(),podUid,r.nodeUid(),r.nodeName(),r.sessionId(),r.leaseUntil(),r.readyAt(),r.startupDeadline(),r.drainDeadline(),r.failureReason(),r.createdAt(),r.updatedAt());
    }
    private void await(BooleanSupplier check,int seconds,String message)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        do{if(check.getAsBoolean())return;Thread.sleep(500);}while(System.nanoTime()<until);throw new AssertionError(message);
    }
    private Map<?,?> node() {
        return ((List<?>)map(json.decode(kubectl("get","nodes","-o","json"))).get("items")).stream().map(this::map).filter(n->{
            var labels=map(map(n.get("metadata")).get("labels"));var spec=map(n.get("spec"));
            if(!"amd64".equals(labels.get("kubernetes.io/arch")) || Boolean.TRUE.equals(spec.get("unschedulable")))return false;
            var taints=(List<?>)spec.get("taints");if(taints!=null && taints.stream().map(this::map).anyMatch(t->Set.of("NoSchedule","NoExecute").contains(t.get("effect"))))return false;
            return ((List<?>)map(n.get("status")).get("conditions")).stream().map(this::map).anyMatch(c->"Ready".equals(c.get("type"))&&"True".equals(c.get("status")));
        }).findFirst().orElseThrow(()->new IllegalStateException("No schedulable Ready amd64 node"));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> compiled(VDRuntime r,Map<?,?> node)throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("image","public.ecr.aws/docker/library/postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24");
        spec.put("platform",Map.of("os","linux","architectures",List.of("amd64")));
        var m=node==null?null:map(node.get("metadata"));
        var launch=new VDRuntimeLaunch(r.vdId(),r.id(),1,NS,"edgeai-runner",URI.create("http://edgeai-api.edgeai.svc:18080"),
            m==null?null:UUID.fromString((String)m.get("uid")),m==null?null:(String)m.get("name"),2,240,10);
        var pod=new KubernetesVDPodCompiler().compile(ServiceExecutionInput.parseSpec(json.canonical(spec)),launch);
        var container=(Map<String,Object>)((List<?>)map(pod.get("spec")).get("containers")).getFirst();
        // Retain identity, affinity, mount and resource policies; this test does not stand in for supervisor acceptance.
        container.put("command",List.of("sleep","240"));container.remove("startupProbe");
        container.put("readinessProbe",Map.of("exec",Map.of("command",List.of("sh","-c","test -f /work/ready")),"periodSeconds",1,"timeoutSeconds",1,"failureThreshold",1));
        return pod;
    }
    @Test void autoPodIdentityReadinessWatchAndDrain()throws Exception{exercise(null);}
    @Test void nodeAffinityUsesActualSchedulerAndNodeUid()throws Exception{exercise(node());}
    private void exercise(Map<?,?> node)throws Exception {
        var r=runtime();var template=compiled(r,node);String claim=UUID.randomUUID().toString();
        try(var gateway=gateway()) {
            try {
                UUID uid=gateway.ensurePod(r,template,claim);assertThat(gateway.ensurePod(r,template,claim)).isEqualTo(uid);
                var bound=state(r,"RUNNING",uid,1);
                await(()->"Running".equals(map(pod(r).get("status")).get("phase")),120,"VD fixture did not reach Running");
                String proof=kubectl("-n",NS,"exec",r.podName(),"--","cat","/var/run/edgeai-identity/token");
                var identity=gateway.authenticatePod(bound,proof);assertThat(identity.podUid()).isEqualTo(uid);assertThat(identity.ready()).isFalse();
                var actual=pod(r);assertThat(identity.nodeName()).isEqualTo(map(actual.get("spec")).get("nodeName"));
                assertThat(map(actual.get("metadata")).get("ownerReferences")).asList().singleElement().satisfies(ref->assertThat(map(ref).get("kind")).isEqualTo("Secret"));
                if(node!=null){var meta=map(node.get("metadata"));assertThat(identity.nodeName()).isEqualTo(meta.get("name"));assertThat(identity.nodeUid().toString()).isEqualTo(meta.get("uid"));}
                kubectl("-n",NS,"exec",r.podName(),"--","touch","/work/ready");await(()->gateway.authenticatePod(bound,proof).ready(),30,"Kubelet readiness was not observed");
                String unbound=kubectl("-n",NS,"create","token","edgeai-runner","--audience=edgeai-vd","--duration=10m");
                String wrongAudience=kubectl("-n",NS,"create","token","edgeai-runner","--audience=edgeai-runner","--duration=10m");
                reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(bound,unbound));
                reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(bound,wrongAudience));
                reason(RuntimeGatewayException.Reason.AUTH_REJECTED,()->gateway.authenticatePod(state(r,"RUNNING",UUID.randomUUID(),1),proof));
                reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.authenticatePod(state(r,"RUNNING",uid,2),proof));
                reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.ensurePod(bound,template,"wrong-claim"));
                reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.stop(bound));
                var snapshot=gateway.listPods();assertThat(snapshot.pods().get(r.id()).podUid()).isEqualTo(uid);assertThat(snapshot.pods().get(r.id()).ready()).isTrue();
                assertThat(gateway.watchPods(snapshot.resourceVersion())).isNotBlank();
                await(()->gateway.stop(state(r,"STOPPED",uid,1)),60,"VD cleanup did not finish");
                assertThat(gateway.listPods().pods()).doesNotContainKey(r.id());
                assertThatThrownBy(()->gateway.authenticatePod(bound,proof)).isInstanceOf(RuntimeGatewayException.class);
            } finally {await(()->gateway.stop(state(r,"STOPPED",null,1)),60,"VD cleanup failed for "+r.id());}
        }
    }
    @Test void latePodUidIsDeletedOnlyWithExactSecretOwnerAndForeignOwnerRemainsUntouched()throws Exception {
        var r=runtime();var template=compiled(r,null);String claim=UUID.randomUUID().toString();
        try(var gateway=gateway()) {
            Object owner=null;
            try {
                UUID first=gateway.ensurePod(r,template,claim);
                kubectl("-n",NS,"delete","pod",r.podName(),"--wait=true","--timeout=45s");
                UUID late=gateway.ensurePod(r,template,claim);assertThat(late).isNotEqualTo(first);
                owner=map(pod(r).get("metadata")).get("ownerReferences");
                patchOwner(r,List.of());
                reason(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT,()->gateway.stop(state(r,"STOPPED",first,1)));
                // Removing ownerReferences leaves a live unowned Pod, without racing Kubernetes garbage collection.
                patchOwner(r,owner);owner=null;
                assertThat(map(pod(r).get("metadata")).get("uid")).isEqualTo(late.toString());
                await(()->gateway.stop(state(r,"STOPPED",first,1)),60,"Late generation Pod cleanup did not finish");
                assertThat(((List<?>)map(json.decode(kubectl("-n",NS,"get","pods,secrets","-l","edgeai.io/vd-runtime-id="+r.id(),"-o","json"))).get("items"))).isEmpty();
            } finally {
                if(owner!=null)patchOwner(r,owner);
                await(()->gateway.stop(state(r,"STOPPED",null,1)),60,"Late Pod fixture cleanup failed for "+r.id());
            }
        }
    }
    private void patchOwner(VDRuntime r,Object refs){kubectl("-n",NS,"patch","pod",r.podName(),"--type=merge","-p",json.canonical(Map.of("metadata",Map.of("ownerReferences",refs))));}
    private void reason(RuntimeGatewayException.Reason reason,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(RuntimeGatewayException.class,e->assertThat(e.reason()).isEqualTo(reason));}
}
