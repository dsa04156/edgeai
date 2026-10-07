package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PlatformController.class, properties = "spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class PlatformControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void anonymousRequestsCanReadPlatformMetadata() throws Exception {
        mvc.perform(get("/api/v1/platform")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void reportsOnlyImplementedCapabilities() throws Exception {
        mvc.perform(get("/api/v1/platform"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("edgeai"))
            .andExpect(jsonPath("$.milestone").value("M4"))
            .andExpect(jsonPath("$.capabilities[0]").value("profiles"))
            .andExpect(jsonPath("$.capabilities[7]").value("virtual-devices"));
    }
    @Test @WithMockUser
    void disabledVdInternalEndpointsRemainDeniedToBasicUsers()throws Exception {
        mvc.perform(get("/internal/v1/vd-runtimes/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/poll"))
            .andExpect(status().isForbidden());
    }
}
