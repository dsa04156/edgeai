package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VirtualDeviceRemovalIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProfileService profiles;
    @Autowired ProfileRepository profileRepository;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired ExecutionService executions;
    @Autowired ExecutionRepository executionRepository;
    @Autowired RuntimeRepository runtimes;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired NodeService nodes;
    @Autowired OffloadService offloads;
    @Autowired PlatformTransactionManager transactions;
    private final JsonMapper json=new JsonMapper();
    private String encode(Object value){return json.writeValueAsString(value);}
    private String key(){return "retirement-"+UUID.randomUUID();}
    private UUID service(){return profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key",key(),"version","1.0.0","spec",Map.of("type","synthetic-test")))).version().id();}
    private UUID version(UUID service){
        var workflow=workflows.create(encode(Map.of("key",key(),"displayName","Retirement regression"))).value();
        return workflows.publish(workflow.id(),encode(Map.of("version","1.0.0","tasks",List.of(Map.of("key","root","serviceProfileVersionId",service.toString(),"parameters",Map.of())),"dependencies",List.of()))).value().id();
    }
    @Test void retiredRegistryRoutesAndProfileWritesAreUnavailable() throws Exception {
        mvc.perform(get("/api/v1/virtual-devices")).andExpect(status().isOk()).andExpect(jsonPath("$.items[*].executionMode").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("CONTINUOUS_EKUIPER"))));
        mvc.perform(get("/api/v1/virtual-devices/continuous-catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/v1/virtual-devices").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/profiles/VD").with(csrf()).contentType("application/json").content(encode(Map.of("key",key(),"version","1.0.0","spec",Map.of("type","retired"))))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/platform")).andExpect(status().isOk()).andExpect(jsonPath("$.capabilities").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("virtual-devices"))));
    }
    @Test void rejectsRootAndTaskVDPoliciesWithoutCreatingRunsAndKeepsOrdinaryReplay() throws Exception {
        UUID version=version(service());
        var request=new LinkedHashMap<String,Object>(Map.of("workflowVersionId",version.toString(),"parameters",Map.of(),"execution",Map.of("mode","VD","vdId",UUID.randomUUID().toString())));
        String idempotency=UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/workflow-runs").with(csrf()).header("Idempotency-Key",idempotency).contentType("application/json").content(encode(request))).andExpect(status().isBadRequest());
        assertThat(executionRepository.byIdempotencyKey(UUID.fromString(idempotency))).isEmpty();
        request.put("execution",Map.of("mode","AUTO"));request.put("taskExecutions",Map.of("root",Map.of("mode","VD","vdId",UUID.randomUUID().toString())));
        mvc.perform(post("/api/v1/workflow-runs").with(csrf()).header("Idempotency-Key",idempotency).contentType("application/json").content(encode(request))).andExpect(status().isBadRequest());
        assertThat(executionRepository.byIdempotencyKey(UUID.fromString(idempotency))).isEmpty();
        request.remove("taskExecutions");var created=executions.create(idempotency,encode(request));
        assertThat(created.created()).isTrue();assertThat(executions.create(idempotency,encode(request)).value().id()).isEqualTo(created.value().id());
        assertThat(executions.detail(created.value().id()).tasks()).singleElement().satisfies(task->assertThat(task.initialMode()).isEqualTo("AUTO"));
    }
    @Test void preservedVDHistoryRemainsReadableAndIsNeverRedispatched() throws Exception {
        UUID service=service(),version=version(service),profile=UUID.randomUUID(),vd=UUID.randomUUID();
        jdbc.update("INSERT INTO edgeai.profile_version(id,kind,profile_key,version,spec,digest) VALUES (?,'VD',?,'1.0.0',?::jsonb,?)",profile,key(),encode(Map.of("apiVersion","edgeai.vd/v1","serviceProfileVersionId",service.toString())),"sha256:"+"a".repeat(64));
        jdbc.update("INSERT INTO edgeai.virtual_device(id,vd_key,display_name,profile_version_id,service_profile_version_id,placement_mode,creation_digest) VALUES (?,?,'Historical VD',?,?,'AUTO',?)",vd,key(),profile,service,"sha256:"+"a".repeat(64));
        assertThat(profileRepository.find(profile)).isEmpty();
        mvc.perform(get("/api/v1/virtual-devices/"+vd)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/virtual-devices/"+vd+"/provision").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isServiceUnavailable());
        var now=Instant.now();var run=new WorkflowRun(UUID.randomUUID(),version,UUID.randomUUID(),"sha256:"+"b".repeat(64),"VD",null,"{}",RetryPolicy.disabled(),null,"PENDING",now,now,null,vd);
        new TransactionTemplate(transactions).executeWithoutResult(status->{assertThat(executionRepository.create(run)).isTrue();executionRepository.initialize(run,workflowRepository.definitions(version),Set.of("root"),Map.of());});
        var before=executionRepository.tasks(run.id());
        assertThat(runtimes.readyAttempts(run.id(),100)).isEmpty();lifecycle.startRun(run.id(),"edgeai-runtimes");
        assertThat(executionRepository.tasks(run.id())).isEqualTo(before);
        mvc.perform(get("/api/v1/workflow-runs/"+run.id())).andExpect(status().isOk()).andExpect(jsonPath("$.run.mode").value("VD")).andExpect(jsonPath("$.run.vdId").value(vd.toString())).andExpect(jsonPath("$.tasks[0].initialVdId").value(vd.toString()));
        // A previous release could already have offloaded this VD task to a normal NODE.
        // Its pinned sensor inputs stay readable, but changing the current mode does not revive it.
        var task=before.getFirst();var old=executionRepository.attempts(task.id()).getFirst();
        UUID node=UUID.randomUUID(),next=UUID.randomUUID();
        nodes.recordSnapshot(List.of(new io.edgeai.domain.node.ExecutionNode(node,"retirement-"+node,"amd64","linux","READY","4","4Gi","{}",now)),now);
        jdbc.update("UPDATE edgeai.task_attempt SET state='OFFLOADED' WHERE id=?",old.id());
        jdbc.update("INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,mode,node_id,cause,created_at,updated_at) VALUES (?,?,2,2,'QUEUED','NODE',?,'OFFLOAD',?,?)",next,task.id(),node,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
        String pinned=encode(Map.of("apiVersion","edgeai.vd-input/v1","vdId",vd.toString(),"vdRevision",1,"capturedAt",now.toString(),"sources",Map.of(),"processing",Map.of("apiVersion","edgeai.processing/v1","pipelines",List.of(Map.of("key","historical")))));
        jdbc.update("INSERT INTO edgeai.vd_task_input(task_id,vd_id,document) VALUES (?,?,?::jsonb)",task.id(),vd,pinned);
        assertThat(executions.taskDetail(task.id()).vdInputsJson()).contains("edgeai.vd-input/v1");
        assertThat(runtimes.readyAttempts(run.id(),100)).isEmpty();
        assertThatThrownBy(()->lifecycle.plan(next,"edgeai-runtimes")).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        assertThat(runtimes.byAttempt(next)).isEmpty();
        assertThatThrownBy(()->executionRepository.startOffload(task.id(),node,List.of(),now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->executionRepository.startRetry(task.id(),now)).isInstanceOf(IllegalStateException.class);
        assertThat(lifecycle.retryTask(task.id())).isFalse();
        assertThatThrownBy(()->offloads.request(task.id(),UUID.randomUUID().toString(),encode(Map.of("sourceAttemptId",next.toString(),"targetNodeId",node.toString(),"drainTimeoutSeconds",30,"startTimeoutSeconds",30))))
            .isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);

    }
}
