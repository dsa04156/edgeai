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
    public ContinuousDeviceController(ContinuousRegistrationService registrations,ContinuousOutputService outputs){this.registrations=registrations;this.outputs=outputs;}
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
