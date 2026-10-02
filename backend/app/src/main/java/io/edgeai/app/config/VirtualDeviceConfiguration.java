package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcVirtualDeviceRepository;
import io.edgeai.domain.repository.VirtualDeviceRepository;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class VirtualDeviceConfiguration {
    @Bean VirtualDeviceRepository virtualDeviceRepository(JdbcTemplate jdbc) { return new JdbcVirtualDeviceRepository(jdbc); }
}
