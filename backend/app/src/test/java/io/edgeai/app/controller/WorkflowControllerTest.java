package io.edgeai.app.controller;
import io.edgeai.app.service.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import io.edgeai.app.config.SecurityConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={WorkflowController.class,WorkflowRunController.class,TaskController.class},properties="spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class WorkflowControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean WorkflowService workflows;
    @MockitoBean ExecutionService executions;
    @Test void commandsWithoutCsrfCannotInvokeServices() throws Exception {
        String id=UUID.randomUUID().toString();
        for(String path:new String[]{"/workflows","/workflows/"+id+"/versions","/workflow-runs","/workflow-runs/"+id+"/cancel","/tasks/"+id+"/cancel"})
            mvc.perform(post("/api/v1"+path).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(workflows,executions);
    }
    @Test void missingIdempotencyAndMalformedIdentifiersAreControlledBadRequests() throws Exception {
        mvc.perform(post("/api/v1/workflow-runs").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WORKFLOW"));
        mvc.perform(get("/api/v1/tasks/not-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(workflows,executions);
    }
    @Test void unavailableStorageDoesNotExposeConnectionDetails() throws Exception {
        when(workflows.list(20,0)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("internal credential detail"));
        mvc.perform(get("/api/v1/workflows")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("WORKFLOW_STORE_UNAVAILABLE"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal credential"))));
    }
}
