package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.VDExecutionService;
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

@WebMvcTest(controllers=VDExecutionController.class,properties="spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class VDExecutionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean VDExecutionService service;
    @Test void csrfAndRequiredKeyProtectAllCommands()throws Exception {
        String path="/api/v1/virtual-devices/"+UUID.randomUUID();
        for(String action:List.of("provision","replace","drain")) {
            mvc.perform(post(path+"/"+action).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mvc.perform(post(path+"/"+action).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void storageAndInvalidRouteAreSanitized()throws Exception {
        UUID id=UUID.randomUUID();when(service.status(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private password"));
        mvc.perform(get("/api/v1/virtual-devices/"+id+"/execution")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("VD_STORE_UNAVAILABLE")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
        mvc.perform(get("/api/v1/virtual-devices/bad/execution")).andExpect(status().isBadRequest());
    }
}
