package io.edgeai.app.config;

import io.edgeai.app.service.VDTokenService;
import io.edgeai.domain.repository.VDRuntimeRepository;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import io.edgeai.domain.vd.VDGateway;
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

final class VDAuthenticationFilter extends OncePerRequestFilter {
    private static final int LIMIT=262144;
    private static final Pattern PATH=Pattern.compile("/internal/v1/vd-runtimes/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/poll");
    private final VDRuntimeRepository runtimes;
    private final VDTokenService tokens;
    private final VDGateway gateway;
    VDAuthenticationFilter(VDRuntimeRepository runtimes,VDTokenService tokens,VDGateway gateway){this.runtimes=runtimes;this.tokens=tokens;this.gateway=gateway;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");var path=PATH.matcher(request.getRequestURI());
        if(!request.getMethod().equals("POST") || !path.matches()){response.setStatus(401);return;}
        String header=request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ") || Collections.list(request.getHeaders("Authorization")).size()!=1 || Collections.list(request.getHeaders("X-EdgeAI-Pod-Token")).size()!=1){response.setStatus(401);return;}
        if(request.getContentLengthLong()>LIMIT){response.setStatus(413);return;}
        VDPrincipal principal;
        try {
            var r=runtimes.runtime(UUID.fromString(path.group(1))).orElse(null);
            if(r==null || !tokens.matches(r,header.substring(7))){response.setStatus(401);return;}
            if(r.terminal() || r.desiredState().equals("STOPPED")){response.setStatus(409);return;}
            if(r.podUid()==null){response.setStatus(503);return;}
            principal=new VDPrincipal(r.id(),r.vdId(),r.generation(),gateway.authenticatePod(r,request.getHeader("X-EdgeAI-Pod-Token")));
        }catch(RuntimeGatewayException e){response.setStatus(e.reason()==RuntimeGatewayException.Reason.AUTH_REJECTED?401:e.reason()==RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT?409:503);return;}
         catch(RuntimeException e){response.setStatus(503);return;}
        byte[] body=request.getInputStream().readNBytes(LIMIT+1);if(body.length>LIMIT){response.setStatus(413);return;}
        var wrapped=new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){
                public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
                public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous VD endpoint");}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        };
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_VD"))));
        try{chain.doFilter(wrapped,response);}finally{SecurityContextHolder.clearContext();}
    }
}
