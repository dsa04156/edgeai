package io.edgeai.app.controller;

import io.edgeai.app.dto.ProfilePageResponse;
import io.edgeai.app.dto.ProfileVersionResponse;
import io.edgeai.app.exception.ControlPlaneException;
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
        if (kind == ProfileIdentity.Kind.VD) throw new IllegalArgumentException("VD profiles are managed by continuous registration only");
        if (kind == ProfileIdentity.Kind.DEVICE) {
            throw new ControlPlaneException(410, "DEVICE_PROFILE_RETIRED",
                "기존 앱 장치 프로필 등록은 종료됐습니다. 센서 화면에서 장치와 측정 항목을 등록하세요.");
        }
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
        if (kind == ProfileIdentity.Kind.VD) throw new IllegalArgumentException("VD profiles are managed by continuous registration only");
        var items = service.list(kind, key, limit, offset);
        return new ProfilePageResponse(items.stream().limit(limit).map(this::response).toList(),
            items.size() > limit ? offset + limit : null);
    }
    @GetMapping("/{key}/versions/{version}")
    public ProfileVersionResponse find(@PathVariable ProfileIdentity.Kind kind, @PathVariable String key, @PathVariable String version) {
        if (kind == ProfileIdentity.Kind.VD) throw new IllegalArgumentException("VD profiles are managed by continuous registration only");
        return response(service.find(new ProfileIdentity(kind, key, version)));
    }
    @DeleteMapping("/{key}/versions/{version}")
    public ResponseEntity<Void> delete(@PathVariable ProfileIdentity.Kind kind, @PathVariable String key, @PathVariable String version) {
        if (kind == ProfileIdentity.Kind.VD) throw new IllegalArgumentException("VD profiles are managed by continuous registration only");
        service.delete(new ProfileIdentity(kind, key, version));
        return ResponseEntity.noContent().build();
    }
    private ProfileVersionResponse response(ProfileVersion version) {
        return new ProfileVersionResponse(version.id(), version.identity().kind(), version.identity().key(),
            version.identity().version(), json.decode(version.specJson()), version.digest(), version.createdAt());
    }
}
