package io.edgeai.app.controller;
import io.edgeai.app.service.OffloadService;
import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.exception.ControlPlaneException;
import java.util.UUID;
import java.time.Instant;
import io.edgeai.domain.repository.Creation;
import io.edgeai.domain.execution.OffloadOperation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=OperationController.class,properties="spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class OperationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OffloadService service;
    @MockitoBean io.edgeai.app.service.VDExecutionService vds;
    @Test void authenticationCsrfAndMissingIdempotencyPreventCommands() throws Exception {
        String id=UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/operations/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tasks/"+id+"/offload").with(user("test")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tasks/"+id+"/offload").with(user("test")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void notFoundAndStorageFailureHaveControlledResponses() throws Exception {
        UUID id=UUID.randomUUID();when(service.find(id)).thenThrow(new ControlPlaneException(404,"OPERATION_NOT_FOUND","작업 상태를 찾을 수 없습니다."));
        mvc.perform(get("/api/v1/operations/"+id).with(user("test"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private backend detail")).when(service).find(id);
        mvc.perform(get("/api/v1/operations/"+id).with(user("test"))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WORKFLOW_STORE_UNAVAILABLE"));
    }
    @Test void acceptedAndReplayedTransferReturnSamePublicOperationAndLocation() throws Exception {
        UUID task=UUID.randomUUID(),id=UUID.randomUUID(),key=UUID.randomUUID();Instant now=Instant.parse("2026-10-02T09:00:00Z");
        var operation=new OffloadOperation(id,task,UUID.randomUUID(),UUID.randomUUID(),null,UUID.randomUUID(),key,
            "sha256:"+"a".repeat(64),"fixture-namespace","DRAINING",null,now.plusSeconds(60),120,null,now,now);
        when(service.request(task,key.toString(),"{}")).thenReturn(new Creation<>(operation,true),new Creation<>(operation,false));
        for(int status:new int[]{202,200}) mvc.perform(post("/api/v1/tasks/"+task+"/offload").with(user("test")).with(csrf())
            .header("Idempotency-Key",key).contentType("application/json").content("{}"))
            .andExpect(status().is(status)).andExpect(header().string("Location","/api/v1/operations/"+id))
            .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.kind").value("TASK_OFFLOAD"))
            .andExpect(jsonPath("$.state").value("DRAINING")).andExpect(jsonPath("$.namespace").doesNotExist())
            .andExpect(jsonPath("$.idempotencyKey").doesNotExist()).andExpect(jsonPath("$.requestDigest").doesNotExist());
    }
}
