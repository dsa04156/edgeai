package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcProfileRepository;
import io.edgeai.domain.repository.ProfileRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class ProfileConfiguration {
    @Bean ProfileRepository profileRepository(JdbcTemplate jdbc) { return new JdbcProfileRepository(jdbc); }
}
