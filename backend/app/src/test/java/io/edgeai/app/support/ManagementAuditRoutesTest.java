package io.edgeai.app.support;

import java.util.Map;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;

class ManagementAuditRoutesTest {
    @Test void everyManagementOperationHasTheExactReviewedContractVocabulary() throws Exception {
        try (var source=getClass().getResourceAsStream("/static/openapi.yaml")) {
            assertThat(source).isNotNull();Map<String,Object> contract=new Yaml().load(source);
            var expected=new HashSet<String>();
            for (var path:((Map<String,Map<String,Object>>)contract.get("paths")).entrySet()) {
                if (!path.getKey().startsWith("/api/v1/")) continue;
                for (var operation:path.getValue().entrySet())
                    if (operation.getValue() instanceof Map<?,?> value && value.containsKey("operationId"))
                        expected.add(operation.getKey().toUpperCase()+" "+path.getKey()+" "+value.get("operationId"));
            }
            assertThat(ManagementAuditRoutes.ROUTES.stream().map(r->r.method()+" "+r.template()+" "+r.operation()).toList())
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        }
    }
    @Test void malformedAndUnmappedPathsNeverBecomeStoredRawValues() {
        var known=ManagementAuditRoutes.request("PATCH","/api/v1/devices/PRIVATE-CANARY");
        assertThat(known.operation()).isEqualTo("updateDevice");assertThat(known.targetId()).isNull();assertThat(known.toString()).doesNotContain("PRIVATE-CANARY");
        var unknown=ManagementAuditRoutes.request("POST","/api/v1/PRIVATE-CANARY/unknown");
        assertThat(unknown.operation()).isEqualTo("unmappedManagementRequest");assertThat(unknown.toString()).doesNotContain("PRIVATE-CANARY");
    }
}
