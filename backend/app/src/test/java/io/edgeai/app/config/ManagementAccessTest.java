package io.edgeai.app.config;

import io.edgeai.app.controller.PlatformController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PlatformController.class)
@Import({SecurityConfiguration.class, SwaggerUiConfiguration.class})
class ManagementAccessTest {
    @Autowired MockMvc mvc;
    @Test void anonymousAndStaleBrowserCredentialsDoNotTriggerPasswordPrompts() throws Exception {
        mvc.perform(get("/api/v1/platform")).andExpect(status().isOk())
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/v1/platform").header("Authorization", "Basic b2xkOndyb25n"))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/")).andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/internal/unconfigured")).andExpect(status().isForbidden());
    }
}
