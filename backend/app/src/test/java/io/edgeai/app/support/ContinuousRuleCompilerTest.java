package io.edgeai.app.support;

import java.util.*;
import io.edgeai.domain.vd.ProcessingExpression;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContinuousRuleCompilerTest {
    @Test void windowsAreIsolatedBeforeCountingAndRequireTheWholeWindow() {
        UUID vd=UUID.randomUUID(),generation=UUID.randomUUID();
        var config=WorkflowInput.JSON.decode(WorkflowInput.JSON.canonical(Map.of("intervalSeconds",1,"retentionDays",7,
            "outputs",List.of(Map.of("name","avg","expression","a + b","operation","movingAverage","sampleCount",3,"unit","°C")))));
        var rule=ContinuousRuleCompiler.compile(vd,generation,config,Map.of("a",ProcessingExpression.Type.NUMBER,"b",ProcessingExpression.Type.NUMBER)).getFirst();
        assertThat(rule.sql()).contains("AVG((cast(x0,\"float\") + cast(x1,\"float\")))","COUNTWINDOW(3,1) FILTER(WHERE vdId='"+vd,"HAVING COUNT(*)=3","LAST_VALUE(evaluationId,false)");
        assertThat(rule.sql()).doesNotContain("FROM edgeai_vd_frames WHERE");
        assertThat(rule.sql()).contains("LAST_VALUE(vdId,false) AS frameVdId", "LAST_VALUE(generation,false) AS frameGeneration");
        assertThat(rule.sql()).doesNotContain("LAST_VALUE(vdId,false) AS vdId", "LAST_VALUE(generation,false) AS generation");
        assertThat(rule.id()).startsWith("edgeai_vd_");
    }
    @Test void booleanMeasurementsCannotEnterNumericWindows() {
        var config=WorkflowInput.JSON.decode(WorkflowInput.JSON.canonical(Map.of("intervalSeconds",1,"retentionDays",7,
            "outputs",List.of(Map.of("name","avg","expression","a","operation","movingAverage","sampleCount",3,"unit","")))));
        assertThatThrownBy(()->ContinuousRuleCompiler.compile(UUID.randomUUID(),UUID.randomUUID(),config,Map.of("a",ProcessingExpression.Type.BOOLEAN)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
