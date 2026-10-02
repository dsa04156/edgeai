package io.edgeai.app.support;

import io.edgeai.adapters.kubernetes.KubernetesVDPodCompiler;
import io.edgeai.domain.vd.VDRuntimeLaunch;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KubernetesVDPodCompilerTest {
    private final JsonDocuments json=new JsonDocuments();
    private VDRuntimeLaunch launch(boolean node,int concurrency) {
        return new VDRuntimeLaunch(UUID.randomUUID(),UUID.randomUUID(),2,"edgeai-runtimes","edgeai-runner",URI.create("http://edgeai-api.edgeai.svc:18080"),
            node?UUID.randomUUID():null,node?"worker-1":null,concurrency,60,30);
    }
    @Test void compilesPersistentVdIdentityReadinessAndSchedulerPolicyWithoutTaskOrJob() throws Exception {
        var service=ServiceExecutionInput.parseSpec(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        var compiler=new KubernetesVDPodCompiler();
        for(boolean node:List.of(false,true)) {
            var launch=launch(node,2);var document=compiler.compile(service,launch);var spec=(Map<?,?>)document.get("spec");
            assertThat(document.get("kind")).isEqualTo("Pod");assertThat(spec.get("restartPolicy")).isEqualTo("Never");
            assertThat(spec.containsKey("activeDeadlineSeconds")).isFalse();assertThat(spec.containsKey("nodeName")).isFalse();
            assertThat(spec.get("automountServiceAccountToken")).isEqualTo(false);
            var text=json.canonical(document);assertThat(text).doesNotContain("attempt-id","task-id","run-id","hostPath","hostNetwork");
            assertThat(text).contains(launch.vdId().toString(),launch.runtimeId().toString(),"/opt/edgeai/vd.py","--ready","edgeai-vd");
            assertThat(text.contains("metadata.name")).isEqualTo(node);
            var container=(Map<?,?>)((List<?>)spec.get("containers")).getFirst();
            assertThat(container.get("resources")).isEqualTo(Map.of("requests",service.resources().requests(),"limits",service.resources().limits()));
            assertThat(container.get("readinessProbe")).isNotNull();assertThat(container.get("startupProbe")).isNotNull();
            var volume=(Map<?,?>)((List<?>)spec.get("volumes")).getFirst();
            assertThat(((Map<?,?>)volume.get("emptyDir")).get("sizeLimit")).isEqualTo(Long.toString(16777216L+2*service.workBytes()));
            assertThat(compiler.compile(service,launch)).isEqualTo(document);
            Files.createDirectories(Path.of("build/runtime-fixtures"));
            Files.writeString(Path.of("build/runtime-fixtures/vd-"+(node?"node":"auto")+"-pod.json"),text);
        }
    }
    @Test void launchRejectsAmbiguousIdentityOutOfBoundsPolicyAndCredentialBearingOrigins() {
        for(String origin:List.of("http://user:password@localhost","http://localhost/path","http://localhost?token=x","file:///tmp/api"))
            assertThatThrownBy(()->new VDRuntimeLaunch(UUID.randomUUID(),UUID.randomUUID(),1,"edgeai-runtimes","edgeai-runner",URI.create(origin),null,null,1,60,30)).isInstanceOf(IllegalArgumentException.class);
        for(int capacity:List.of(0,17))assertThatThrownBy(()->launch(false,capacity)).isInstanceOf(IllegalArgumentException.class);
        var valid=launch(false,1);
        assertThatThrownBy(()->new VDRuntimeLaunch(valid.vdId(),valid.runtimeId(),0,valid.namespace(),valid.serviceAccount(),valid.controlPlane(),null,null,1,60,30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new VDRuntimeLaunch(valid.vdId(),valid.runtimeId(),1,valid.namespace(),valid.serviceAccount(),valid.controlPlane(),UUID.randomUUID(),null,1,60,30)).isInstanceOf(IllegalArgumentException.class);
    }
}
