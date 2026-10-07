package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.SensorRegistrationService;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.node.SensorRegistrationSource.*;
import java.util.List;
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

@WebMvcTest(controllers = SensorRegistrationController.class, properties = "spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class SensorRegistrationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SensorRegistrationService service;
    private final String body = "{\"name\":\"sensor-2\",\"templateId\":\"etri-arduino-temperature\",\"endpoint\":\"/dev/edgeai/arduino-002\",\"physicalDeviceId\":\"arduino-002\",\"baudRate\":115200}";
    @Test void optionsAreNoStoreAndWritesRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/sensors/registrations").contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        when(service.catalog()).thenReturn(new Catalog(List.of()));
        mvc.perform(get("/api/v1/sensors/registration-options")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.templates").isArray());
    }
    @Test void newAndExistingRegistrationsHaveDistinctStatuses() throws Exception {
        when(service.register(any())).thenReturn(new Result("sensor-2", "device-serial-jetson", "etri-arduino-temperature", true, "UNLOCKED", "DOWN"))
            .thenReturn(new Result("sensor-2", "device-serial-jetson", "etri-arduino-temperature", false, "UNLOCKED", "UP"));
        mvc.perform(post("/api/v1/sensors/registrations").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.created").value(true)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/v1/sensors/registrations").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
    }
    @Test void controlledConflictAndUnknownOutcomeReachClient() throws Exception {
        when(service.register(any())).thenThrow(new ControlPlaneException(409,"SENSOR_REGISTRATION_CONFLICT","중복"))
            .thenThrow(new ControlPlaneException(502,"SENSOR_REGISTRATION_UNKNOWN","결과 미확인"));
        mvc.perform(post("/api/v1/sensors/registrations").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SENSOR_REGISTRATION_CONFLICT"));
        mvc.perform(post("/api/v1/sensors/registrations").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("SENSOR_REGISTRATION_UNKNOWN"));
    }
}
