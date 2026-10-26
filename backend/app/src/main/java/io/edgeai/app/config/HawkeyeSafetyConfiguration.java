package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcHawkeyeSafetyRepository;
import io.edgeai.adapters.repository.JdbcHawkeyeVideoRepository;
import io.edgeai.adapters.scenario.HttpHawkeyeMotionObserver;
import io.edgeai.adapters.scenario.HttpHawkeyeRuntime;
import io.edgeai.domain.repository.HawkeyeSafetyRepository;
import io.edgeai.domain.repository.HawkeyeVideoRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties({HawkeyeSafetyProperties.class, HawkeyeVideoProperties.class})
public class HawkeyeSafetyConfiguration {
    @Bean HawkeyeSafetyRepository hawkeyeSafetyRepository(JdbcTemplate jdbc) { return new JdbcHawkeyeSafetyRepository(jdbc); }
    @Bean HawkeyeVideoRepository hawkeyeVideoRepository(JdbcTemplate jdbc) { return new JdbcHawkeyeVideoRepository(jdbc); }

    /** The adapter URL is server configuration; the browser never selects an upstream. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "edgeai.hawkeye-safety.enabled", havingValue = "true")
    HttpHawkeyeRuntime hawkeyeRuntime(HawkeyeSafetyProperties properties) {
        return new HttpHawkeyeRuntime(properties.url(), properties.timeout());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${edgeai.hawkeye-video.observer-url:}'.isEmpty()")
    HttpHawkeyeMotionObserver hawkeyeMotionObserver(HawkeyeVideoProperties properties) {
        return new HttpHawkeyeMotionObserver(properties.observerUrl());
    }
}
