package io.edgeai.app.support;

import io.edgeai.app.support.ResourceProfileInput.Confirmed;
import io.edgeai.domain.aimodel.AiModelExecution;
import io.edgeai.domain.aimodel.ImagePlatform;
import io.edgeai.domain.aimodel.ImageReference;
import io.edgeai.domain.resourceprofile.AcceleratorRequirement;
import java.util.*;
import static io.edgeai.app.support.ResourceProfileInput.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * Strict parsing of an AI model execution registration. Resource quantities reuse the resource profile rules
 * (integer units, min ≤ request ≤ max, vendor-domain accelerator names); the image is a repository plus the sha256
 * digest the user confirmed, never a free-form image string.
 */
public final class AiModelInput {
    public static final int MAX_BYTES = 65536;
    public static final String FINGERPRINT_SCOPE = "edgeai-ai-model-request-v1";
    private AiModelInput() {}

    public record Image(String repository, String tag, String digest, ImagePlatform platform) {}
    public record Register(String modelKey, String displayName, String modelVersion, String description, String configName, Image image,
                           List<String> command, List<String> args, Map<String, String> inputConditions, Map<String, String> runtime,
                           Confirmed cpu, Confirmed memory, List<AcceleratorRequirement> accelerators, UUID resourceProfileId) {
        /** Normalised request: the idempotency fingerprint and the user-supplied part of the stored document. */
        public Map<String, Object> normalized() {
            var map = AiModelDocuments.ordered("modelKey", modelKey, "displayName", displayName, "modelVersion", modelVersion,
                "description", description, "configName", configName);
            var platform = AiModelDocuments.ordered("os", image.platform().os(), "architecture", image.platform().architecture(), "variant", image.platform().variant());
            map.put("image", AiModelDocuments.ordered("repository", image.repository(), "tag", image.tag(), "digest", image.digest(), "platform", platform));
            map.put("execution", AiModelDocuments.ordered("command", command, "args", args));
            map.put("inputConditions", inputConditions);
            map.put("runtime", runtime);
            map.put("resources", AiModelDocuments.resources(cpu, memory, accelerators));
            map.put("resourceProfileId", resourceProfileId == null ? null : resourceProfileId.toString());
            return map;
        }
        public String fingerprint() { return JSON.digest(FINGERPRINT_SCOPE, normalized()); }
    }

    public static Register register(String body) {
        var map = fields(JSON.parse(body, MAX_BYTES), Set.of("modelKey", "displayName", "modelVersion", "image", "resources"),
            Set.of("description", "configName", "execution", "inputConditions", "runtime", "resourceProfileId"));
        JSON.boundedCanonical(map, MAX_BYTES);
        String key = text(map.get("modelKey"), 100);
        if (!key.matches(AiModelExecution.KEY)) throw new IllegalArgumentException("모델 키는 소문자로 시작하는 영문 소문자·숫자·._- 100자 이하입니다.");
        String version = text(map.get("modelVersion"), 64);
        if (!version.matches(AiModelExecution.VERSION)) throw new IllegalArgumentException("모델 버전은 영문·숫자로 시작하는 ._+- 포함 64자 이하입니다.");
        String config = map.get("configName") == null ? AiModelExecution.DEFAULT_CONFIG : text(map.get("configName"), 63);
        if (!config.matches(AiModelExecution.CONFIG)) throw new IllegalArgumentException("실행 구성명은 영문 소문자·숫자·- 63자 이하입니다.");
        String display = text(map.get("displayName"), 200).strip();
        String description = map.get("description") == null ? null : text(map.get("description"), 2000);

        var i = fields(map.get("image"), Set.of("repository", "digest", "platform"), Set.of("tag"));
        var p = fields(i.get("platform"), Set.of("os", "architecture"), Set.of("variant"));
        var platform = new ImagePlatform(text(p.get("os"), 32), text(p.get("architecture"), 32), p.get("variant") == null ? null : text(p.get("variant"), 16));
        if (!platform.runnable()) throw new IllegalArgumentException("등록할 수 있는 플랫폼은 linux/amd64·linux/arm64입니다.");
        var image = new Image(ImageReference.repository(text(i.get("repository"), ImageReference.MAX_REPOSITORY)),
            i.get("tag") == null ? null : ImageReference.tag(text(i.get("tag"), 128)), ImageReference.digest(text(i.get("digest"), 71)), platform);

        List<String> command = null, args = null;
        if (map.get("execution") != null) {
            var e = fields(map.get("execution"), Set.of(), Set.of("command", "args"));
            command = strings(e.get("command"), "command");
            args = strings(e.get("args"), "args");
        }
        var resources = fields(map.get("resources"), Set.of("cpu", "memory"), Set.of("accelerators"));
        return new Register(key, display, version, description, config, image, command, args,
            inputConditions(map.get("inputConditions")), runtime(map.get("runtime")),
            cpu(resources.get("cpu")), memory(resources.get("memory")), accelerators(resources.get("accelerators")),
            map.get("resourceProfileId") == null ? null : WorkflowInput.uuid(map.get("resourceProfileId")));
    }

    /** Optional exec form; an empty list is normalised to "not specified" so both spellings fingerprint alike. */
    static List<String> strings(Object value, String label) {
        if (value == null) return null;
        var values = new ArrayList<String>();
        for (Object item : list(value, 32)) {
            if (!(item instanceof String s) || s.isEmpty() || s.length() > 4096 || s.chars().anyMatch(c -> c == 0))
                throw new IllegalArgumentException(label + " 항목은 1–4096자 문자열입니다.");
            values.add(s);
        }
        return values.isEmpty() ? null : List.copyOf(values);
    }
}
