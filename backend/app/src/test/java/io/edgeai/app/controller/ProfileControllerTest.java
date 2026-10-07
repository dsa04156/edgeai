package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.exception.ProfileConflictException;
import io.edgeai.app.service.ProfileService;
import io.edgeai.app.support.ProfileJson;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@WebMvcTest(controllers = ProfileController.class, properties = "spring.security.user.password=test-only-unused")
@Import({SecurityConfiguration.class, ProfileJson.class})
class ProfileControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProfileService service;
    @Test void deletingUnusedVersionRequiresCsrfAndReportsReferences() throws Exception {
        String path = "/api/v1/profiles/SERVICE/example/versions/1.0.0";
        mvc.perform(delete(path)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        mvc.perform(delete(path).with(csrf())).andExpect(status().isNoContent());
        doThrow(new io.edgeai.app.exception.ProfileInUseException()).when(service).delete(any());
        mvc.perform(delete(path).with(csrf())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROFILE_IN_USE"));
    }
    @Test void writesRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/profiles/DEVICE").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void rejectsUnknownKindAndInvalidPaginationTypes() throws Exception {
        mvc.perform(get("/api/v1/profiles/UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/profiles/DEVICE?limit=abc")).andExpect(status().isBadRequest());
    }
    @Test void exposesStableErrorsWithoutStorageDetails() throws Exception {
        when(service.publish(any(), anyString())).thenThrow(new ProfileConflictException());
        mvc.perform(post("/api/v1/profiles/DEVICE").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_CONFLICT"));
        when(service.list(any(), any(), anyInt(), anyInt())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret connection details"));
        mvc.perform(get("/api/v1/profiles/DEVICE"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PROFILE_STORE_UNAVAILABLE"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }
}
