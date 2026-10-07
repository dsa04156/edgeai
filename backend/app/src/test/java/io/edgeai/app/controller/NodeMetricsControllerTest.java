package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.NodeMetricsService;
import io.edgeai.domain.node.NodeMetricsSource.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NodeMetricsController.class, properties = "spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class NodeMetricsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean NodeMetricsService service;

    @Test void exposesReadOnlyNormalizedDataWithoutBrowserCaching() throws Exception {
        var now = Instant.parse("2026-10-06T06:00:00Z");
        when(service.snapshot()).thenReturn(new Snapshot("AVAILABLE", now, 90,
            List.of(new NodeMetrics("node-a", List.of(new Measurement("CPU_USAGE", "", 0, "PERCENT", now, true)), new Readiness("READY", now, true))), 0));
        mvc.perform(get("/api/v1/node-metrics")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items[0].nodeName").value("node-a"))
            .andExpect(jsonPath("$.items[0].readiness.status").value("READY"))
            .andExpect(jsonPath("$.items[0].readiness.observedAt").value(now.toString()))
            .andExpect(jsonPath("$.items[0].readiness.fresh").value(true))
            .andExpect(jsonPath("$.items[0].measurements[0].value").value(0))
            .andExpect(jsonPath("$.items[0].measurements[0].observedAt").value(now.toString()));
    }
    @Test void disabledAndUnavailableRemainDistinctFromEmptySuccess() throws Exception {
        for (String state : List.of("DISABLED", "UNAVAILABLE", "AVAILABLE")) {
            when(service.snapshot()).thenReturn(new Snapshot(state, Instant.now(), 90, List.of(), 0));
            mvc.perform(get("/api/v1/node-metrics")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state));
        }
    }
}
