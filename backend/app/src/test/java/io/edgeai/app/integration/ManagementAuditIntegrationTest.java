package io.edgeai.app.integration;

import io.edgeai.app.service.ManagementAuditService;
import io.edgeai.domain.audit.ManagementAudit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ManagementAuditIntegrationTest {
    @Autowired ManagementAuditService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private ManagementAudit.Request request() { return new ManagementAudit.Request(UUID.randomUUID(),"POST","createWorkflow","/api/v1/workflows",null,null); }
    private final ManagementAudit.Actor actor=new ManagementAudit.Actor("LOCAL_BASIC","audit-test","NAME");
    @Test void admittedRequestSurvivesOuterRollbackAndMissingOutcomeRemainsExplicit() {
        var request=request();
        new TransactionTemplate(transactions).executeWithoutResult(status->{service.begin(request);status.setRollbackOnly();});
        assertThat(service.find(request.id()).orElseThrow().state()).isEqualTo("OUTCOME_UNKNOWN");
        service.finish(request.id(),202,"HTTP_COMPLETED",actor);
        var stored=service.find(request.id()).orElseThrow();
        assertThat(stored.state()).isEqualTo("OUTCOME_RECORDED");assertThat(stored.outcome().httpStatus()).isEqualTo(202);
        assertThat(stored.outcome().actor()).isEqualTo(actor);
    }
    @Test void requestsAndResultsRejectUpdateDeleteAndTruncate() {
        var request=request();service.begin(request);service.finish(request.id(),403,"HTTP_COMPLETED",actor);
        for (String table:java.util.List.of("management_audit_request","management_audit_outcome")) {
            String key=table.endsWith("request")?"id":"request_id";
            for (String command:java.util.List.of("UPDATE edgeai."+table+" SET "+key+"='"+request.id()+"' WHERE "+key+"='"+request.id()+"'",
                    "DELETE FROM edgeai."+table+" WHERE "+key+"='"+request.id()+"'", "TRUNCATE edgeai."+table+" CASCADE"))
                assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(status->{status.setRollbackOnly();jdbc.execute(command);}))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(service.find(request.id()).orElseThrow().outcome().httpStatus()).isEqualTo(403);
    }
    @Test void outcomeNeedsExistingRequestAndCannotReplaceAnEarlierResult() {
        assertThatThrownBy(()->service.finish(UUID.randomUUID(),200,"HTTP_COMPLETED",actor)).isInstanceOf(DataIntegrityViolationException.class);
        var request=request();service.begin(request);service.finish(request.id(),409,"HTTP_COMPLETED",actor);
        assertThatThrownBy(()->service.finish(request.id(),200,"HTTP_COMPLETED",actor)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.find(request.id()).orElseThrow().outcome().httpStatus()).isEqualTo(409);
    }
}
