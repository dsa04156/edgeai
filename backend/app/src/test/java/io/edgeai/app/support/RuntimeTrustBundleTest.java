package io.edgeai.app.support;

import io.edgeai.adapters.kubernetes.*;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.vd.VDRuntimeLaunch;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeTrustBundleTest {
    private final JsonDocuments json=new JsonDocuments();
    private final URI origin=URI.create("https://control.example:18443");
    private Map<?,?> map(Object value){return (Map<?,?>)value;}
    private void trust(Map<?,?> pod){
        var volumes=(List<?>)pod.get("volumes");
        var ca=volumes.stream().map(this::map).filter(v->v.get("name").equals("edgeai-trust")).findFirst().orElseThrow();
        assertThat(ca.get("configMap")).isEqualTo(Map.of("name","edgeai-ca-v1","defaultMode",292,"items",List.of(Map.of("key","ca.crt","path","ca.crt"))));
        var container=map(((List<?>)pod.get("containers")).getFirst());
        assertThat((List<?>)container.get("env")).anyMatch(v->v.equals(Map.of("name","SSL_CERT_FILE","value","/var/run/edgeai-trust/ca.crt")));
        assertThat((List<?>)container.get("volumeMounts")).anyMatch(v->v.equals(Map.of("name","edgeai-trust","mountPath","/var/run/edgeai-trust","readOnly",true)));
        assertThat(map(container.get("securityContext")).get("readOnlyRootFilesystem")).isEqualTo(true);
        assertThat(json.canonical(pod)).doesNotContain("verify=false","CERT_NONE","hostPath");
    }
    @Test void jobAndVdReceiveOnlyTheExplicitPublicCaBundle()throws Exception{
        var spec=ServiceExecutionInput.parseSpec(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        var base=new RuntimeLaunch(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,"edgeai-runtimes","edgeai-runner","claim",origin,null,null);
        assertThat(json.canonical(new KubernetesJobCompiler().compile(spec,base))).doesNotContain("edgeai-trust","SSL_CERT_FILE");
        var launch=new RuntimeLaunch(base.runId(),base.taskId(),base.attemptId(),1,base.namespace(),base.serviceAccount(),base.claimSecret(),origin,null,null,List.of(),"edgeai-ca-v1");
        trust(map(map(map(new KubernetesJobCompiler().compile(spec,launch).get("spec")).get("template")).get("spec")));
        var vd=new VDRuntimeLaunch(UUID.randomUUID(),UUID.randomUUID(),1,base.namespace(),base.serviceAccount(),origin,null,null,1,60,30,"edgeai-ca-v1");
        trust(map(new KubernetesVDPodCompiler().compile(spec,vd).get("spec")));
    }
    @Test void vdLegacyConfigurationKeepsItsDigestAndTrustIsPinnedWhenPresent(){
        var legacy=new TreeMap<String,Object>();
        legacy.putAll(Map.of("namespace","edgeai-runtimes","serviceAccount","edgeai-runner","controlPlane",origin.toString(),
            "serviceProfileVersionId",UUID.randomUUID().toString(),"sources",Map.of(),"placementMode","AUTO","maxConcurrentTasks",1,"startupSeconds",60,"drainSeconds",30));
        legacy.put("targetNodeId",null);legacy.put("targetNodeName",null);String text=json.canonical(legacy);
        assertThat(VDRuntimeDocuments.digest(text)).isEqualTo(json.digest("edgeai-vd-runtime-configuration-v1",legacy));
        assertThat(VDRuntimeDocuments.launch(UUID.randomUUID(),UUID.randomUUID(),1,text).caConfigMap()).isEmpty();
        assertThat(VDRuntimeDocuments.settings(text).caConfigMap()).isEmpty();
        legacy.put("caConfigMap","edgeai-ca-v1");String trusted=json.canonical(legacy);
        assertThat(VDRuntimeDocuments.digest(trusted)).isNotEqualTo(VDRuntimeDocuments.digest(text));
        assertThat(VDRuntimeDocuments.launch(UUID.randomUUID(),UUID.randomUUID(),1,trusted).caConfigMap()).isEqualTo("edgeai-ca-v1");
        assertThat(VDRuntimeDocuments.settings(trusted).caConfigMap()).isEqualTo("edgeai-ca-v1");
        legacy.put("caConfigMap","");assertThatThrownBy(()->VDRuntimeDocuments.read(json.canonical(legacy))).isInstanceOf(IllegalArgumentException.class);
        legacy.put("caConfigMap","edgeai-ca-v1");legacy.put("skipTlsVerification",true);
        assertThatThrownBy(()->VDRuntimeDocuments.read(json.canonical(legacy))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void trustReferencesCannotEscapeTheRuntimeNamespaceOrBecomeArbitraryPaths(){
        for(String name:List.of("../ca.crt","another-namespace/ca"," ","CA", "bad..name")){
            assertThatThrownBy(()->new RuntimeSettings("edgeai-runtimes","edgeai-runner",origin,120,name)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->new RuntimeLaunch(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,"edgeai-runtimes","edgeai-runner","claim",origin,null,null,List.of(),name)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->new VDRuntimeLaunch(UUID.randomUUID(),UUID.randomUUID(),1,"edgeai-runtimes","edgeai-runner",origin,null,null,1,60,30,name)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
