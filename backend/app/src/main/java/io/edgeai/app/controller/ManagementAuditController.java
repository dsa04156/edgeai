package io.edgeai.app.controller;
import io.edgeai.app.dto.ManagementAuditResponse;
import io.edgeai.app.dto.ResourcePageResponse;
import io.edgeai.app.service.ManagementAuditService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-requests")
public class ManagementAuditController {
    private final ManagementAuditService service;
    public ManagementAuditController(ManagementAuditService service) { this.service=service; }
    @GetMapping public ResourcePageResponse<ManagementAuditResponse> list(@RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int offset) {
        var values=service.list(limit,offset);
        return new ResourcePageResponse<>(values.stream().limit(limit).map(ManagementAuditResponse::from).toList(),values.size()>limit?offset+limit:null);
    }
    @GetMapping("/{auditId}") public ResponseEntity<ManagementAuditResponse> find(@PathVariable UUID auditId) {
        return service.find(auditId).map(value->ResponseEntity.ok(ManagementAuditResponse.from(value))).orElseGet(()->ResponseEntity.notFound().build());
    }
}
