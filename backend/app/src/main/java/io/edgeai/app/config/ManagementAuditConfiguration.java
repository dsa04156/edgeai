package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcManagementAuditRepository;
import io.edgeai.domain.repository.ManagementAuditRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ManagementAuditConfiguration {
    @Bean ManagementAuditRepository managementAuditRepository(JdbcTemplate jdbc) { return new JdbcManagementAuditRepository(jdbc); }
}
