package io.edgeai.app.config;
import io.edgeai.adapters.repository.*;
import io.edgeai.domain.repository.*;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class DeviceConfiguration {
    @Bean Clock clock() { return Clock.tickMillis(java.time.ZoneOffset.UTC); }
    @Bean DeviceRepository deviceRepository(JdbcTemplate jdbc) { return new JdbcDeviceRepository(jdbc); }
    @Bean NodeRepository nodeRepository(JdbcTemplate jdbc) { return new JdbcNodeRepository(jdbc); }
}
