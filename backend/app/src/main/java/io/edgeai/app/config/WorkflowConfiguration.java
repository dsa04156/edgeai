package io.edgeai.app.config;
import io.edgeai.adapters.repository.*;
import io.edgeai.domain.repository.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration
class WorkflowConfiguration {
    @Bean WorkflowRepository workflowRepository(JdbcTemplate jdbc) { return new JdbcWorkflowRepository(jdbc); }
    @Bean ExecutionRepository executionRepository(JdbcTemplate jdbc) { return new JdbcExecutionRepository(jdbc); }
    @Bean RuntimeRepository runtimeRepository(JdbcTemplate jdbc) { return new JdbcRuntimeRepository(jdbc); }
    @Bean RemoteRepository remoteRepository(JdbcTemplate jdbc) { return new JdbcRemoteRepository(jdbc); }
    @Bean OffloadRepository offloadRepository(JdbcTemplate jdbc) { return new JdbcOffloadRepository(jdbc); }
    @Bean TelemetryRepository telemetryRepository(JdbcTemplate jdbc) { return new JdbcTelemetryRepository(jdbc); }
    @Bean DataRouteRepository dataRouteRepository(JdbcTemplate jdbc) { return new JdbcDataRouteRepository(jdbc); }
    @Bean StreamCheckpointRepository streamCheckpointRepository(JdbcTemplate jdbc) { return new JdbcStreamCheckpointRepository(jdbc); }
    @Bean StreamExecutionRepository streamExecutionRepository(JdbcTemplate jdbc) { return new JdbcStreamExecutionRepository(jdbc); }
    @Bean StreamRunRepository streamRunRepository(JdbcTemplate jdbc) { return new JdbcStreamRunRepository(jdbc); }
}
