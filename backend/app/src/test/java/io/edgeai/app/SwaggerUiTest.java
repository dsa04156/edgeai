package io.edgeai.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PlatformController.class, properties = "spring.security.user.password=test-only-unused")
@Import({SecurityConfiguration.class, SwaggerUiConfiguration.class})
class SwaggerUiTest {
    @Autowired MockMvc mvc;
    @Test void documentationAndAssetsRequireAuthentication() throws Exception {
        for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html", "/openapi.yaml", "/swagger-ui/assets/swagger-ui-bundle.js"})
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
    @Test void servesPackagedUiAndTheReviewedContract() throws Exception {
        mvc.perform(get("/swagger-ui.html").with(user("test")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html").with(user("test")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("EdgeAI API")));
        mvc.perform(get("/swagger-ui/assets/swagger-ui-bundle.js").with(user("test")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("SwaggerUIBundle")));
        mvc.perform(get("/openapi.yaml").with(user("test")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("operationId: publishProfile")))
            .andExpect(content().string(containsString("openapi: 3.1.0")));
    }
}
