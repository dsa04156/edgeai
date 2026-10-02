package io.edgeai.app.controller;
import io.edgeai.app.dto.*;
import io.edgeai.app.service.NodeService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {
    private final NodeService service;
    private final Clock clock;
    public NodeController(NodeService service,Clock clock) { this.service=service;this.clock=clock; }
    @GetMapping
    public ResourcePageResponse<NodeResponse> list(@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var values=service.list(limit,offset);var now=clock.instant();
        return new ResourcePageResponse<>(values.stream().limit(limit).map(n->NodeResponse.from(n,now)).toList(),values.size()>limit?offset+limit:null);
    }
    @GetMapping("/{nodeId}")
    public NodeResponse detail(@PathVariable UUID nodeId) { return NodeResponse.from(service.find(nodeId),clock.instant()); }
}
