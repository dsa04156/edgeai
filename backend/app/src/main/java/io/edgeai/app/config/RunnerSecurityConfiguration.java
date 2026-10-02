package io.edgeai.app.config;
import io.edgeai.app.service.RunnerTokenService;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.RuntimeGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@ConditionalOnProperty(name="edgeai.runtime.enabled",havingValue="true")
class RunnerSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain runnerSecurity(HttpSecurity http,RuntimeRepository runtimes,RunnerTokenService tokens,RuntimeGateway gateway) throws Exception {
        return http.securityMatcher("/internal/v1/attempts/**").csrf(csrf->csrf.disable())
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth.anyRequest().hasRole("RUNNER"))
            .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->response.setStatus(401))
                .accessDeniedHandler((request,response,error)->response.setStatus(403)))
            .addFilterBefore(new RunnerAuthenticationFilter(runtimes,tokens,gateway),AnonymousAuthenticationFilter.class).build();
    }
}
