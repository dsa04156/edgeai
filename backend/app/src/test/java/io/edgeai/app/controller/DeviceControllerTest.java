package io.edgeai.app.controller;
import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.*;
import java.time.*;
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

@WebMvcTest(controllers={DeviceController.class,NodeController.class},properties="spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class DeviceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DeviceService devices;
    @MockitoBean NodeService nodes;
    @MockitoBean Clock clock;
    @Test void deletingRegistrationRequiresCsrfAndReportsUsage() throws Exception {
        var id=UUID.randomUUID();String path="/api/v1/devices/"+id+"/registration";
        mvc.perform(delete(path)).andExpect(status().isForbidden());
        verifyNoInteractions(devices);
        mvc.perform(delete(path).with(csrf())).andExpect(status().isNoContent());
        doThrow(new io.edgeai.app.exception.ControlPlaneException(409,"DEVICE_IN_USE","사용 중인 장치입니다.")).when(devices).delete(id);
        mvc.perform(delete(path).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_IN_USE"));
    }
    @Test void allWriteVerbsRequireCsrf() throws Exception {
        String id=UUID.randomUUID().toString();
        for (var request:List.of(post("/api/v1/devices"),patch("/api/v1/devices/"+id),delete("/api/v1/devices/"+id),put("/api/v1/devices/"+id+"/attachments/"+id)))
            mvc.perform(request.contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(devices,nodes);
    }
    @Test void dbFailureIs503WithoutLeakingConnectionDetails() throws Exception {
        when(devices.list(20,0)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private server password"));
        mvc.perform(get("/api/v1/devices")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DEVICE_STORE_UNAVAILABLE"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
    }
    @Test void malformedIdsAndPaginationHaveStableErrors() throws Exception {
        mvc.perform(get("/api/v1/devices/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/nodes?limit=abc")).andExpect(status().isBadRequest());
    }
}
