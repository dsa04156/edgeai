package io.edgeai.app.controller;

import io.edgeai.app.dto.OffloadLabResponse;
import io.edgeai.app.service.OffloadLabService;
import io.edgeai.domain.execution.OffloadLabSession;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@ConditionalOnProperty(name="edgeai.workflow.enabled",havingValue="true")
@RestController
@RequestMapping("/api/v1/offload-lab")
public class OffloadLabController {
    private final OffloadLabService service;
    public OffloadLabController(OffloadLabService service){this.service=service;}
    public record SessionResponse(UUID id,String state,UUID runId,UUID edgeNodeId,String edgeNodeName,String loadDesired,int loadGeneration,
            Instant loadExpiresAt,Instant createdAt,Instant stoppedAt) {
        static SessionResponse from(OffloadLabSession s){return new SessionResponse(s.id(),s.state(),s.runId(),s.edgeNodeId(),s.edgeNodeName(),s.loadDesired(),
            s.loadGeneration(),s.loadExpiresAt(),s.createdAt(),s.stoppedAt());}
    }
    @GetMapping
    public ResponseEntity<OffloadLabResponse> view(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view());}
    @PostMapping(value="/sessions",consumes="application/json")
    public ResponseEntity<SessionResponse> start(@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=service.start(key,body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/offload-lab")).body(SessionResponse.from(result.value()));
    }
    @PostMapping(value="/sessions/{id}/stop",consumes="application/json")
    public SessionResponse stop(@PathVariable UUID id,@RequestBody String body){return SessionResponse.from(service.stop(id,body));}
    @PostMapping(value="/sessions/{id}/load",consumes="application/json")
    public ResponseEntity<SessionResponse> load(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=service.startLoad(id,key,body);
        return ResponseEntity.status(result.created()?201:200).body(SessionResponse.from(result.value()));
    }
    @PostMapping(value="/sessions/{id}/load/stop",consumes="application/json")
    public SessionResponse stopLoad(@PathVariable UUID id,@RequestBody String body){return SessionResponse.from(service.stopLoad(id,body));}
}
