package io.edgeai.app.controller;
import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.dto.TaskResultsResponse;
import io.edgeai.app.service.ResultService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=ResultController.class,properties="spring.security.user.password=test-only-unused")
@Import(SecurityConfiguration.class)
class ResultControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ResultService results;
    @Test void authenticatedEmptyResultIsDistinctFromAuthenticationAndInvalidInput()throws Exception{
        UUID id=UUID.randomUUID();when(results.results(id)).thenReturn(new TaskResultsResponse(id,List.of()));
        mvc.perform(get("/api/v1/tasks/"+id+"/results")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tasks/"+id+"/results").with(user("fixture"))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/tasks/not-a-uuid/results").with(user("fixture"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TASK_ID"));
    }
    @Test void unavailableDatabaseIsNotReportedAsAnEmptySuccess()throws Exception{
        UUID id=UUID.randomUUID();when(results.results(id)).thenThrow(new DataAccessResourceFailureException("private connection details"));
        mvc.perform(get("/api/v1/tasks/"+id+"/results").with(user("fixture"))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("RESULT_STORE_UNAVAILABLE"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private connection details"))));
    }
    @Test void remoteResultExposesAllocationAndSyntheticSourceWithoutInventingAPod()throws Exception{
        UUID task=UUID.randomUUID(),allocation=UUID.randomUUID();
        var result=new TaskResultsResponse.Result(UUID.randomUUID(),task,UUID.randomUUID(),UUID.randomUUID(),2,null,"sha256:"+"a".repeat(64),java.time.Instant.now(),List.of(),allocation,"SYNTHETIC",null);
        when(results.results(task)).thenReturn(new TaskResultsResponse(task,List.of(result)));
        mvc.perform(get("/api/v1/tasks/"+task+"/results").with(user("fixture"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].producerPodUid").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.items[0].remoteAllocationId").value(allocation.toString()))
            .andExpect(jsonPath("$.items[0].remoteSourceMode").value("SYNTHETIC"));
    }
}
