package io.edgeai.app.config;

import io.edgeai.app.controller.PlatformController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PlatformController.class, properties = "spring.security.user.password=test-only-unused")
@Import({SecurityConfiguration.class, SwaggerUiConfiguration.class})
class SwaggerUiTest {
    @Autowired MockMvc mvc;
    @Test void documentationAndAssetsArePublic() throws Exception {
        for (String path : new String[]{"/swagger-ui/index.html", "/openapi.yaml", "/stream-openapi.yaml", "/swagger-ui/assets/swagger-ui-bundle.js"})
            mvc.perform(get(path)).andExpect(status().isOk());
    }
    @Test void managementApiIsPublic() throws Exception {
        mvc.perform(get("/api/v1/platform")).andExpect(status().isOk());
    }
    @Test void servesPackagedUiAndTheReviewedContract() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("EdgeAI API")));
        mvc.perform(get("/swagger-ui/assets/swagger-ui-bundle.js"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("SwaggerUIBundle")));
        mvc.perform(get("/openapi.yaml"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("operationId: publishProfile")))
            .andExpect(content().string(containsString("openapi: 3.1.0")));
        mvc.perform(get("/stream-openapi.yaml"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("operationId: bindDeviceStream")))
            .andExpect(content().string(containsString("operationId: bindRunnerStream")));
    }
}
