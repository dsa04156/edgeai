package io.edgeai.app.controller;

import io.edgeai.app.config.InferenceConfiguration;
import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.InferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InferenceController.class)
@Import({SecurityConfiguration.class, InferenceConfiguration.class, InferenceService.class})
class InferenceDisabledTest {
    @Autowired MockMvc mvc;
    @Test void unconfiguredInferenceExposesNoModels() throws Exception {
        mvc.perform(get("/api/v1/inference/models")).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(post("/api/v1/inference/predict").with(csrf()).contentType("application/json")
                .content("{\"modelId\":\"detector-v1\",\"imageBase64\":\"iVBORw0KGgo=\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INFERENCE_MODEL_NOT_FOUND"));
    }
}
