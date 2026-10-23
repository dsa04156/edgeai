package io.edgeai.app.service;

import io.edgeai.app.config.HawkeyeVideoProperties;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeScope;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;

class HawkeyeVideoServiceTest {
    final HawkeyeVideoRepository repository = mock(HawkeyeVideoRepository.class);

    HawkeyeVideoService service(boolean runtimeEnabled, String scope, boolean inspection) {
        return new HawkeyeVideoService(repository, mock(ExecutionService.class), mock(ExecutionRepository.class), mock(RuntimeRepository.class),
            mock(WorkflowRepository.class), Optional.empty(), mock(TransactionTemplate.class),
            new HawkeyeVideoProperties(true, "hawkeye-video-safety", 3, Duration.ofMillis(20)), RuntimeScope.parse(scope),
            Clock.systemUTC(), runtimeEnabled, inspection);
    }

    @Test void sessionsAreNotAdvancedWithoutAnExecutingRuntimeOrInInspection() throws Exception {
        for (var service : List.of(service(false, "*", false), service(true, "other-workflow", false), service(true, "*", true))) {
            Thread.sleep(150);
            service.close();
        }
        verify(repository, never()).running();
    }

    @Test void anExecutingRuntimeAdvancesRunningSessions() throws Exception {
        var service = service(true, "hawkeye-video-safety", false);
        try { verify(repository, timeout(2000).atLeastOnce()).running(); }
        finally { service.close(); }
    }
}
