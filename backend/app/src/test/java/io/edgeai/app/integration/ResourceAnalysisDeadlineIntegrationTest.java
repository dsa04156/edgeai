package io.edgeai.app.integration;

import io.edgeai.app.service.ProfileService;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.resourceprofile.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * One shared deadline bounds the whole analysis: slow Prometheus calls and a slow KRR run are cut at the budgets
 * (here 1 s observation / 3 s total) instead of adding up past the console proxy timeout.
 */
@SpringBootTest(properties={"edgeai.resource-profiling.enabled=true","edgeai.resource-profiling.namespaces=edgeai",
    "edgeai.resource-profiling.observation-budget-seconds=1","edgeai.resource-profiling.total-budget-seconds=3"})
@AutoConfigureMockMvc
@Import(ResourceAnalysisDeadlineIntegrationTest.Sources.class)
class ResourceAnalysisDeadlineIntegrationTest {
    static final AtomicInteger interrupted=new AtomicInteger();
    static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch(InterruptedException e){interrupted.incrementAndGet();Thread.currentThread().interrupt();throw new IllegalStateException("cancelled");}
    }
    static final class SlowMetrics implements WorkloadMetricsSource {
        public List<WorkloadCandidate> workloads(String namespace,Instant start,Instant end){return List.of();}
        public List<ObservedPod> pods(WorkloadTarget target,Instant start,Instant end){return List.of();}
        public WorkloadMeasurement measure(WorkloadTarget target,Instant start,Instant end){sleep(700);
            var usage=new UsageSummary(ObservationStatus.AVAILABLE,"millicores",1.0,2.0,3.0,4.0,20,start,end,null);
            return new WorkloadMeasurement(start,end,15,List.of(),usage,new UsageSummary(ObservationStatus.AVAILABLE,"bytes",1.0,2.0,3.0,4.0,20,start,end,null),"cadvisor",List.of());}
        public DeviceObservation device(DeviceRef device,String namespace,List<ObservedPod> pods,Instant start,Instant end){sleep(5000);throw new AssertionError("must be cut");}
    }
    static final class SlowKrr implements RecommendationSource {
        public KrrRecommendation recommend(WorkloadTarget target,int historyHours){sleep(10000);throw new AssertionError("must be cut");}
    }
    @TestConfiguration static class Sources {
        @Bean SlowMetrics slowMetrics(){return new SlowMetrics();}
        @Bean SlowKrr slowKrr(){return new SlowKrr();}
    }
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final JsonMapper json=new JsonMapper();

    @Test @SuppressWarnings("unchecked") void slowObservationsAndKrrAreCutAtTheSharedDeadlineAndTheResultStaysVisible() throws Exception {
        var spec=(Map<String,Object>)json.readValue(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")),Map.class);
        var service=profiles.publish(ProfileIdentity.Kind.SERVICE,json.writeValueAsString(Map.of("key","rp-deadline-"+UUID.randomUUID().toString().substring(0,8),"version","1.0.0","spec",spec))).version();
        Instant end=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var body=Map.of("serviceProfileVersionId",service.id().toString(),
            "target",Map.of("namespace","edgeai","kind","Deployment","name","detector","container","model"),
            "window",Map.of("start",end.minus(Duration.ofHours(1)).toString(),"end",end.toString()),
            "devices",List.of(Map.of("node","gpu-node-1","kind","GPU","device","pci:0000:02:00.0"),Map.of("node","gpu-node-1","kind","GPU","device","pci:0000:03:00.0")),
            "recommendation",Map.of("historyHours",24));
        long started=System.nanoTime();
        String response=mvc.perform(post("/api/v1/resource-profiles/analyses").with(user("test")).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)))
            .andExpect(status().is(201)).andReturn().getResponse().getContentAsString();
        Duration elapsed=Duration.ofNanos(System.nanoTime()-started);
        JsonNode analysis=json.readTree(response);
        // 0.7 s workload + devices cut at the 1 s observation budget; KRR cut at 3 s total minus the 0.3 s save reserve.
        // Not 0.7+5+5+10 s: the response and the saved row both land inside the total budget.
        assertThat(elapsed).isBetween(Duration.ofMillis(2600),Duration.ofMillis(3000));
        assertThat(analysis.path("measurement").path("cpu").path("status").asText()).isEqualTo("AVAILABLE");
        for(var device:analysis.path("devices")) {
            assertThat(device.path("status").asText()).isEqualTo("UNAVAILABLE");
            assertThat(device.path("reason").asText()).contains("시간 한도");
        }
        assertThat(analysis.path("recommendation").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(analysis.path("recommendation").path("reason").asText()).contains("시간 한도");
        assertThat(analysis.path("recommendation").path("cpu").path("requestMillicores").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.resource_profile_analysis WHERE id=?",Integer.class,UUID.fromString(analysis.path("id").asText()))).isEqualTo(1);
        Thread.sleep(200);
        assertThat(interrupted.get()).as("cut calls are cancelled, not left running").isGreaterThanOrEqualTo(2);
    }
}
