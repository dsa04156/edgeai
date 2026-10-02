package io.edgeai.app.profile;

import io.edgeai.adapters.profile.JdbcProfileRepository;
import io.edgeai.domain.profile.ProfileRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class ProfileConfiguration {
    @Bean ProfileRepository profileRepository(JdbcTemplate jdbc) { return new JdbcProfileRepository(jdbc); }
}
