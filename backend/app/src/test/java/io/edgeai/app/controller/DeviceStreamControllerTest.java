package io.edgeai.app.controller;
import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.exception.StreamExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=DeviceStreamController.class,properties="spring.security.user.password=test-only-unused")
@Import({SecurityConfiguration.class,StreamExceptionHandler.class})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class DeviceStreamControllerTest {
    @Autowired MockMvc mvc;
    @Test void disabledBindingRemainsExplicitAndDoesNotRelaxManagementCsrf()throws Exception {
        String path="/api/v1/devices/"+UUID.randomUUID()+"/sessions/"+UUID.randomUUID()+"/stream-token";
        mvc.perform(post(path).with(user("manager")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("STREAM_DISABLED")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(path).with(user("manager")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/devices/invalid/sessions/"+UUID.randomUUID()+"/stream-token").with(user("manager")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STREAM_REQUEST"));
    }
}
