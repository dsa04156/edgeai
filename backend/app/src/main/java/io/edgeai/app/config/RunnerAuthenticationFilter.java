package io.edgeai.app.config;

import io.edgeai.app.service.RunnerTokenService;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.vd.*;
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
import org.springframework.beans.factory.ObjectProvider;

final class RunnerAuthenticationFilter extends OncePerRequestFilter {
    private static final int LIMIT=262144;
    private static final Pattern PATH=Pattern.compile("/internal/v1/attempts/([a-fA-F0-9-]{36})/(claim|uploads|commit|fail|telemetry|streams(?:/heartbeat|/execution|/complete|/checkpoints/(?:uploads|commit|latest|handover))?)");
    private final RuntimeRepository runtimes;
    private final RunnerTokenService tokens;
    private final RuntimeGateway gateway;
    private final VDTaskRepository allocations;
    private final VDRuntimeRepository supervisors;
    private final ObjectProvider<VDGateway> vdGateway;
    RunnerAuthenticationFilter(RuntimeRepository runtimes,RunnerTokenService tokens,RuntimeGateway gateway,VDTaskRepository allocations,VDRuntimeRepository supervisors,ObjectProvider<VDGateway> vdGateway) {
        this.runtimes=runtimes;this.tokens=tokens;this.gateway=gateway;this.allocations=allocations;this.supervisors=supervisors;this.vdGateway=vdGateway;
    }
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
            if(runtime.vd()) {
                var allocation=allocations.byRuntime(runtime.id()).orElse(null);var boundary=vdGateway.getIfAvailable();
                if(allocation==null || !allocation.open() || boundary==null){response.setStatus(401);return;}
                var supervisor=supervisors.runtime(allocation.vdRuntimeId()).orElseThrow();
                if(supervisor.terminal() || supervisor.desiredState().equals("STOPPED") || !runtime.namespace().equals(supervisor.namespace()) ||
                    !runtime.vdId().equals(supervisor.vdId()) || !Objects.equals(allocation.sessionId(),supervisor.sessionId()) || allocation.generation()!=supervisor.generation()){
                    response.setStatus(409);return;
                }
                var pod=boundary.authenticatePod(supervisor,request.getHeader("X-EdgeAI-Pod-Token"));
                if(!allocation.podUid().equals(pod.podUid()) || !Objects.equals(supervisor.nodeUid(),pod.nodeUid()) || !Objects.equals(supervisor.nodeName(),pod.nodeName())){
                    response.setStatus(409);return;
                }
                principal=new RunnerPrincipal(attempt,runtime.epoch(),null,new VDTaskProducer(supervisor.id(),supervisor.generation(),supervisor.sessionId(),pod.podUid(),pod.nodeUid(),pod.nodeName()));
            } else {
                if(runtime.remote()){response.setStatus(401);return;}
                principal=new RunnerPrincipal(attempt,runtime.epoch(),gateway.authenticatePod(runtime,request.getHeader("X-EdgeAI-Pod-Token")));
            }
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
