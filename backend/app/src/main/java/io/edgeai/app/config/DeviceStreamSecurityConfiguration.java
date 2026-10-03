package io.edgeai.app.config;
import io.edgeai.app.service.DeviceStreamTokenService;
import io.edgeai.domain.repository.DeviceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@ConditionalOnProperty(name={"edgeai.stream.enabled","edgeai.stream.bindings-enabled"},havingValue="true")
class DeviceStreamSecurityConfiguration {
    @Bean @Order(0) SecurityFilterChain deviceStreamSecurity(HttpSecurity http,DeviceRepository devices,DeviceStreamTokenService tokens)throws Exception {
        return http.securityMatcher("/internal/v1/devices/**").csrf(c->c.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().hasRole("DEVICE_STREAM"))
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->r.setStatus(401)).accessDeniedHandler((q,r,x)->r.setStatus(403)))
            .addFilterBefore(new DeviceStreamAuthenticationFilter(devices,tokens),AnonymousAuthenticationFilter.class).build();
    }
}
