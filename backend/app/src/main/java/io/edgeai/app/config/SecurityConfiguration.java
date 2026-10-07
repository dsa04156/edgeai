package io.edgeai.app.config;

import jakarta.servlet.http.HttpServletResponse;
import io.edgeai.app.service.ManagementAuditService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
public class SecurityConfiguration {
    @Bean @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${edgeai.recovery.inspect-only:false}") boolean inspection,
            ObjectProvider<ManagementAuditService> audits, ObjectProvider<MeterRegistry> metrics) throws Exception {
        // MVC slices may omit persistence; the full application supplies the required service/repository.
        var audit=audits.getIfAvailable();
        if (!inspection && audit!=null) http.addFilterBefore(new ManagementAuditFilter(audit,metrics.getIfAvailable()),CsrfFilter.class);
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/internal/**").denyAll()
                .requestMatchers(request -> inspection && !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())).denyAll()
                .anyRequest().permitAll())
            // Keep CSRF denial as 403 without an error redispatch.
            .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, denied) ->
                response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
            .build();
    }
}
