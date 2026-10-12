package io.edgeai.app.support;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

class RuntimeParametersTest {
    @Test void processingAssignmentSupports1024TimestampedSamplesWithoutLosingPrecision() {
        var samples=new ArrayList<Object>();
        for(int index=0;index<1024;index++)samples.add(Map.of("value",new java.math.BigDecimal("48.28257369995117"),"observedAt",Instant.parse("2026-10-12T00:00:00Z").plusMillis(index*100).toString()));
        String frame=JSON.canonical(Map.of("apiVersion","edgeai.vd-input/v1","processing",Map.of("apiVersion","edgeai.processing/v1"),"sources",Map.of("input",Map.of("properties",Map.of("signal",Map.of("samples",samples))))));
        assertThat(frame.length()).isGreaterThan(65536);
        assertThat(RuntimeParameters.merge("{}","{}",frame)).contains("48.28257369995117").contains("2026-10-12T00:01:42.300Z");
    }
}
