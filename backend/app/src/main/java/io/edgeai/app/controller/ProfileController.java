package io.edgeai.app.controller;

import io.edgeai.app.dto.ProfilePageResponse;
import io.edgeai.app.dto.ProfileVersionResponse;
import io.edgeai.app.service.ProfileService;
import io.edgeai.app.support.ProfileJson;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.profile.ProfileVersion;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles/{kind}")
public class ProfileController {
    private final ProfileService service;
    private final ProfileJson json;
    public ProfileController(ProfileService service, ProfileJson json) { this.service = service; this.json = json; }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<ProfileVersionResponse> publish(@PathVariable ProfileIdentity.Kind kind, @RequestBody String body) {
        var result = service.publish(kind, body);
        var identity = result.version().identity();
        var location = URI.create("/api/v1/profiles/" + kind + "/" + identity.key() + "/versions/" + identity.version());
        return ResponseEntity.status(result.created() ? 201 : 200).location(location).body(response(result.version()));
    }
    @GetMapping
    public ProfilePageResponse list(@PathVariable ProfileIdentity.Kind kind,
                     @RequestParam(required = false) String key,
                     @RequestParam(defaultValue = "20") int limit,
                     @RequestParam(defaultValue = "0") int offset) {
        var items = service.list(kind, key, limit, offset);
        return new ProfilePageResponse(items.stream().limit(limit).map(this::response).toList(),
            items.size() > limit ? offset + limit : null);
    }
    @GetMapping("/{key}/versions/{version}")
    public ProfileVersionResponse find(@PathVariable ProfileIdentity.Kind kind, @PathVariable String key, @PathVariable String version) {
        return response(service.find(new ProfileIdentity(kind, key, version)));
    }
    @DeleteMapping("/{key}/versions/{version}")
    public ResponseEntity<Void> delete(@PathVariable ProfileIdentity.Kind kind, @PathVariable String key, @PathVariable String version) {
        service.delete(new ProfileIdentity(kind, key, version));
        return ResponseEntity.noContent().build();
    }
    private ProfileVersionResponse response(ProfileVersion version) {
        return new ProfileVersionResponse(version.id(), version.identity().kind(), version.identity().key(),
            version.identity().version(), json.decode(version.specJson()), version.digest(), version.createdAt());
    }
}
