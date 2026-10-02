package io.edgeai.app.config;
import io.edgeai.adapters.repository.*;
import io.edgeai.domain.repository.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration
class WorkflowConfiguration {
    @Bean WorkflowRepository workflowRepository(JdbcTemplate jdbc) { return new JdbcWorkflowRepository(jdbc); }
    @Bean ExecutionRepository executionRepository(JdbcTemplate jdbc) { return new JdbcExecutionRepository(jdbc); }
}
