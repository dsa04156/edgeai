package io.edgeai.app.controller;
import io.edgeai.app.dto.*;
import io.edgeai.app.service.WorkflowService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {
    private final WorkflowService service;
    public WorkflowController(WorkflowService service) { this.service=service; }
    @PostMapping(consumes="application/json")
    public ResponseEntity<WorkflowResponse> create(@RequestBody String body) {
        var result=service.create(body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/workflows/"+result.value().id())).body(WorkflowResponse.from(result.value()));
    }
    @GetMapping
    public ResourcePageResponse<WorkflowResponse> list(@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var values=service.list(limit,offset);return new ResourcePageResponse<>(values.stream().limit(limit).map(WorkflowResponse::from).toList(),values.size()>limit?offset+limit:null);
    }
    @GetMapping("/{workflowId}")
    public WorkflowDetailResponse detail(@PathVariable UUID workflowId,@RequestParam(required=false) String version,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var value=service.detail(workflowId,version,limit,offset);
        return new WorkflowDetailResponse(WorkflowResponse.from(value.workflow()),value.versions().stream().limit(limit).map(WorkflowVersionResponse::from).toList(),value.versions().size()>limit?offset+limit:null);
    }
    @PostMapping(value="/{workflowId}/versions",consumes="application/json")
    public ResponseEntity<WorkflowVersionResponse> publish(@PathVariable UUID workflowId,@RequestBody String body) {
        var result=service.publish(workflowId,body);
        return ResponseEntity.status(result.created()?201:200).body(WorkflowVersionResponse.from(result.value()));
    }
}
