package io.edgeai.app.config;

import io.edgeai.app.service.RunnerTokenService;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class RunnerAuthenticationFilter extends OncePerRequestFilter {
    private static final int LIMIT=262144;
    private static final Pattern PATH=Pattern.compile("/internal/v1/attempts/([a-fA-F0-9-]{36})/(claim|uploads|commit|fail)");
    private final RuntimeRepository runtimes;
    private final RunnerTokenService tokens;
    private final RuntimeGateway gateway;
    RunnerAuthenticationFilter(RuntimeRepository runtimes,RunnerTokenService tokens,RuntimeGateway gateway) {this.runtimes=runtimes;this.tokens=tokens;this.gateway=gateway;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var path=PATH.matcher(request.getRequestURI());
        if(!request.getMethod().equals("POST") || !path.matches()){response.setStatus(401);return;}
        String header=request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ")){response.setStatus(401);return;}
        if(request.getContentLengthLong()>LIMIT){response.setStatus(413);return;}
        RunnerPrincipal principal;
        try {
            UUID attempt=UUID.fromString(path.group(1));var runtime=runtimes.byAttempt(attempt).orElse(null);
            if(runtime==null || !tokens.matches(runtime,header.substring(7))){response.setStatus(401);return;}
            principal=new RunnerPrincipal(attempt,runtime.epoch(),gateway.authenticatePod(runtime,request.getHeader("X-EdgeAI-Pod-Token")));
        } catch(RuntimeGatewayException error) {response.setStatus(error.reason()==RuntimeGatewayException.Reason.AUTH_REJECTED?401:error.reason()==RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT?409:503);return;}
          catch(IllegalArgumentException error) {response.setStatus(401);return;}
          catch(RuntimeException error) {response.setStatus(503);return;}
        byte[] body=request.getInputStream().readNBytes(LIMIT+1);
        if(body.length>LIMIT){response.setStatus(413);return;}
        var wrapped=new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read(){return input.read();}
                    public boolean isFinished(){return input.available()==0;}
                    public boolean isReady(){return true;}
                    public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous Runner endpoint");}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        };
        var authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_RUNNER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try{chain.doFilter(wrapped,response);}finally{SecurityContextHolder.clearContext();}
    }
}
