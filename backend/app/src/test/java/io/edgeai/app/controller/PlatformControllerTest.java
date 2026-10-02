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
    void anonymousRequestsCannotReadPlatformMetadata() throws Exception {
        mvc.perform(get("/api/v1/platform")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void reportsOnlyImplementedCapabilities() throws Exception {
        mvc.perform(get("/api/v1/platform"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("edgeai"))
            .andExpect(jsonPath("$.milestone").value("M3"))
            .andExpect(jsonPath("$.capabilities[0]").value("profiles"));
    }
}
