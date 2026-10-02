package io.edgeai.app.controller;
import io.edgeai.app.config.VDPrincipal;
import io.edgeai.app.service.VDPollService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

@RestController
@ConditionalOnProperty(name={"edgeai.runtime.enabled","edgeai.vd.enabled"},havingValue="true")
@RequestMapping(value="/internal/v1/vd-runtimes/{runtimeId}/poll",consumes="application/json",produces="application/json")
public class VDPollController {
    private final VDPollService service;
    public VDPollController(VDPollService service){this.service=service;}
    @PostMapping public ResponseEntity<String> poll(@AuthenticationPrincipal VDPrincipal principal,@RequestBody byte[] body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(JSON.boundedCanonical(service.poll(principal,body),262144));
    }
}
