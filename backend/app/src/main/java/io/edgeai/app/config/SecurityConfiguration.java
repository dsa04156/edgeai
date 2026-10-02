package io.edgeai.app.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/internal/**").denyAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            // Keep CSRF denial as 403; sendError would redispatch through authenticated /error.
            .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, denied) ->
                response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
            .build();
    }
}
