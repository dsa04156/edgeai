package io.edgeai.app.controller;
import io.edgeai.app.dto.*;
import io.edgeai.app.service.ExecutionService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="edgeai.workflow.enabled", havingValue="true")
@RestController
@RequestMapping("/api/v1/workflow-runs")
public class WorkflowRunController {
    private final ExecutionService service;
    public WorkflowRunController(ExecutionService service) { this.service=service; }
    @PostMapping(consumes="application/json")
    public ResponseEntity<WorkflowRunResponse> create(@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=service.create(key,body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/workflow-runs/"+result.value().id())).body(WorkflowRunResponse.from(result.value()));
    }
    @GetMapping
    public ResourcePageResponse<WorkflowRunResponse> list(@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var values=service.list(limit,offset);return new ResourcePageResponse<>(values.stream().limit(limit).map(WorkflowRunResponse::from).toList(),values.size()>limit?offset+limit:null);
    }
    @GetMapping("/{runId}")
    public RunDetailResponse detail(@PathVariable UUID runId) {
        var value=service.detail(runId);return new RunDetailResponse(WorkflowRunResponse.from(value.run()),value.tasks().stream().map(TaskResponse::from).toList());
    }
    @PostMapping(value="/{runId}/cancel",consumes="application/json")
    public WorkflowRunResponse cancel(@PathVariable UUID runId,@RequestBody String body) { return WorkflowRunResponse.from(service.cancelRun(runId,body)); }
}
