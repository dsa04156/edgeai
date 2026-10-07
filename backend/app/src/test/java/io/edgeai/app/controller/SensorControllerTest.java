package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.SensorService;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.node.SensorAccessSource.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=SensorController.class,properties="spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class SensorControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SensorService service;
    @Test void historyIsNoStoreAndMissingQueryIsInvalid() throws Exception {
        when(service.readings("sensor-1","",100)).thenReturn(new Readings("sensor-1",Instant.now(),List.of()));
        mvc.perform(get("/api/v1/sensors/readings?device=sensor-1")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.readings").isEmpty());
        mvc.perform(get("/api/v1/sensors/readings")).andExpect(status().isBadRequest());
    }
    @Test void commandRequiresCsrfAndPassesControlledRequest() throws Exception {
        var request="{\"device\":\"sensor-1\",\"command\":\"temp\",\"method\":\"GET\",\"values\":{}}";
        mvc.perform(post("/api/v1/sensors/command").contentType("application/json").content(request)).andExpect(status().isForbidden());verifyNoInteractions(service);
        when(service.execute("sensor-1","temp","GET",Map.of())).thenReturn(new CommandResult("sensor-1","temp","GET",Instant.now(),List.of()));
        mvc.perform(post("/api/v1/sensors/command").with(csrf()).contentType("application/json").content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.command").value("temp"));
    }
    @Test void unavailableAndLockedStatusesReachBrowser() throws Exception {
        when(service.commands("sensor-1")).thenThrow(new ControlPlaneException(502,"SENSOR_UNAVAILABLE","조회 실패"));
        mvc.perform(get("/api/v1/sensors/commands?device=sensor-1")).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("SENSOR_UNAVAILABLE"));
    }
}
