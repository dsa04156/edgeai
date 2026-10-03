package io.edgeai.app.controller;

import io.edgeai.app.dto.*;
import io.edgeai.app.service.StreamRunService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workflow-runs/{runId}/streams")
public class StreamRunController {
    private final StreamRunService service;
    public StreamRunController(StreamRunService service){this.service=service;}
    @GetMapping public ResourcePageResponse<StreamRouteResponse> list(@PathVariable UUID runId,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        var values=service.list(runId,limit,offset);
        return new ResourcePageResponse<>(values.stream().limit(limit).toList(),values.size()>limit?offset+limit:null);
    }
}
