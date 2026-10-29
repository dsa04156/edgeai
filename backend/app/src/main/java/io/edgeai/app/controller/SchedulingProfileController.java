package io.edgeai.app.controller;
import io.edgeai.app.service.SchedulingService;
import io.edgeai.app.support.WorkflowInput;
import io.edgeai.domain.scheduling.SchedulingProfile;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="edgeai.workflow.enabled", havingValue="true")
@RestController
@RequestMapping("/api/v1/scheduling-profiles")
public class SchedulingProfileController {
    private final SchedulingService service;
    public SchedulingProfileController(SchedulingService service) { this.service=service; }
    @PostMapping(consumes="application/json")
    public ResponseEntity<Map<String,Object>> register(@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=service.register(key,body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/scheduling-profiles/"+result.value().id())).body(response(result.value()));
    }
    @GetMapping("/{profileId}")
    public Map<String,Object> get(@PathVariable UUID profileId) { return response(service.profile(profileId)); }
    @PostMapping(value="/{profileId}/placement-preview",consumes="application/json")
    public Map<String,Object> preview(@PathVariable UUID profileId,@RequestBody String body) { return service.preview(profileId,body); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> response(SchedulingProfile profile) {
        var result=new TreeMap<String,Object>((Map<String,Object>)WorkflowInput.JSON.decode(profile.documentJson()));
        result.put("id",profile.id().toString());result.put("digest",profile.digest());result.put("createdAt",profile.createdAt().toString());
        return result;
    }
}
