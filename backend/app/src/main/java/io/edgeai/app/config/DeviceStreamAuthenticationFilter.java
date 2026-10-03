package io.edgeai.app.config;

import io.edgeai.app.service.DeviceStreamTokenService;
import io.edgeai.domain.device.Device;
import io.edgeai.domain.repository.DeviceRepository;
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

final class DeviceStreamAuthenticationFilter extends OncePerRequestFilter {
    private static final int LIMIT=16384;
    private static final Pattern PATH=Pattern.compile("/internal/v1/devices/([a-fA-F0-9-]{36})/sessions/([a-fA-F0-9-]{36})/streams(?:/heartbeat)?");
    private final DeviceRepository devices;private final DeviceStreamTokenService tokens;
    DeviceStreamAuthenticationFilter(DeviceRepository devices,DeviceStreamTokenService tokens){this.devices=devices;this.tokens=tokens;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");var path=PATH.matcher(request.getRequestURI());
        if(!request.getMethod().equals("POST") || !path.matches()){response.setStatus(401);return;}
        String header=request.getHeader("Authorization");if(header==null || !header.startsWith("Bearer ")){response.setStatus(401);return;}
        if(request.getContentLengthLong()>LIMIT){response.setStatus(413);return;}
        DeviceStreamPrincipal principal;
        try{
            UUID deviceId=UUID.fromString(path.group(1)),sessionId=UUID.fromString(path.group(2));
            var device=devices.find(deviceId,false).orElse(null);var session=devices.activeSession(deviceId).orElse(null);
            if(device==null || device.state()!=Device.State.ACTIVE || session==null || !session.id().equals(sessionId)
                || session.epoch()!=device.sessionEpoch() || !tokens.matches(session,header.substring(7))){response.setStatus(401);return;}
            principal=new DeviceStreamPrincipal(deviceId,sessionId,session.epoch());
        }catch(IllegalArgumentException e){response.setStatus(401);return;}catch(RuntimeException e){response.setStatus(503);return;}
        byte[] bytes=request.getInputStream().readNBytes(LIMIT+1);if(bytes.length>LIMIT){response.setStatus(413);return;}
        var wrapped=new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(bytes);return new ServletInputStream(){
                public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
                public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous stream endpoint");}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        };
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_DEVICE_STREAM"))));
        try{chain.doFilter(wrapped,response);}finally{SecurityContextHolder.clearContext();}
    }
}
