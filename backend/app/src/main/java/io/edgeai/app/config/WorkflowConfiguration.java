package io.edgeai.app.config;
import io.edgeai.adapters.repository.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration
class WorkflowConfiguration {
    @Bean WorkflowRepository workflowRepository(JdbcTemplate jdbc) { return new JdbcWorkflowRepository(jdbc); }
    /** EDGEAI_RUNTIME_WORKFLOW_KEYS: '*' (default) or the only workflow keys whose Runs the runtime executes. */
    @Bean RuntimeScope runtimeScope(@Value("${edgeai.runtime.workflow-keys:*}") String keys) { return RuntimeScope.parse(keys); }
    @Bean ExecutionRepository executionRepository(JdbcTemplate jdbc,RuntimeScope scope) { return new JdbcExecutionRepository(jdbc,scope); }
    @Bean RuntimeRepository runtimeRepository(JdbcTemplate jdbc,RuntimeScope scope) { return new JdbcRuntimeRepository(jdbc,scope); }
    @Bean RemoteRepository remoteRepository(JdbcTemplate jdbc) { return new JdbcRemoteRepository(jdbc); }
    @Bean OffloadRepository offloadRepository(JdbcTemplate jdbc,RuntimeScope scope) { return new JdbcOffloadRepository(jdbc,scope); }
    @Bean TelemetryRepository telemetryRepository(JdbcTemplate jdbc) { return new JdbcTelemetryRepository(jdbc); }
    @Bean DataRouteRepository dataRouteRepository(JdbcTemplate jdbc) { return new JdbcDataRouteRepository(jdbc); }
    @Bean StreamCheckpointRepository streamCheckpointRepository(JdbcTemplate jdbc) { return new JdbcStreamCheckpointRepository(jdbc); }
    @Bean StreamExecutionRepository streamExecutionRepository(JdbcTemplate jdbc) { return new JdbcStreamExecutionRepository(jdbc); }
    @Bean StreamRunRepository streamRunRepository(JdbcTemplate jdbc) { return new JdbcStreamRunRepository(jdbc); }
    @Bean SchedulingRepository schedulingRepository(JdbcTemplate jdbc) { return new JdbcSchedulingRepository(jdbc); }
    @Bean ObservationFeedRepository observationFeedRepository(JdbcTemplate jdbc) { return new JdbcObservationFeedRepository(jdbc); }
}
