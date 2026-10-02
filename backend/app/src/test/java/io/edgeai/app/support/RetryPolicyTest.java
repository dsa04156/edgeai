package io.edgeai.app.support;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static io.edgeai.app.support.WorkflowInput.*;

class RetryPolicyTest {
    @Test void refusesInvalidBudgetsUnsafeCodesDuplicateCodesAndUnknownFields() {
        for(var change:List.of(Map.of("maxAttempts",0),Map.of("maxAttempts",9),Map.of("maxAttempts",1.5),Map.of("maxAttempts",9007199254740993L),
                Map.of("backoffSeconds",0),Map.of("backoffSeconds",301),Map.of("maxElapsedSeconds",86401),
                Map.of("retryOn",List.of()),Map.of("retryOn",List.of("OUTPUT_INVALID")),Map.of("retryOn",List.of("WORKLOAD_FAILED","WORKLOAD_FAILED")),Map.of("unknown",1))) {
            var input=new HashMap<String,Object>(Map.of("maxAttempts",2,"backoffSeconds",5,"maxElapsedSeconds",600,"retryOn",List.of("WORKLOAD_FAILED")));input.putAll(change);
            assertThatThrownBy(()->retryPolicy(JSON.decode(JSON.canonical(input)))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->retryPolicy(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
