package io.edgeai.app.support;

import io.edgeai.domain.runtime.RuntimeScope;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeScopeTest {
    @Test void wildcardKeepsEveryWorkflowInScope() {
        var scope = RuntimeScope.parse(" * ");
        assertSame(RuntimeScope.UNRESTRICTED, scope);
        assertFalse(scope.restricted());
        assertTrue(scope.allows("anything"));
        assertEquals("TRUE", scope.runCondition("run_id"));
    }
    @Test void keysAreNormalizedToASortedDistinctSet() {
        var scope = RuntimeScope.parse(" hawkeye-video-safety , other.flow,hawkeye-video-safety");
        assertEquals("hawkeye-video-safety,other.flow", scope.toString());
        assertEquals(RuntimeScope.parse("other.flow,hawkeye-video-safety"), scope);
        assertTrue(scope.allows("other.flow"));
        assertFalse(scope.allows("live-input-switch"));
        assertTrue(scope.runCondition("r.run_id").startsWith("r.run_id IN (SELECT scope_run.id FROM edgeai.workflow_run scope_run"));
        assertTrue(scope.runCondition("r.run_id").endsWith("WHERE scope_workflow.workflow_key IN ('hawkeye-video-safety','other.flow'))"));
    }
    @Test void anythingButWorkflowKeysIsRejected() {
        for (var value : new String[] {null, "", "  ", "a,,b", "UPPER", "x'); DROP TABLE edgeai.workflow;--", "*,a", "a b", "-a"})
            assertThrows(IllegalArgumentException.class, () -> RuntimeScope.parse(value), String.valueOf(value));
    }
}
