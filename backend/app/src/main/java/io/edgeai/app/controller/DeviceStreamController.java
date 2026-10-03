package io.edgeai.app.controller;
import io.edgeai.app.config.DeviceStreamPrincipal;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.StreamBindingService;
import io.edgeai.app.support.JsonDocuments;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(consumes="application/json",produces="application/json")
public class DeviceStreamController {
    private final ObjectProvider<StreamBindingService> bindings;
    public DeviceStreamController(ObjectProvider<StreamBindingService> bindings){this.bindings=bindings;}
    @PostMapping("/api/v1/devices/{deviceId}/sessions/{sessionId}/stream-token")
    public ResponseEntity<String> token(@PathVariable UUID deviceId,@PathVariable UUID sessionId,@RequestBody String body){return response(service().deviceToken(deviceId,sessionId,body));}
    @PostMapping("/internal/v1/devices/{deviceId}/sessions/{sessionId}/streams")
    public ResponseEntity<String> binding(@AuthenticationPrincipal DeviceStreamPrincipal principal,@RequestBody String body){return response(service().device(principal,body));}
    @PostMapping("/internal/v1/devices/{deviceId}/sessions/{sessionId}/streams/heartbeat")
    public ResponseEntity<String> heartbeat(@AuthenticationPrincipal DeviceStreamPrincipal principal,@RequestBody String body){return response(service().deviceHeartbeat(principal,body));}
    private StreamBindingService service(){var service=bindings.getIfAvailable();if(service==null)throw new ControlPlaneException(501,"STREAM_DISABLED","스트림 배정 기능이 비활성입니다.");return service;}
    private static ResponseEntity<String> response(Object value){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(new JsonDocuments().boundedCanonical(value,262144));}
}
