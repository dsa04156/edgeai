package io.edgeai.app.support;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowSourceInputTest {
    @Test void sensorAndVirtualOutputAreDistinctTypedSelections() {
        assertEquals(Map.of("kind","SENSOR","deviceId","temperature","property","value"),
            WorkflowSourceInput.parse(Map.of("kind","SENSOR","deviceId","temperature","property","value")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowSourceInput.parse(
            Map.of("kind","SENSOR","deviceId","temperature","property","value","outputId","mean")));
        assertEquals("CONTINUOUS_VD", WorkflowSourceInput.parse(Map.of("kind","CONTINUOUS_VD",
            "vdId","12345678-1234-4234-8234-123456789012","outputId","mean")).get("kind"));
    }
}
