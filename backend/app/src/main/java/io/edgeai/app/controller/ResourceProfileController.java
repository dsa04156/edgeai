package io.edgeai.app.controller;

import io.edgeai.app.service.ResourceAnalysisService;
import io.edgeai.app.service.ResourceProfileService;
import io.edgeai.app.support.ResourceProfileDocuments;
import java.net.URI;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 3-3 resource requirement profiles (separate from timing-based scheduling profiles) and the 2-1 handoff. */
@ConditionalOnProperty(name="edgeai.resource-profiling.enabled",havingValue="true")
@RestController
@RequestMapping("/api/v1/resource-profiles")
public class ResourceProfileController {
    private final ResourceProfileService profiles;
    private final ResourceAnalysisService analyses;
    public ResourceProfileController(ResourceProfileService profiles,ResourceAnalysisService analyses) { this.profiles=profiles;this.analyses=analyses; }

    @GetMapping("/capabilities")
    public Map<String,Object> capabilities() { return analyses.capabilities(); }
    @GetMapping("/workloads")
    public Map<String,Object> workloads(@RequestParam String namespace,@RequestParam(required=false) Integer hours,
                                        @RequestParam(required=false) String start,@RequestParam(required=false) String end) {
        return Map.of("items",analyses.workloads(namespace,hours,start,end).stream().map(w->Map.of("namespace",w.namespace(),"kind",w.kind().name(),"name",w.name(),"containers",w.containers())).toList());
    }
    @GetMapping("/pods")
    public Map<String,Object> pods(@RequestParam String namespace,@RequestParam String kind,@RequestParam String name,@RequestParam String container,
                                   @RequestParam String start,@RequestParam String end) {
        return Map.of("items",analyses.pods(namespace,kind,name,container,start,end).stream().map(p->Map.of("name",p.name(),"uid",p.uid(),"node",p.node())).toList());
    }
    @PostMapping(value="/analyses",consumes="application/json")
    public ResponseEntity<Map<String,Object>> analyze(@RequestBody String body) {
        var result=analyses.analyze(body);
        return ResponseEntity.status(201).location(URI.create("/api/v1/resource-profiles/analyses/"+result.get("id"))).body(result);
    }
    @GetMapping("/analyses/{analysisId}")
    public Map<String,Object> analysis(@PathVariable UUID analysisId) { return analyses.analysis(analysisId); }
    @PostMapping(consumes="application/json")
    public ResponseEntity<Map<String,Object>> save(@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=profiles.save(key,body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/resource-profiles/"+result.value().id()))
            .body(ResourceProfileDocuments.profile(result.value()));
    }
    @GetMapping
    public Map<String,Object> list(@RequestParam(required=false) UUID serviceProfileVersionId,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var items=profiles.list(serviceProfileVersionId,limit,offset);
        var result=new LinkedHashMap<String,Object>();
        result.put("items",items.stream().limit(limit).map(ResourceProfileDocuments::profile).toList());
        result.put("nextOffset",items.size()>limit?offset+limit:null);
        return result;
    }
    @GetMapping("/{profileId}")
    public Map<String,Object> get(@PathVariable UUID profileId) { return ResourceProfileDocuments.profile(profiles.profile(profileId)); }
    @GetMapping("/{profileId}/handoff")
    public Map<String,Object> handoff(@PathVariable UUID profileId) { return ResourceProfileDocuments.handoff(profiles.profile(profileId)); }
    @GetMapping("/services/{serviceKey}/versions/{serviceVersion}/revisions/{revision}/handoff")
    public Map<String,Object> exact(@PathVariable String serviceKey,@PathVariable String serviceVersion,@PathVariable int revision) {
        return ResourceProfileDocuments.handoff(profiles.revision(serviceKey,serviceVersion,revision));
    }
}
