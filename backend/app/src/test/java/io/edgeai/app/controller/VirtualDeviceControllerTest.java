package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.VirtualDeviceService;
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

@WebMvcTest(controllers=VirtualDeviceController.class,properties="spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class VirtualDeviceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean VirtualDeviceService service;
    @Test void writesRequireCsrf() throws Exception {
        String path="/api/v1/virtual-devices",detail=path+"/"+UUID.randomUUID();
        for(var request:List.of(post(path),patch(detail),delete(detail),delete(detail+"/registration")))
            mvc.perform(request.contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void permanentDeletionIsSeparateFromRelease() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(delete("/api/v1/virtual-devices/"+id+"/registration").with(csrf())).andExpect(status().isNoContent());
        verify(service).deleteRegistration(id);verify(service,never()).release(id);
    }
    @Test void dbFailureIs503WithoutConnectionDetails() throws Exception {
        when(service.list(20,0)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private server password"));
        mvc.perform(get("/api/v1/virtual-devices")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("VD_STORE_UNAVAILABLE"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
    }
    @Test void invalidRouteAndQueryHaveStableErrors() throws Exception {
        mvc.perform(get("/api/v1/virtual-devices/not-a-uuid")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_VIRTUAL_DEVICE"));
        mvc.perform(get("/api/v1/virtual-devices?limit=abc")).andExpect(status().isBadRequest());
    }
}
