package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcContinuousRepository;
import io.edgeai.domain.repository.ContinuousRepository;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ContinuousConfiguration {
    @Bean io.edgeai.domain.repository.VirtualDeviceRepository continuousDevices(JdbcTemplate jdbc){return new io.edgeai.adapters.repository.JdbcVirtualDeviceRepository(jdbc);}
    @Bean ContinuousRepository continuousRepository(JdbcTemplate jdbc){return new JdbcContinuousRepository(jdbc);}
}
