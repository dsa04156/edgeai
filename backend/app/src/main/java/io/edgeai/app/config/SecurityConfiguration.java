package io.edgeai.app.config;

import jakarta.servlet.http.HttpServletResponse;
import io.edgeai.app.support.SuccessfulPasswordMatchCache;
import io.edgeai.app.service.ManagementAuditService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
public class SecurityConfiguration {
    @Bean @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService users,
            @Value("${edgeai.recovery.inspect-only:false}") boolean inspection,
            ObjectProvider<ManagementAuditService> audits, ObjectProvider<MeterRegistry> metrics) throws Exception {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(new SuccessfulPasswordMatchCache(PasswordEncoderFactories.createDelegatingPasswordEncoder()));
        if (users instanceof UserDetailsPasswordService passwordService) provider.setUserDetailsPasswordService(passwordService);
        // MVC slices may omit persistence; the full application supplies the required service/repository.
        var audit=audits.getIfAvailable();
        if (!inspection && audit!=null) http.addFilterBefore(new ManagementAuditFilter(audit,metrics.getIfAvailable()),CsrfFilter.class);
        return http
            .authenticationProvider(provider)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/internal/**").denyAll()
                .requestMatchers(request -> inspection && !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())).denyAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            // Keep CSRF denial as 403; sendError would redispatch through authenticated /error.
            .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, denied) ->
                response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
            .build();
    }
}
