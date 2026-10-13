package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcContinuousRepository;
import io.edgeai.domain.repository.ContinuousRepository;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ContinuousConfiguration {
    @Bean ContinuousRepository continuousRepository(JdbcTemplate jdbc){return new JdbcContinuousRepository(jdbc);}
}
