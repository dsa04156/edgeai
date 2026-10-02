package io.edgeai.app.profile;

import io.edgeai.domain.profile.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles/{kind}")
public class ProfileController {
    private final ProfileService service;
    private final ProfileJson json;
    public ProfileController(ProfileService service, ProfileJson json) { this.service = service; this.json = json; }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<VersionResponse> publish(@PathVariable ProfileIdentity.Kind kind, @RequestBody String body) {
        var result = service.publish(kind, body);
        var identity = result.version().identity();
        var location = URI.create("/api/v1/profiles/" + kind + "/" + identity.key() + "/versions/" + identity.version());
        return ResponseEntity.status(result.created() ? 201 : 200).location(location).body(response(result.version()));
    }
    @GetMapping
    public Page list(@PathVariable ProfileIdentity.Kind kind,
                     @RequestParam(required = false) String key,
                     @RequestParam(defaultValue = "20") int limit,
                     @RequestParam(defaultValue = "0") int offset) {
        var items = service.list(kind, key, limit, offset);
        return new Page(items.stream().limit(limit).map(this::response).toList(),
            items.size() > limit ? offset + limit : null);
    }
    @GetMapping("/{key}/versions/{version}")
    public VersionResponse find(@PathVariable ProfileIdentity.Kind kind, @PathVariable String key, @PathVariable String version) {
        return response(service.find(new ProfileIdentity(kind, key, version)));
    }
    private VersionResponse response(ProfileVersion version) {
        return new VersionResponse(version.id(), version.identity().kind(), version.identity().key(),
            version.identity().version(), json.decode(version.specJson()), version.digest(), version.createdAt());
    }
    public record VersionResponse(UUID id, ProfileIdentity.Kind kind, String key, String version,
                                  Object spec, String digest, Instant createdAt) {}
    public record Page(List<VersionResponse> items, Integer nextOffset) {}
}
