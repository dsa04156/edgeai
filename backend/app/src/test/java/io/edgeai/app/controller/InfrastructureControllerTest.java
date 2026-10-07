package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.InfrastructureService;
import io.edgeai.domain.node.InfrastructureSource.*;
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

@WebMvcTest(controllers = InfrastructureController.class, properties = "spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class InfrastructureControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean InfrastructureService service;
    @Test void exposesIndependentNodeAndEdgeXInventoryWithoutCaching() throws Exception {
        when(service.snapshot()).thenReturn(new Snapshot("AVAILABLE", "AVAILABLE", Instant.now(),
            List.of(new Node("server", "EDGE_AI_SERVER", "arm64", "linux", "READY")),
            List.of(new Sensor("sensor", "edge", "temperature", "serial", "UNLOCKED", "UP", List.of("temp")))));
        mvc.perform(get("/api/v1/infrastructure")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.nodes[0].kind").value("EDGE_AI_SERVER"))
            .andExpect(jsonPath("$.sensors[0].operatingState").value("UP"))
            .andExpect(jsonPath("$.sensors[0].properties[0]").value("temp"));
    }
}
