package io.edgeai.app.config;

import io.edgeai.adapters.kubernetes.KubernetesProcessingEngineGateway;
import io.edgeai.domain.vd.ProcessingEngineGateway;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@ConditionalOnProperty(name="edgeai.continuous.enabled",havingValue="true")
class ProcessingSecurityConfiguration {
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="edgeai.kubernetes.url")
    KubernetesProcessingEngineGateway processingEngineGateway(@Value("${edgeai.kubernetes.url}") String url,@Value("${edgeai.kubernetes.token-file:}") String token,
        @Value("${edgeai.kubernetes.ca-file:}") String ca,@Value("${edgeai.continuous.namespace:ekuiper}") String ns,
        @Value("${edgeai.continuous.service-account:edgeai-processing}") String account,@Value("${edgeai.continuous.pod-name:my-ekuiper-0}") String pod) {
        return new KubernetesProcessingEngineGateway(url,token,ca,ns,account,pod);
    }
    @Bean @Order(0) SecurityFilterChain processingSecurity(HttpSecurity http,ObjectProvider<ProcessingEngineGateway> gateways,
        @Value("${edgeai.continuous.credential-file:}") String file)throws Exception {
        return http.securityMatcher("/internal/v1/processing/**").csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().hasRole("PROCESSING_ENGINE"))
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->r.setStatus(401)).accessDeniedHandler((q,r,x)->r.setStatus(403)))
            .addFilterBefore(new EngineFilter(gateways,file),AnonymousAuthenticationFilter.class).build();
    }
    private static final class EngineFilter extends OncePerRequestFilter {
        private static final int LIMIT=262144;
        private final ObjectProvider<ProcessingEngineGateway> gateways;private final String file;
        EngineFilter(ObjectProvider<ProcessingEngineGateway> gateways,String file){this.gateways=gateways;this.file=file;}
        protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain chain)throws ServletException,IOException {
            r.setHeader("Cache-Control","no-store");
            if(!q.getMethod().equals("POST") || !Set.of("/internal/v1/processing/poll","/internal/v1/processing/reset","/internal/v1/processing/results").contains(q.getRequestURI())){r.setStatus(401);return;}
            String bearer=q.getHeader("Authorization"),token=q.getHeader("X-EdgeAI-Pod-Token");
            if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()>1024 || Collections.list(q.getHeaders("Authorization")).size()!=1 || Collections.list(q.getHeaders("X-EdgeAI-Pod-Token")).size()!=1){r.setStatus(401);return;}
            if(q.getContentLengthLong()>LIMIT){r.setStatus(413);return;}
            ProcessingEngineGateway.Identity principal;
            try {
                if(file.isBlank() || Files.size(Path.of(file))>1024){r.setStatus(503);return;}
                String credential=Files.readString(Path.of(file)).trim();
                if(credential.length()<32 || !MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8),bearer.substring(7).getBytes(StandardCharsets.UTF_8))){r.setStatus(401);return;}
                var gateway=gateways.getIfAvailable();if(gateway==null){r.setStatus(503);return;}principal=gateway.authenticate(token);if(principal==null){r.setStatus(401);return;}
            }catch(RuntimeGatewayException e){r.setStatus(e.reason()==RuntimeGatewayException.Reason.AUTH_REJECTED?401:503);return;}
             catch(Exception e){r.setStatus(503);return;}
            byte[] body=q.getInputStream().readNBytes(LIMIT+1);if(body.length>LIMIT){r.setStatus(413);return;}
            var wrapped=new HttpServletRequestWrapper(q) {
                public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous engine endpoint");}};}
                public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
            };
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_PROCESSING_ENGINE"))));
            try{chain.doFilter(wrapped,r);}finally{SecurityContextHolder.clearContext();}
        }
    }
}
