package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.Creation;
import io.edgeai.domain.workflow.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIntegrationTest {
    @Autowired WorkflowService workflows;
    @Autowired ExecutionService executions;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    private final JsonMapper json=new JsonMapper();
    private String encode(Object value) { return json.writeValueAsString(value); }
    private Workflow workflow() { return workflows.create(encode(Map.of("key","workflow-"+UUID.randomUUID(),"displayName","시험 워크플로"))).value(); }
    private UUID profile() { return profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key","service-"+UUID.randomUUID(),"version","1.0.0","spec",Map.of("type","synthetic-test")))).version().id(); }
    private Map<String,Object> node(String key,UUID profile) { return Map.of("key",key,"serviceProfileVersionId",profile.toString(),"parameters",Map.of("serial",9007199254740993L)); }
    private Map<String,Object> edge(String from,String to,String mode) { return Map.of("fromTask",from,"toTask",to,"fromPort","output","toPort","input","mode",mode); }
    private String dag(UUID profile,String version) { return encode(Map.of("version",version,"tasks",List.of(node("root",profile),node("child",profile),node("independent",profile)),"dependencies",List.of(edge("root","child","BATCH")))); }
    private WorkflowVersion version() { return workflows.publish(workflow().id(),dag(profile(),"1.0.0")).value(); }
    private String runInput(UUID version) { return encode(Map.of("workflowVersionId",version.toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of("serial",9007199254740993L))); }
    private String perform(MockHttpServletRequestBuilder request,int statusCode) throws Exception { return mvc.perform(request.with(user("test")).with(csrf())).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString(); }
    private Task named(WorkflowRun run,String key) { return executions.detail(run.id()).tasks().stream().filter(t->t.key().equals(key)).findFirst().orElseThrow(); }

    @Test void httpWorkflowPublicationRunReplayCancelAndIndependentBranch() throws Exception {
        String body=encode(Map.of("key","http-workflow-"+UUID.randomUUID(),"displayName","실행 시험"));
        var workflow=json.readTree(perform(post("/api/v1/workflows").contentType("application/json").content(body),201));
        String path="/api/v1/workflows/"+workflow.path("id").asText();
        perform(post("/api/v1/workflows").contentType("application/json").content(body),200);
        perform(post("/api/v1/workflows").contentType("application/json").content(body.replace("실행 시험","다른 이름")),409);
        perform(get("/api/v1/workflows?limit=1"),200);
        String definition=dag(profile(),"1.0.0");
        var version=json.readTree(perform(post(path+"/versions").contentType("application/json").content(definition),201));
        perform(post(path+"/versions").contentType("application/json").content(definition),200);
        perform(post(path+"/versions").contentType("application/json").content(definition.replace("9007199254740993","2")),409);
        perform(post(path+"/versions").contentType("application/json").content(definition.replace("1.0.0","1.1.0")),201);
        String detail=perform(get(path+"?version=1.0.0"),200);
        assertThat(detail).contains("9007199254740993");
        assertThat(json.readTree(detail).path("versions").size()).isEqualTo(1);
        perform(get(path+"?version=2.0.0"),404);
        String runBody=runInput(UUID.fromString(version.path("id").asText())),key=UUID.randomUUID().toString();
        perform(post("/api/v1/workflow-runs").contentType("application/json").content(runBody),400);
        var created=json.readTree(perform(post("/api/v1/workflow-runs").header("Idempotency-Key",key).contentType("application/json").content(runBody),201));
        UUID runId=UUID.fromString(created.path("id").asText());var run=executions.detail(runId).run();
        assertThat(created.path("state").asText()).isEqualTo("PENDING");
        perform(post("/api/v1/workflow-runs").header("Idempotency-Key",key).contentType("application/json").content(runBody),200);
        perform(post("/api/v1/workflow-runs").header("Idempotency-Key",key).contentType("application/json").content(runBody.replace("9007199254740993","1")),409);
        assertThat(executions.taskDetail(named(run,"root").id()).attempts()).hasSize(1).allMatch(a->a.state().equals("QUEUED"));
        assertThat(executions.taskDetail(named(run,"child").id()).attempts()).isEmpty();
        perform(get("/api/v1/tasks/"+named(run,"root").id()),200);
        perform(post("/api/v1/tasks/"+named(run,"root").id()+"/cancel").contentType("application/json").content("{}"),200);
        assertThat(named(run,"root").state()).isEqualTo("CANCELLED");assertThat(named(run,"child").state()).isEqualTo("SKIPPED");
        assertThat(named(run,"independent").state()).isEqualTo("READY");assertThat(executions.detail(runId).run().state()).isEqualTo("PENDING");
        perform(get("/api/v1/workflow-runs?limit=1"),200);perform(get("/api/v1/workflow-runs/"+runId),200);
        perform(post("/api/v1/workflow-runs/"+runId+"/cancel").contentType("application/json").content("{}"),200);
        perform(post("/api/v1/workflow-runs/"+runId+"/cancel").contentType("application/json").content("{}"),200);
        var replay=json.readTree(perform(post("/api/v1/workflow-runs").header("Idempotency-Key",key).contentType("application/json").content(runBody),200));
        assertThat(replay.path("id").asText()).isEqualTo(runId.toString());assertThat(replay.path("state").asText()).isEqualTo("CANCELLED");
    }
    @Test void concurrentPublicationAndRunCreationAreAtomicAndExactlyOnce() throws Exception {
        var workflow=workflow();String dag=dag(profile(),"1.0.0");
        var publications=parallel(i->workflows.publish(workflow.id(),dag));
        assertThat(publications.stream().filter(Creation::created).count()).isEqualTo(1);
        assertThat(publications.stream().map(r->r.value().id()).distinct().count()).isEqualTo(1);
        UUID version=publications.getFirst().value().id();String key=UUID.randomUUID().toString();String body=runInput(version);
        var runs=parallel(i->executions.create(key,body));
        assertThat(runs.stream().filter(Creation::created).count()).isEqualTo(1);
        UUID runId=runs.getFirst().value().id();assertThat(runs).allMatch(r->r.value().id().equals(runId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.task WHERE run_id=?",Integer.class,runId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.task_attempt a JOIN edgeai.task t ON a.task_id=t.id WHERE t.run_id=?",Integer.class,runId)).isEqualTo(2);
    }
    @Test void conflictingConcurrentInputsHaveOneWinnerAndCancellationLeavesNoActiveAttempts() throws Exception {
        var version=version();String key=UUID.randomUUID().toString(),body=runInput(version.id());
        var results=parallel(i->{
            try { var result=executions.create(key,i%2==0?body:body.replace("9007199254740993","1"));return result.created()?"created":"replay"; }
            catch(ControlPlaneException e) { assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT");return "conflict"; }
        });
        assertThat(results.stream().filter("created"::equals).count()).isEqualTo(1);
        assertThat(results.stream().filter("conflict"::equals).count()).isEqualTo(4);
        UUID id=jdbc.queryForObject("SELECT id FROM edgeai.workflow_run WHERE idempotency_key=?",UUID.class,UUID.fromString(key));
        var run=executions.detail(id).run();UUID root=named(run,"root").id();
        parallel(i->{if(i%2==0)executions.cancelRun(id,"{}");else executions.cancelTask(root,"{}");return true;});
        assertThat(executions.detail(id).run().state()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id WHERE t.run_id=? AND a.state IN ('QUEUED','RUNNING','CANCELLING')",Integer.class,id)).isZero();
    }
    @Test void rejectsMalformedDagWrongProfileAndUnsupportedStreamWithoutCreatingRuns() throws Exception {
        var workflow=workflow();UUID profile=profile();String path="/api/v1/workflows/"+workflow.id()+"/versions";
        String valid=dag(profile,"1.0.0");
        for(String bad:List.of("{}",valid.replace("\"toTask\":\"child\"","\"toTask\":\"root\""),valid.replace("\"toTask\":\"child\"","\"toTask\":\"missing\"")))
            perform(post(path).contentType("application/json").content(bad),400);
        var wrong=profiles.publish(ProfileIdentity.Kind.DEVICE,encode(Map.of("key","wrong-"+UUID.randomUUID(),"version","1.0.0","spec",Map.of("x",1)))).version();
        perform(post(path).contentType("application/json").content(valid.replace(profile.toString(),wrong.id().toString())),400);
        perform(post(path).contentType("application/json").content(valid.replace(profile.toString(),UUID.randomUUID().toString())),404);
        assertThat(workflows.detail(workflow.id(),null,20,0).versions()).isEmpty();
        var stream=workflows.publish(workflow.id(),valid.replace("BATCH","STREAM")).value();
        String key=UUID.randomUUID().toString();
        perform(post("/api/v1/workflow-runs").header("Idempotency-Key",key).contentType("application/json").content(runInput(stream.id())),501);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_run WHERE idempotency_key=?",Integer.class,UUID.fromString(key))).isZero();
        perform(get("/api/v1/workflows?limit=101"),400);perform(get("/api/v1/workflow-runs?offset=-1"),400);
        perform(get("/api/v1/tasks/"+UUID.randomUUID()),404);perform(get("/api/v1/workflow-runs/"+UUID.randomUUID()),404);
        perform(post(path).contentType("application/json").content("{\"data\":\""+"x".repeat(65536)+"\"}"),413);
    }
    @Test void publishedDagChildrenCannotChangeOrGainNewRowsAndActiveAttemptIsUnique() {
        var version=version();var run=executions.create(UUID.randomUUID().toString(),runInput(version.id())).value();var root=named(run,"root");
        for(String sql:List.of("UPDATE edgeai.workflow_version SET digest=digest WHERE id=?","DELETE FROM edgeai.workflow_version WHERE id=?","UPDATE edgeai.task_definition SET parameters='{}' WHERE workflow_version_id=?","DELETE FROM edgeai.task_dependency WHERE workflow_version_id=?"))
            assertThatThrownBy(()->jdbc.update(sql,version.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.task_definition(id,workflow_version_id,task_key,service_profile_version_id,parameters) VALUES (?,?,?,?, '{}'::jsonb)",UUID.randomUUID(),version.id(),"late",profile()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.task_attempt(id,task_id,number,epoch,state,created_at,updated_at) VALUES (?,?,2,2,'QUEUED',now(),now())",UUID.randomUUID(),root.id()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var other=version();var otherRun=executions.create(UUID.randomUUID().toString(),runInput(other.id())).value();
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.task(id,run_id,workflow_version_id,definition_id,state,created_at,updated_at) VALUES (?,?,?,?,'READY',now(),now())",UUID.randomUUID(),run.id(),version.id(),named(otherRun,"root").definitionId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var tx=new TransactionTemplate(transactions);
        for(String table:List.of("workflow_version","task_definition","task_dependency"))
            assertThatThrownBy(()->tx.execute(status->{status.setRollbackOnly();jdbc.execute("TRUNCATE edgeai."+table+" CASCADE");return null;}))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("immutable");
    }
    @Test void nodePolicyAndDagReorderingPreserveIdentityAndCompletedTasksCannotBeCancelled() {
        var workflow=workflow();UUID profile=profile();String input=dag(profile,"1.0.0");
        var version=workflows.publish(workflow.id(),input).value();
        var reversed=encode(Map.of("version","1.0.0","tasks",List.of(node("independent",profile),node("child",profile),node("root",profile)),"dependencies",List.of(edge("root","child","BATCH"))));
        assertThat(workflows.publish(workflow.id(),reversed).value().id()).isEqualTo(version.id());
        UUID node=UUID.randomUUID();jdbc.update("INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES (?,'workflow-fixture','amd64','linux','NOT_READY','1','1Gi','{\"source\":\"test-fixture\"}',now())",node);
        String body=encode(Map.of("workflowVersionId",version.id(),"execution",Map.of("mode","NODE","nodeId",node),"parameters",Map.of()));
        var run=executions.create(UUID.randomUUID().toString(),body).value();assertThat(run.nodeId()).isEqualTo(node);
        var root=named(run,"root");jdbc.update("UPDATE edgeai.task SET state='SUCCEEDED' WHERE id=?",root.id());
        assertThatThrownBy(()->executions.cancelTask(root.id(),"{}")).isInstanceOf(ControlPlaneException.class);
        assertThat(executions.taskDetail(root.id()).task().state()).isEqualTo("SUCCEEDED");
        // State fixture only: this does not claim that a Kubernetes runtime executed.
    }
    private <T> List<T> parallel(IntFunction<T> call) throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<8;i++){int index=i;futures.add(executor.submit(()->{start.await();return call.apply(index);}));}
            start.countDown();var result=new ArrayList<T>();for(var f:futures)result.add(f.get(20,TimeUnit.SECONDS));return result;
        }
    }
}
