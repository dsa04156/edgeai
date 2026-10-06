package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.service.ExecutionService;
import io.edgeai.app.service.StreamRunService;
import io.edgeai.app.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {WorkflowController.class, WorkflowRunController.class, StreamRunController.class,
    PlatformController.class, EnabledApiContractController.class}, properties = {
        "spring.security.user.password=test-only-unused", "edgeai.workflow.enabled=false"})
@Import(SecurityConfiguration.class)
class WorkflowDisabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean WorkflowService workflows;
    @MockitoBean ExecutionService executions;
    @MockitoBean StreamRunService streams;

    @Test void authenticatedWorkflowReadsAndCommandsAreUnavailable() throws Exception {
        var id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        for (var path : new String[]{"/workflows", "/workflows/" + id, "/workflow-runs",
                "/workflow-runs/" + id, "/workflow-runs/" + id + "/streams"})
            mvc.perform(get("/api/v1" + path).with(user("test"))).andExpect(status().isNotFound());
        for (var path : new String[]{"/workflows", "/workflows/" + id + "/versions", "/workflow-runs",
                "/workflow-runs/" + id + "/cancel"})
            mvc.perform(post("/api/v1" + path).with(user("test")).with(csrf())
                .contentType("application/json").content("{}")).andExpect(status().isNotFound());
        verifyNoInteractions(workflows, executions, streams);
    }

    @Test void metadataAndSwaggerDoNotAdvertiseWorkflowEndpoints() throws Exception {
        mvc.perform(get("/api/v1/platform").with(user("test")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.capabilities", not(hasItem("workflows"))))
            .andExpect(jsonPath("$.capabilities", not(hasItem("runs"))))
            .andExpect(jsonPath("$.capabilities", hasItem("virtual-devices")));
        mvc.perform(get("/openapi.yaml").with(user("test")))
            .andExpect(status().isOk()).andExpect(content().string(not(containsString("/api/v1/workflows"))))
            .andExpect(content().string(not(containsString("/api/v1/workflow-runs"))))
            .andExpect(content().string(containsString("operationId: publishProfile")));
        mvc.perform(get("/openapi.yaml")).andExpect(status().isUnauthorized());
    }
}
