package io.edgeai.app.config;

import io.edgeai.app.service.ManagementAuditService;
import io.edgeai.app.support.ManagementAuditRoutes;
import io.edgeai.domain.audit.ManagementAudit;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed only inside the management chain, before CSRF and after SecurityContext loading. */
public final class ManagementAuditFilter extends OncePerRequestFilter {
    public static final String HEADER="X-EdgeAI-Audit-Id";
    private static final Logger LOG=LoggerFactory.getLogger(ManagementAuditFilter.class);
    private final ManagementAuditService service;
    private final MeterRegistry metrics;
    public ManagementAuditFilter(ManagementAuditService service,MeterRegistry metrics) { this.service=service;this.metrics=metrics; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        var audit=ManagementAuditRoutes.request(request.getMethod(),request.getRequestURI().substring(request.getContextPath().length()));
        boolean mutation=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        if (mutation) {
            try { service.begin(audit); }
            catch (RuntimeException failure) {
                failed("admission",audit);
                response.setStatus(503);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"code\":\"AUDIT_STORE_UNAVAILABLE\",\"message\":\"감사 접수 기록을 저장하지 못해 변경을 실행하지 않았습니다.\"}");
                return;
            }
            response.setHeader(HEADER,audit.id().toString());
        }
        boolean handlerFailed=false;
        try { chain.doFilter(request,response); }
        catch (IOException|ServletException|RuntimeException|Error failure) { handlerFailed=true;throw failure; }
        finally {
            if (mutation) {
                try {
                    if (request.isAsyncStarted()) failed("async",audit);
                    else service.finish(audit.id(),handlerFailed?500:response.getStatus(),handlerFailed?"HANDLER_FAILED":"HTTP_COMPLETED",actor());
                }
                catch (RuntimeException failure) { failed("completion",audit); }
            } else if (response.getStatus()==401 || response.getStatus()==403) {
                try {
                    service.deniedRead(audit,response.getStatus(),actor());
                    if (!response.isCommitted()) response.setHeader(HEADER,audit.id().toString());
                } catch (RuntimeException failure) { failed("denial",audit); }
            }
        }
    }
    private void failed(String phase,ManagementAudit.Request request) {
        if (metrics!=null) metrics.counter("edgeai.audit.storage.failures","phase",phase).increment();
        LOG.error("Management audit storage failure: phase={} auditId={}",phase,request.id());
    }
    public static ManagementAudit.Actor actor() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)
            return new ManagementAudit.Actor("UNAUTHENTICATED",null,"NONE");
        String name=authentication.getName();
        if (name!=null && !name.isEmpty() && name.length()<=256 && name.codePoints().noneMatch(Character::isISOControl))
            return new ManagementAudit.Actor("LOCAL_BASIC",name,"NAME");
        try {
            var hash=MessageDigest.getInstance("SHA-256").digest((name==null?"":name).getBytes(StandardCharsets.UTF_8));
            return new ManagementAudit.Actor("LOCAL_BASIC",HexFormat.of().formatHex(hash),"SHA256");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
