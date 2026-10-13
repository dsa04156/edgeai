package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcContinuousRepository;
import io.edgeai.adapters.repository.JdbcProcessingDefinitionRepository;
import io.edgeai.adapters.repository.JdbcProcessingHistoryQueue;
import io.edgeai.domain.repository.ContinuousRepository;
import io.edgeai.domain.repository.ProcessingDefinitionRepository;
import io.edgeai.domain.repository.ProcessingHistoryQueue;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ContinuousConfiguration {
    @Bean io.edgeai.domain.repository.VirtualDeviceRepository continuousDevices(JdbcTemplate jdbc){return new io.edgeai.adapters.repository.JdbcVirtualDeviceRepository(jdbc);}
    @Bean ContinuousRepository continuousRepository(JdbcTemplate jdbc){return new JdbcContinuousRepository(jdbc);}
    @Bean ProcessingDefinitionRepository processingDefinitionRepository(JdbcTemplate jdbc){return new JdbcProcessingDefinitionRepository(jdbc);}
    @Bean ProcessingHistoryQueue processingHistoryQueue(JdbcTemplate jdbc){return new JdbcProcessingHistoryQueue(jdbc);}
}
