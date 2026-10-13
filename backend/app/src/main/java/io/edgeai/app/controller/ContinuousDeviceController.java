package io.edgeai.app.controller;

import io.edgeai.app.dto.VirtualDeviceResponse;
import io.edgeai.app.service.ContinuousRegistrationService;
import io.edgeai.app.service.ContinuousOutputService;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/virtual-devices")
public class ContinuousDeviceController {
    private final ContinuousRegistrationService registrations;
    private final ContinuousOutputService outputs;
    private final io.edgeai.app.service.VirtualDeviceService devices;
    private final io.edgeai.app.service.ContinuousExecutionService executions;
    private final io.edgeai.domain.repository.ProfileRepository profiles;
    public ContinuousDeviceController(ContinuousRegistrationService registrations,ContinuousOutputService outputs,io.edgeai.app.service.VirtualDeviceService devices,io.edgeai.app.service.ContinuousExecutionService executions,io.edgeai.domain.repository.ProfileRepository profiles){this.registrations=registrations;this.outputs=outputs;this.devices=devices;this.executions=executions;this.profiles=profiles;}
    @GetMapping public io.edgeai.app.dto.ResourcePageResponse<io.edgeai.app.dto.VirtualDeviceResponse> list(@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){var items=devices.list(limit,offset);return new io.edgeai.app.dto.ResourcePageResponse<>(items.stream().limit(limit).map(VirtualDeviceResponse::from).toList(),items.size()>limit?offset+limit:null);}
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id){var snapshot=devices.detail(id);var dto=io.edgeai.app.dto.VirtualDeviceDetailResponse.from(snapshot);return Map.of("vd",dto.vd(),"activeSources",dto.activeSources(),"sourceHistory",dto.sourceHistory(),"sourceHistoryTruncated",dto.sourceHistoryTruncated(),"continuous",io.edgeai.app.support.WorkflowInput.JSON.decode(io.edgeai.app.support.VirtualDeviceInput.spec(profiles.find(snapshot.vd().profileVersionId()).orElseThrow().specJson()).continuousJson()));}
    @DeleteMapping("/{id}") public VirtualDeviceResponse release(@PathVariable UUID id){return VirtualDeviceResponse.from(devices.release(id));}
    @GetMapping("/{id}/execution") public Map<String,Object> execution(@PathVariable UUID id){devices.detail(id);return Map.of("continuous",executions.state(id));}
    @PostMapping(value="/{id}/provision",consumes="application/json") public ResponseEntity<io.edgeai.app.dto.VDOperationResponse> start(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody String body){return command(id,key,body,io.edgeai.app.service.ContinuousExecutionService.Action.PROVISION);}
    @PostMapping(value="/{id}/drain",consumes="application/json") public ResponseEntity<io.edgeai.app.dto.VDOperationResponse> stop(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody String body){return command(id,key,body,io.edgeai.app.service.ContinuousExecutionService.Action.DRAIN);}
    private ResponseEntity<io.edgeai.app.dto.VDOperationResponse> command(UUID id,String key,String body,io.edgeai.app.service.ContinuousExecutionService.Action action){var result=executions.request(id,key,body,action);return ResponseEntity.status(result.created()?202:200).location(URI.create("/api/v1/operations/"+result.value().id())).body(result.value());}
    @GetMapping("/continuous-catalog") public Map<String,Object> catalog(){return registrations.catalog();}
    @GetMapping("/{id}/outputs") public Map<String,Object> outputs(@PathVariable UUID id){return outputs.outputs(id);}
    @GetMapping("/{id}/outputs/{outputId}/history") public Map<String,Object> history(@PathVariable UUID id,@PathVariable String outputId,
        @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(defaultValue="1000") int limit,
        @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer bucketSeconds){return outputs.history(id,outputId,from,to,limit,cursor,bucketSeconds);}
    @PostMapping(value="/continuous",consumes="application/json")
    public ResponseEntity<VirtualDeviceResponse> create(@RequestBody String body) {
        var result=registrations.create(body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/virtual-devices/"+result.value().id())).body(VirtualDeviceResponse.from(result.value()));
    }
    @PatchMapping(value="/{id}/continuous",consumes="application/json") public VirtualDeviceResponse update(@PathVariable UUID id,@RequestBody String body){return VirtualDeviceResponse.from(registrations.update(id,body));}
}
