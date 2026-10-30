package io.edgeai.app.support;

import io.edgeai.app.support.ResourceProfileInput.Confirmed;
import io.edgeai.domain.aimodel.*;
import io.edgeai.domain.resourceprofile.AcceleratorRequirement;
import java.util.*;

/** Stored documents and API views of AI model catalog entries and registry lookups. */
public final class AiModelDocuments {
    public static final String DOCUMENT_SCOPE = "edgeai-ai-model-execution-v1";
    public static final List<String> LIMITATIONS = List.of(
        "자원 요구량은 실행 측에 전달할 선언이며 자원 예약·분할·증강이나 실행 보장이 아닙니다.",
        "등록은 실행 모듈(SERVICE)을 발행하거나 워크플로·Pod를 만들지 않습니다. 워크플로 연동은 후속 범위입니다.",
        "이미지는 등록 시 검증한 digest로 고정되며 이후 tag가 바뀌어도 이 항목은 바뀌지 않습니다.");
    private AiModelDocuments() {}

    public static LinkedHashMap<String, Object> ordered(Object... pairs) { return ResourceProfileDocuments.ordered(pairs); }

    public static Map<String, Object> resources(Confirmed cpu, Confirmed memory, List<AcceleratorRequirement> accelerators) {
        var c = cpu.range(); var m = memory.range();
        return ordered(
            "cpu", ordered("minMillicores", c.min(), "requestMillicores", c.request(), "maxMillicores", c.max(), "basis", cpu.basis().name()),
            "memory", ordered("minBytes", m.min(), "requestBytes", m.request(), "maxBytes", m.max(), "basis", memory.basis().name()),
            "accelerators", accelerators.stream().map(AiModelDocuments::accelerator).toList());
    }
    public static Object accelerator(AcceleratorRequirement a) {
        var map = ordered("kind", a.kind().name(), "resourceName", a.resourceName(), "unit", a.unit().name(),
            "min", a.range().min(), "request", a.range().request(), "max", a.range().max());
        map.put("deviceMemoryBytes", a.deviceMemoryBytes()); map.put("vendor", a.vendor()); map.put("models", a.models());
        return map;
    }

    public static Map<String, Object> platform(ImagePlatform p) {
        var map = ordered("os", p.os(), "architecture", p.architecture());
        map.put("variant", p.variant());
        map.put("label", p.label());
        map.put("runnable", p.runnable());
        return map;
    }

    public static Map<String, Object> resolved(String host, ResolvedImage image) {
        var map = ordered("registry", host, "repository", image.repository(), "reference", image.reference(), "digest", image.digest(),
            "mediaType", image.mediaType(), "kind", image.kind().name(), "imageReference", host + "/" + image.repository() + "@" + image.digest());
        map.put("platforms", image.platforms().stream().map(p -> {
            var row = platform(p.platform());
            row.put("manifestDigest", p.digest()); row.put("mediaType", p.mediaType()); row.put("size", p.size());
            return (Object) row;
        }).toList());
        return map;
    }

    public static Map<String, Object> image(String host, VerifiedImage v, String sourceTag, String verifiedAt) {
        var map = ordered("registry", host, "repository", v.repository(), "digest", v.digest(),
            "reference", host + "/" + v.repository() + "@" + v.digest(), "mediaType", v.mediaType(), "kind", v.kind().name());
        map.put("sourceTag", sourceTag);
        map.put("platform", platform(v.platform()));
        map.put("platformManifestDigest", v.platformManifestDigest());
        map.put("platformReference", host + "/" + v.repository() + "@" + v.platformManifestDigest());
        map.put("platformManifestMediaType", v.platformManifestMediaType());
        map.put("configDigest", v.configDigest());
        map.put("configMediaType", v.configMediaType());
        map.put("layersSize", v.layersSize());
        map.put("created", v.created());
        map.put("verifiedAt", verifiedAt);
        return map;
    }

    /** API view of a stored entry: storage identity plus the canonical stored document. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> entry(AiModelExecution e) {
        var result = ordered("id", e.id().toString(), "digest", e.digest(), "createdAt", e.createdAt().toString(),
            "measurementStatus", e.measurementStatus());
        result.put("resourceProfileId", e.resourceProfileId() == null ? null : e.resourceProfileId().toString());
        result.putAll((Map<String, Object>) WorkflowInput.JSON.decode(e.documentJson()));
        return result;
    }
}
