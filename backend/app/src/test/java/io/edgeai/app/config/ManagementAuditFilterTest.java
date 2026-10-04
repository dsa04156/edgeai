package io.edgeai.app.config;

import io.edgeai.app.service.ManagementAuditService;
import io.edgeai.domain.audit.ManagementAudit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementAuditFilterTest {
    private final ManagementAuditService service=mock(ManagementAuditService.class);
    private final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    private final ManagementAuditFilter filter=new ManagementAuditFilter(service,metrics);
    @AfterEach void cleanup() { SecurityContextHolder.clearContext();metrics.close(); }
    private void user(String name) { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name,"PRIVATE-CREDENTIAL",java.util.List.of())); }
    @Test void admissionFailureNeverCallsHandlerOrExposesDiagnostics() throws Exception {
        doThrow(new IllegalStateException("PRIVATE-CANARY")).when(service).begin(any());
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/devices"),response,(q,r)->calls.incrementAndGet());
        assertThat(calls).hasValue(0);assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("AUDIT_STORE_UNAVAILABLE").doesNotContain("PRIVATE-CANARY");
        verify(service,never()).finish(any(),anyInt(),anyString(),any());
        assertThat(metrics.get("edgeai.audit.storage.failures").tag("phase","admission").counter().count()).isEqualTo(1);
    }
    @Test void completionFailurePreservesSuccessfulHandlerAndLeavesOnlyDurableIntent() throws Exception {
        doThrow(new IllegalStateException("PRIVATE-CANARY")).when(service).finish(any(),anyInt(),anyString(),any());
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();user("operator");
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/devices"),response,(q,r)->{calls.incrementAndGet();response.setStatus(201);});
        assertThat(calls).hasValue(1);assertThat(response.getStatus()).isEqualTo(201);
        assertThatCode(()->UUID.fromString(response.getHeader(ManagementAuditFilter.HEADER))).doesNotThrowAnyException();
        verify(service).begin(any());
        assertThat(metrics.get("edgeai.audit.storage.failures").tag("phase","completion").counter().count()).isEqualTo(1);
    }
    @Test void fixedRouteAndOnlyUuidTargetsAreCapturedWithServerGeneratedIdentity() throws Exception {
        var device=UUID.randomUUID();var node=UUID.randomUUID();var forged=UUID.randomUUID();
        var request=new MockHttpServletRequest("PUT","/api/v1/devices/"+device+"/attachments/"+node);
        request.addHeader(ManagementAuditFilter.HEADER,forged.toString());request.addHeader("Authorization","PRIVATE-CANARY");
        request.setQueryString("token=PRIVATE-CANARY");request.setContent("PRIVATE-CANARY".getBytes());
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(q,r)->{user("operator");response.setStatus(200);});
        var captor=ArgumentCaptor.forClass(ManagementAudit.Request.class);verify(service).begin(captor.capture());
        var audit=captor.getValue();assertThat(audit.id()).isNotEqualTo(forged);
        assertThat(audit.operation()).isEqualTo("attachDevice");assertThat(audit.targetId()).isEqualTo(device);assertThat(audit.relatedId()).isEqualTo(node);
        assertThat(audit.toString()).doesNotContain("PRIVATE-CANARY");
        verify(service).finish(audit.id(),200,"HTTP_COMPLETED",new ManagementAudit.Actor("LOCAL_BASIC","operator","NAME"));
    }
    @Test void exceptionsAreRethrownAndNeverRecordedAsSuccessfulHttpResponses() {
        var failure=new IllegalStateException("PRIVATE-CANARY");var request=new MockHttpServletRequest("POST","/api/v1/devices");
        assertThatThrownBy(()->filter.doFilter(request,new MockHttpServletResponse(),(q,r)->{throw failure;})).isSameAs(failure);
        verify(service).finish(any(),eq(500),eq("HANDLER_FAILED"),eq(new ManagementAudit.Actor("UNAUTHENTICATED",null,"NONE")));
    }
    @Test void successfulReadsAreNotStoredAndDeniedReadsNeverTrustCredentialHeaders() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/v1/devices");request.addHeader("Authorization","PRIVATE-CANARY");
        filter.doFilter(request,new MockHttpServletResponse(),(q,r)->{});verifyNoInteractions(service);
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(q,r)->response.setStatus(401));
        verify(service).deniedRead(any(),eq(401),eq(new ManagementAudit.Actor("UNAUTHENTICATED",null,"NONE")));
        verify(service,never()).begin(any());
    }
    @Test void internalHealthAndAsynchronousCompletionDoNotProduceFalseResults() throws Exception {
        for (String path:java.util.List.of("/actuator/health/readiness","/internal/v1/attempts/private/commit"))
            filter.doFilter(new MockHttpServletRequest("POST",path),new MockHttpServletResponse(),(q,r)->{});
        verifyNoInteractions(service);
        var request=new MockHttpServletRequest("POST","/api/v1/devices");request.setAsyncSupported(true);
        filter.doFilter(request,new MockHttpServletResponse(),(q,r)->request.startAsync());
        verify(service).begin(any());verify(service,never()).finish(any(),anyInt(),anyString(),any());
    }
    @Test void longOrControlCharacterSubjectsUseExplicitDigestInsteadOfUnsafeTruncation() {
        for (String name:java.util.List.of("x".repeat(257),"operator\nPRIVATE-CANARY")) {
            user(name);var actor=ManagementAuditFilter.actor();
            assertThat(actor.type()).isEqualTo("LOCAL_BASIC");assertThat(actor.subjectFormat()).isEqualTo("SHA256");
            assertThat(actor.subject()).matches("[a-f0-9]{64}").doesNotContain("PRIVATE-CANARY");
        }
    }
}
