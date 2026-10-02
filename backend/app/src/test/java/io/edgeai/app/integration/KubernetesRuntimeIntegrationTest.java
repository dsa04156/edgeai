package io.edgeai.app.integration;

import io.edgeai.adapters.kubernetes.*;
import io.edgeai.app.support.*;
import io.edgeai.domain.runtime.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real scheduler, Job/Pod, TokenReview and deletion. A sleeping fixture replaces the workload, not the Kubernetes API. */
class KubernetesRuntimeIntegrationTest {
    private static final String NAMESPACE="edgeai-runtimes";
    private static final JsonDocuments JSON=new JsonDocuments();
    private String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Required explicit test environment: "+name);return value;}
    private String kubectl(String... args){
        var command=new ArrayList<>(List.of("kubectl","--context",required("EDGEAI_RUNTIME_TEST_CONTEXT"),"--request-timeout=15s"));command.addAll(List.of(args));
        try{
            var process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] output=process.getInputStream().readNBytes(1048577);
            if(output.length>1048576 || !process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS) || process.exitValue()!=0){process.destroyForcibly();throw new IllegalStateException("Kubernetes fixture operation failed; response suppressed");}
            return new String(output,java.nio.charset.StandardCharsets.UTF_8).strip();
        }catch(Exception e){throw new IllegalStateException("Kubernetes fixture operation failed; response suppressed");}
    }
    private KubernetesRuntimeGateway gateway(){return new KubernetesRuntimeGateway(required("EDGEAI_RUNTIME_TEST_URL"),required("EDGEAI_RUNTIME_TEST_TOKEN_FILE"),required("EDGEAI_RUNTIME_TEST_CA_FILE"),NAMESPACE,"edgeai-runner");}
    private RuntimeInstance runtime(){UUID attempt=UUID.randomUUID();return new RuntimeInstance(UUID.randomUUID(),attempt,UUID.randomUUID(),UUID.randomUUID(),1,NAMESPACE,"edgeai-"+attempt,UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,null,null,Instant.now(),Instant.now());}
    private Map<?,?> map(Object value){return (Map<?,?>)value;}
    private List<?> items(String...args){return (List<?>)map(JSON.decode(kubectl(args))).get("items");}
    private void await(BooleanSupplier check,int seconds,String failure)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        do{if(check.getAsBoolean())return;Thread.sleep(500);}while(System.nanoTime()<deadline);
        throw new AssertionError(failure);
    }
    private Map<?,?> scheduledNode(){
        return items("get","nodes","-o","json").stream().map(this::map).filter(n->{
            var metadata=map(n.get("metadata"));var labels=map(metadata.get("labels"));var spec=map(n.get("spec"));
            if(!"amd64".equals(labels.get("kubernetes.io/arch")) || Boolean.TRUE.equals(spec.get("unschedulable")))return false;
            var taints=(List<?>)spec.get("taints");if(taints!=null&&taints.stream().map(this::map).anyMatch(t->Set.of("NoSchedule","NoExecute").contains(t.get("effect"))))return false;
            return ((List<?>)map(n.get("status")).get("conditions")).stream().map(this::map).anyMatch(c->"Ready".equals(c.get("type"))&&"True".equals(c.get("status")));
        }).findFirst().orElseThrow(()->new IllegalStateException("No Ready schedulable amd64 Node for the real test"));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> job(RuntimeInstance r,Map<?,?> node)throws Exception{
        var spec=(Map<String,Object>)JSON.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("image","public.ecr.aws/docker/library/postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24");
        spec.put("platform",Map.of("os","linux","architectures",List.of("amd64")));spec.put("timeoutSeconds",240);
        var meta=node==null?null:map(node.get("metadata"));
        var launch=new RuntimeLaunch(r.runId(),r.taskId(),r.attemptId(),1,NAMESPACE,"edgeai-runner",r.jobName()+"-claim",URI.create("http://edgeai-api.edgeai.svc:18080"),
            meta==null?null:UUID.fromString((String)meta.get("uid")),meta==null?null:(String)meta.get("name"));
        var job=new KubernetesJobCompiler().compile(ServiceExecutionInput.parseSpec(JSON.canonical(spec)),launch);
        var pod=map(map(job.get("spec")).get("template"));
        var container=(Map<String,Object>)((List<?>)map(pod.get("spec")).get("containers")).getFirst();
        // Test fixture only: keep the compiled affinity, ownership, projected identity and security settings.
        container.put("command",List.of("sleep","240"));return job;
    }
    @Test void actualAutoSchedulerPodIdentityWatchAndUidDeletion()throws Exception{exercise(null);}
    @Test void actualNodeAffinityRetainsSchedulerAndMatchesNodeUid()throws Exception{exercise(scheduledNode());}
    private void exercise(Map<?,?> requestedNode)throws Exception{
        var r=runtime();var job=job(r,requestedNode);String claim=UUID.randomUUID().toString();
        try(var gateway=gateway()){
            try{
                UUID uid=gateway.ensureJob(r,job,claim);assertThat(gateway.ensureJob(r,job,claim)).isEqualTo(uid);
                final String[] podName={null};
                await(()->{
                    var pods=items("-n",NAMESPACE,"get","pods","-l","edgeai.io/attempt-id="+r.attemptId(),"-o","json");
                    for(var value:pods){var pod=map(value);if("Running".equals(map(pod.get("status")).get("phase"))){podName[0]=(String)map(pod.get("metadata")).get("name");return true;}}
                    return false;
                },120,"Real scheduler/fixture Pod did not reach Running within 120 seconds");
                String proof=kubectl("-n",NAMESPACE,"exec",podName[0],"--","cat","/var/run/edgeai-identity/token");
                var identity=gateway.authenticatePod(r,proof);assertThat(identity.jobUid()).isEqualTo(uid);
                var actual=map(JSON.decode(kubectl("-n",NAMESPACE,"get","pod",podName[0],"-o","json")));
                assertThat(identity.podUid().toString()).isEqualTo(map(actual.get("metadata")).get("uid"));
                assertThat(identity.nodeName()).isEqualTo(map(actual.get("spec")).get("nodeName"));
                if(requestedNode!=null){var metadata=map(requestedNode.get("metadata"));assertThat(identity.nodeUid().toString()).isEqualTo(metadata.get("uid"));assertThat(identity.nodeName()).isEqualTo(metadata.get("name"));}
                // A valid unbound SA token with the right audience cannot impersonate a Pod.
                String unbound=kubectl("-n",NAMESPACE,"create","token","edgeai-runner","--audience=edgeai-runner","--duration=10m");
                assertThatThrownBy(()->gateway.authenticatePod(r,unbound)).isInstanceOfSatisfying(RuntimeGatewayException.class,e->assertThat(e.reason()).isEqualTo(RuntimeGatewayException.Reason.AUTH_REJECTED));
                var snapshot=gateway.listJobs();assertThat(snapshot.jobs().get(r.attemptId()).jobUid()).isEqualTo(uid);
                assertThat(gateway.watchJobs(snapshot.resourceVersion())).isNotBlank();
                await(()->gateway.stop(r),60,"Owned Job/Pod/Secret deletion did not finish");
                assertThat(items("-n",NAMESPACE,"get","jobs,pods,secrets","-l","edgeai.io/attempt-id="+r.attemptId(),"-o","json")).isEmpty();
                assertThatThrownBy(()->gateway.authenticatePod(r,proof)).isInstanceOf(RuntimeGatewayException.class);
            }finally{await(()->gateway.stop(r),60,"Fixture cleanup did not finish; retain attempt ID "+r.attemptId());}
        }
    }
}
