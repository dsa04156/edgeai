package io.edgeai.app.support;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContinuousInputTest {
    @Test void inputNamesAndExpressionsAreValidatedBeforeAContinuousProfileCanBePublished() {
        for(var expression:List.of("unknown + 1","a; DELETE FROM vd_frames","AVG(a)","a / (2 -)","a + true")) {
            String document=WorkflowInput.JSON.canonical(Map.of("intervalSeconds",1,"retentionDays",7,
                "outputs",List.of(Map.of("name","result","expression",expression,"operation","scalar","unit",""))));
            assertThatThrownBy(()->ContinuousInput.parse(WorkflowInput.JSON.decode(document),Set.of("a")))
                .as(expression).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void continuousProfileUsesManagedSourcesWithoutAServiceOrSupervisor() {
        var spec = VirtualDeviceInput.spec(WorkflowInput.JSON.canonical(Map.of(
            "apiVersion", "edgeai.vd/v3", "type", "processing", "executionMode", "CONTINUOUS_EKUIPER",
            "sources", Map.of("a", Map.of("deviceModel", "temperature-model", "required", true)),
            "continuous", Map.of("intervalSeconds", 1, "retentionDays", 7,
                "outputs", List.of(Map.of("name", "temperature", "expression", "a", "operation", "scalar", "unit", "°C"))))));
        assertThat(spec.serviceProfileVersionId()).isNull();
        assertThat(spec.sources().get("a").deviceModel()).isEqualTo("temperature-model");
    }
}
