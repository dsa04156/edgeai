package io.edgeai.app.config;

import io.edgeai.app.controller.PlatformController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlatformController.class, properties = {
    "edgeai.recovery.inspect-only=true", "spring.security.user.name=reader", "spring.security.user.password=test-only"})
@Import(SecurityConfiguration.class)
class RecoveryInspectionSecurityTest {
    @Autowired MockMvc mvc;

    @Test void readsDoNotRequireCredentials() throws Exception {
        mvc.perform(get("/api/v1/platform").with(httpBasic("reader", "test-only"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/platform")).andExpect(status().isOk());
    }

    @Test void validAuthenticationAndCsrfCannotAuthorizeWrites() throws Exception {
        for (var request : java.util.List.of(post("/api/v1/platform"), put("/api/v1/platform"),
                patch("/api/v1/platform"), delete("/api/v1/platform")))
            mvc.perform(request.with(httpBasic("reader", "test-only")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/attempts/claim").with(httpBasic("reader", "test-only")).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
