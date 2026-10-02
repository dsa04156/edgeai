package io.edgeai.app.config;
import io.edgeai.app.service.VDTokenService;
import io.edgeai.domain.repository.VDRuntimeRepository;
import io.edgeai.domain.vd.VDGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@ConditionalOnProperty(name={"edgeai.runtime.enabled","edgeai.vd.enabled"},havingValue="true")
class VDSecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain vdSecurity(HttpSecurity http,VDRuntimeRepository runtimes,VDTokenService tokens,VDGateway gateway)throws Exception {
        return http.securityMatcher("/internal/v1/vd-runtimes/**").csrf(csrf->csrf.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().hasRole("VD"))
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->r.setStatus(401)).accessDeniedHandler((q,r,x)->r.setStatus(403)))
            .addFilterBefore(new VDAuthenticationFilter(runtimes,tokens,gateway),AnonymousAuthenticationFilter.class).build();
    }
}
