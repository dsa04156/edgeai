package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcVirtualDeviceRepository;
import io.edgeai.adapters.repository.JdbcVDRuntimeRepository;
import io.edgeai.domain.repository.VirtualDeviceRepository;
import io.edgeai.domain.repository.VDRuntimeRepository;
import io.edgeai.domain.repository.VDPollRepository;
import io.edgeai.adapters.repository.JdbcVDPollRepository;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class VirtualDeviceConfiguration {
    @Bean VirtualDeviceRepository virtualDeviceRepository(JdbcTemplate jdbc) { return new JdbcVirtualDeviceRepository(jdbc); }
    @Bean VDRuntimeRepository vdRuntimeRepository(JdbcTemplate jdbc) { return new JdbcVDRuntimeRepository(jdbc); }
    @Bean VDPollRepository vdPollRepository(JdbcTemplate jdbc) { return new JdbcVDPollRepository(jdbc); }
}
