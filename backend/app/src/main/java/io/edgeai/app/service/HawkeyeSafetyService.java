package io.edgeai.app.service;

import io.edgeai.app.config.HawkeyeSafetyProperties;
import io.edgeai.app.dto.HawkeyeSafetyRunResponse;
import io.edgeai.app.dto.HawkeyeSafetyScenarioResponse;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.HawkeyeSafetyStatus;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.repository.Creation;
import io.edgeai.domain.repository.HawkeyeSafetyRepository;
import io.edgeai.domain.scenario.ClassificationRuntime;
import io.edgeai.domain.scenario.HawkeyeRuntime;
import io.edgeai.domain.scenario.HawkeyeSafetyRun;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Hawkeye warehouse-safety scenario: one pinned sample per run, one active run, executed by HawkeyeSafetyExecutor. */
@Service
public class HawkeyeSafetyService {
    public static final String ID = "hawkeye-warehouse-safety";
    private static final JsonDocuments JSON = new JsonDocuments();
    private static final JsonMapper SNAPSHOT = JsonMapper.builder().build();
    private final HawkeyeSafetyRepository repository;
    private final HawkeyeSafetyExecutor executor;
    private final HawkeyeRuntime runtime;
    private final HawkeyeSafetyProperties properties;

    public HawkeyeSafetyService(HawkeyeSafetyRepository repository, HawkeyeSafetyExecutor executor,
            ObjectProvider<HawkeyeRuntime> runtime, HawkeyeSafetyProperties properties) {
        this.repository = repository;
        this.executor = executor;
        this.runtime = runtime.getIfAvailable();
        this.properties = properties;
    }

    public HawkeyeSafetyScenarioResponse capability() {
        var m = properties.model(); var d = properties.dataset();
        HawkeyeSafetyScenarioResponse.Runtime state = null;
        if (runtime != null) {
            var status = HawkeyeSafetyStatus.of(runtime, properties);
            state = new HawkeyeSafetyScenarioResponse.Runtime(status.state(), status.reported(), status.error());
        }
        return new HawkeyeSafetyScenarioResponse(ID, runtime != null, HawkeyeSafetyRun.CATEGORIES,
            new HawkeyeSafetyScenarioResponse.Model(m.name(), m.ollamaName(), m.repository(), m.revision(), m.license(),
                m.manifestDigest(), m.languageSha256(), m.visionSha256()),
            new HawkeyeSafetyScenarioResponse.Dataset(d.name(), d.repository(), d.revision(), d.license(), d.split(), true, d.samplesSha256()),
            properties.samples(), state, repository.active().orElse(null));
    }

    /** Exact pinned sample bytes, served only when they match the configured SHA-256. */
    public byte[] sampleImage(String sampleId) {
        var sample = properties.sample(sampleId);
        if (sample == null) throw new ControlPlaneException(404, "SAMPLE_NOT_FOUND", "고정 샘플을 찾을 수 없습니다.");
        if (runtime == null) throw disabled();
        byte[] image;
        try { image = runtime.sample(sampleId); }
        catch (ClassificationRuntime.Failure failure) {
            throw new ControlPlaneException(503, "RUNTIME_UNAVAILABLE", "안전 점검 런타임에서 샘플을 읽지 못했습니다.");
        }
        if (!sample.sha256().equals(ImageComparisonExecutor.sha256(image)))
            throw new ControlPlaneException(502, "SAMPLE_MISMATCH", "런타임 샘플이 고정 SHA-256과 다릅니다.");
        return image;
    }

    public Creation<HawkeyeSafetyRunResponse> start(String key, String body) {
        UUID idempotency = UUID.fromString(key);
        if (!key.equals(idempotency.toString())) throw invalid();
        var sample = sample(body);
        if (runtime == null) throw disabled();
        var id = UUID.randomUUID();
        if (repository.create(id, idempotency, sample.id(), sample.referenceLabel(), executor.owner(), properties.lease())) {
            executor.submit(id);
            return new Creation<>(detail(id), true);
        }
        var existing = repository.byIdempotencyKey(idempotency);
        if (existing.isPresent()) {
            if (!existing.get().sampleId().equals(sample.id()))
                throw new ControlPlaneException(409, "IDEMPOTENCY_CONFLICT", "같은 Idempotency-Key에 다른 샘플이 있습니다.");
            return new Creation<>(response(existing.get()), false);
        }
        throw new ControlPlaneException(409, "SCENARIO_RUN_ACTIVE", "이미 진행 중인 안전 점검 실행이 있습니다. 끝난 뒤 시작하세요.");
    }

    public List<HawkeyeSafetyRun> list(int limit, int offset) {
        WorkflowService.page(limit, offset);
        return repository.list(limit + 1, offset);
    }

    public HawkeyeSafetyRunResponse detail(UUID id) {
        return response(repository.find(id).orElseThrow(
            () -> new ControlPlaneException(404, "SCENARIO_RUN_NOT_FOUND", "안전 점검 실행을 찾을 수 없습니다.")));
    }

    public static HawkeyeSafetyRunResponse response(HawkeyeSafetyRun run) {
        var target = run.snapshotJson() == null ? null : SNAPSHOT.readValue(run.snapshotJson(), HawkeyeSafetyRunResponse.Target.class);
        var r = run.result();
        var result = r == null ? null : new HawkeyeSafetyRunResponse.Result(r.category(), r.explanation(),
            r.category() == null ? null : r.category().equals(run.referenceLabel()), r.rawOutput(), r.doneReason(), r.inputSha256(),
            r.processor(), r.sizeBytes(), r.sizeVramBytes(), r.inferenceMs(), r.loadMs(), r.roundTripMs(), r.promptEvalCount(), r.evalCount());
        return new HawkeyeSafetyRunResponse(run.id(), run.sampleId(), run.referenceLabel(), run.state(), run.createdAt(), run.startedAt(),
            run.finishedAt(), run.failureCode(), run.failureMessage(), target, result);
    }

    static String snapshot(HawkeyeSafetyRunResponse.Target target) { return SNAPSHOT.writeValueAsString(target); }

    private HawkeyeSafetyProperties.Sample sample(String body) {
        if (!(JSON.parse(body, 1024) instanceof Map<?, ?> request) || !request.keySet().equals(Set.of("sampleId"))
            || !(request.get("sampleId") instanceof String id) || properties.sample(id) == null) throw invalid();
        return properties.sample(id);
    }
    private static ControlPlaneException disabled() {
        return new ControlPlaneException(503, "SCENARIO_DISABLED", "Hawkeye 안전 점검 런타임이 설정되지 않았습니다.");
    }
    private static ControlPlaneException invalid() {
        return new ControlPlaneException(400, "INVALID_SCENARIO_REQUEST", "UUID Idempotency-Key와 고정 샘플 sampleId를 입력하세요.");
    }
}
