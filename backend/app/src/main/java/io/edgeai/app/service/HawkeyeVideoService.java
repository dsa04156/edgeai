package io.edgeai.app.service;

import io.edgeai.app.config.HawkeyeVideoProperties;
import io.edgeai.app.dto.HawkeyeVideoCapabilityResponse;
import io.edgeai.app.dto.HawkeyeVideoSessionResponse;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeScope;
import io.edgeai.domain.scenario.HawkeyeVideoSession;
import io.edgeai.domain.storage.ArtifactFiles;
import io.edgeai.domain.storage.ArtifactStoreUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hawkeye video sessions run one published SERVICE workflow (capture → infer) as consecutive bounded BATCH Runs over
 * one pinned RTSP camera. Each window is an ordinary platform Run; this service only orders them. Frames between two
 * windows are not read: delivery is windowed, never continuous.
 */
@Service
public class HawkeyeVideoService {
    public static final String FRAMES_PORT = "frames";
    public static final String FRAMES_MEDIA_TYPE = "application/x-tar";
    static final long MAX_FRAMES_BYTES = 16L * 1024 * 1024;
    private static final JsonDocuments JSON = new JsonDocuments();
    private final HawkeyeVideoRepository repository;
    private final ExecutionService executions;
    private final ExecutionRepository runs;
    private final RuntimeRepository runtimes;
    private final WorkflowRepository workflows;
    private final Optional<ArtifactFiles> files;
    private final TransactionTemplate transactions;
    private final HawkeyeVideoProperties properties;
    private final boolean executes;
    private final Clock clock;
    private final Semaphore readers = new Semaphore(4);
    private final ScheduledExecutorService scheduler;

    public HawkeyeVideoService(HawkeyeVideoRepository repository, ExecutionService executions, ExecutionRepository runs,
            RuntimeRepository runtimes, WorkflowRepository workflows, Optional<ArtifactFiles> files, TransactionTemplate transactions,
            HawkeyeVideoProperties properties, RuntimeScope scope, Clock clock, @Value("${edgeai.runtime.enabled:false}") boolean runtimeEnabled,
            @Value("${edgeai.recovery.inspect-only:false}") boolean inspection) {
        this.repository = repository; this.executions = executions; this.runs = runs; this.runtimes = runtimes; this.workflows = workflows;
        this.files = files; this.transactions = transactions; this.properties = properties; this.clock = clock;
        this.executes = runtimeEnabled && scope.allows(properties.workflowKey());
        // Without an executing runtime a window Run would only be stored, so sessions are not advanced at all.
        if (properties.enabled() && executes && !inspection) {
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("hawkeye-video-windows").daemon().factory());
            scheduler.scheduleWithFixedDelay(this::advanceAll, properties.poll().toMillis(), properties.poll().toMillis(), TimeUnit.MILLISECONDS);
        } else scheduler = null;
    }

    @jakarta.annotation.PreDestroy void close() { if (scheduler != null) scheduler.shutdownNow(); }

    public HawkeyeVideoCapabilityResponse capability() {
        var workflow = workflows.findByKey(properties.workflowKey()).orElse(null);
        var versions = workflow == null ? List.<HawkeyeVideoCapabilityResponse.Version>of() : workflows.versions(workflow.id(), 20, 0).stream()
            .map(v -> new HawkeyeVideoCapabilityResponse.Version(v.id(), v.version(), v.createdAt())).toList();
        return new HawkeyeVideoCapabilityResponse(properties.enabled(), properties.enabled() && executes, properties.workflowKey(),
            workflow == null ? null : new HawkeyeVideoCapabilityResponse.Workflow(workflow.id(), workflow.key(), workflow.displayName()),
            versions, properties.maxWindows(), repository.active().orElse(null));
    }

    public Creation<HawkeyeVideoSessionResponse> start(String key, String body) {
        UUID idempotency;
        try { idempotency = UUID.fromString(key); } catch (IllegalArgumentException e) { throw invalid(); }
        if (!key.equals(idempotency.toString())) throw invalid();
        var request = request(body);
        String digest = JSON.digest("edgeai-hawkeye-video-session-v1", request);
        var existing = repository.byIdempotencyKey(idempotency);
        if (existing.isPresent()) return replay(existing.get(), digest);
        if (!properties.enabled()) throw new ControlPlaneException(503, "SCENARIO_DISABLED", "영상 안전 점검이 설정되지 않았습니다.");
        if (!executes) throw new ControlPlaneException(503, "RUNTIME_DISABLED", "영상 워크플로를 실행할 실행 환경이 활성화되지 않았습니다.");
        UUID versionId = UUID.fromString((String) request.get("workflowVersionId"));
        var version = workflows.version(versionId).orElseThrow(() -> new ControlPlaneException(404, "WORKFLOW_NOT_FOUND", "발행된 DAG 버전이 없습니다."));
        var workflow = workflows.find(version.workflowId(), false).orElseThrow();
        if (!workflow.key().equals(properties.workflowKey()))
            throw new ControlPlaneException(409, "WORKFLOW_NOT_ALLOWED", "영상 안전 점검 워크플로의 버전을 선택하세요.");
        var camera = (Map<?, ?>) request.get("camera");
        var now = clock.instant();
        var session = new HawkeyeVideoSession(UUID.randomUUID(), idempotency, digest, versionId, (String) camera.get("id"),
            UUID.fromString((String) camera.get("uid")), null, properties.maxWindows(), "RUNNING", null, null, null, now, now, null);
        // The first window is created with the session, so an unavailable camera is reported here and leaves no session.
        var created = transactions.execute(status -> {
            if (!repository.create(session)) return null;
            window(session, 1);
            return session.id();
        });
        if (created == null) {
            var raced = repository.byIdempotencyKey(idempotency);
            if (raced.isPresent()) return replay(raced.get(), digest);
            throw new ControlPlaneException(409, "SCENARIO_SESSION_ACTIVE", "이미 진행 중인 영상 점검이 있습니다. 중지한 뒤 시작하세요.");
        }
        return new Creation<>(detail(created), true);
    }

    /** Ends the session, then cancels its unfinished window; no later window is created once this commits. */
    public HawkeyeVideoSessionResponse stop(UUID id, String body) {
        if (!(JSON.parse(body, 64) instanceof Map<?, ?> empty) || !empty.isEmpty()) throw invalid();
        transactions.executeWithoutResult(status -> {
            var session = repository.lock(id).orElseThrow(() -> notFound());
            if (!session.state().equals("RUNNING")) return;
            repository.end(id, "STOPPED", "USER_STOPPED", null, null, clock.instant());
            for (var window : repository.windows(id)) {
                var run = runs.run(window.runId(), true).orElseThrow();
                if (!HawkeyeVideoSession.FINISHED_RUN.contains(run.state()) && !run.state().equals("CANCELLING"))
                    executions.cancelRun(run.id(), "{}");
            }
        });
        return detail(id);
    }

    public List<HawkeyeVideoSession> list(int limit, int offset) {
        WorkflowService.page(limit, offset);
        return repository.list(limit + 1, offset);
    }

    public HawkeyeVideoSessionResponse summary(HawkeyeVideoSession session) {
        return HawkeyeVideoSessionResponse.from(session, repository.windows(session.id()), false);
    }

    public HawkeyeVideoSessionResponse detail(UUID id) {
        var session = repository.find(id).orElseThrow(HawkeyeVideoService::notFound);
        return HawkeyeVideoSessionResponse.from(session, repository.windows(id), true);
    }

    /** Creates the next window or ends the session; a failed or cancelled window ends it rather than being skipped. */
    void advance(UUID id) {
        try {
            transactions.executeWithoutResult(status -> {
                var session = repository.lock(id).orElseThrow();
                if (!session.state().equals("RUNNING")) return;
                var windows = repository.windows(id);
                if (!windows.isEmpty()) {
                    var last = windows.getFirst();
                    if (!HawkeyeVideoSession.FINISHED_RUN.contains(last.runState())) return;
                    if (!last.runState().equals("SUCCEEDED")) {
                        repository.end(id, "FAILED", "WINDOW_FAILED", "WINDOW_" + last.runState(),
                            "창 " + last.seq() + "의 실행이 " + last.runState() + " 상태로 끝나 다음 창을 만들지 않았습니다.", clock.instant());
                        return;
                    }
                    if (windows.size() >= session.maxWindows()) {
                        repository.end(id, "COMPLETED", "MAX_WINDOWS", null, null, clock.instant());
                        return;
                    }
                }
                window(session, windows.size() + 1);
            });
        } catch (ControlPlaneException rejected) {
            transactions.executeWithoutResult(status -> {
                if (repository.lock(id).orElseThrow().state().equals("RUNNING"))
                    repository.end(id, "FAILED", "WINDOW_REJECTED", rejected.code(), rejected.getMessage(), clock.instant());
            });
        }
    }

    private void advanceAll() {
        try {
            for (var id : repository.running()) advance(id);
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(HawkeyeVideoService.class).warn("Hawkeye video windows deferred ({})", error.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private void window(HawkeyeVideoSession session, int seq) {
        var body = JSON.canonical(Map.of("workflowVersionId", session.workflowVersionId().toString(), "parameters", Map.of(),
            "execution", Map.of("mode", "AUTO"), "camera", Map.of("id", session.deviceId(), "uid", session.deviceUid().toString())));
        var key = UUID.nameUUIDFromBytes(("edgeai-hawkeye-video:" + session.id() + ":" + seq).getBytes(StandardCharsets.UTF_8));
        var run = executions.create(key.toString(), body).value();
        // The camera stays the one pinned by the first window: a changed configuration or source generation under the
        // same UID ends the session (this Run rolls back with it) instead of feeding other video into the next window.
        var pinned = new TreeMap<String, Object>((Map<String, Object>) ((Map<?, ?>) JSON.decode(run.parametersJson())).get(ManagedDeviceService.CAMERA_BINDING));
        pinned.remove("pinnedAt");
        String snapshot = JSON.canonical(pinned);
        if (!repository.pinCamera(session.id(), snapshot)) {
            var current = repository.find(session.id()).orElseThrow().cameraSourceJson();
            if (!JSON.decode(current).equals(JSON.decode(snapshot)))
                throw new ControlPlaneException(409, "CAMERA_SOURCE_CHANGED", "세션을 시작한 뒤 카메라 설정이나 영상 원본이 바뀌어 다음 구간을 만들지 않았습니다.");
        }
        repository.addWindow(session.id(), seq, run.id(), clock.instant());
    }

    /**
     * One JPEG from the verified frames artifact of a capture Task in a session window. The artifact digest was checked
     * when the Result was committed and again on download; the frame must also match its manifest SHA-256.
     */
    public byte[] frame(UUID taskId, int seq) {
        var task = runs.task(taskId).orElseThrow(HawkeyeVideoService::frameNotFound);
        if (!repository.windowRun(task.runId())) throw frameNotFound();
        var artifact = runtimes.result(taskId).stream().flatMap(r -> r.outputs().stream()).filter(o -> o.port().equals(FRAMES_PORT))
            .map(o -> o.artifact()).findFirst().orElseThrow(HawkeyeVideoService::frameNotFound);
        if (!FRAMES_MEDIA_TYPE.equals(artifact.mediaType()) || artifact.bytes() > MAX_FRAMES_BYTES) throw frameNotFound();
        var store = files.orElseThrow(ArtifactStoreUnavailableException::new);
        if (!readers.tryAcquire()) throw new ControlPlaneException(429, "RESULT_PREVIEW_BUSY", "결과 조회가 진행 중입니다. 잠시 후 다시 조회하세요.");
        try {
            var directory = Files.createTempDirectory("edgeai-frames-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var file = directory.resolve("frames.tar");
            try {
                store.downloadFile(artifact, file);
                return HawkeyeFrames.frame(Files.readAllBytes(file), seq);
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            }
        } catch (IOException e) {
            throw new ArtifactStoreUnavailableException();
        } finally {
            readers.release();
        }
    }

    private Creation<HawkeyeVideoSessionResponse> replay(HawkeyeVideoSession session, String digest) {
        if (!session.requestDigest().equals(digest))
            throw new ControlPlaneException(409, "IDEMPOTENCY_CONFLICT", "같은 Idempotency-Key에 다른 영상 점검 요청이 있습니다.");
        return new Creation<>(detail(session.id()), false);
    }
    private static Map<String, Object> request(String body) {
        try {
            if (!(JSON.parse(body, 2048) instanceof Map<?, ?> request) || !request.keySet().equals(Set.of("workflowVersionId", "camera"))
                || !(request.get("workflowVersionId") instanceof String version)) throw invalid();
            return Map.of("workflowVersionId", UUID.fromString(version).toString(), "camera", ManagedDeviceService.cameraReference(request.get("camera")));
        } catch (IllegalArgumentException e) { throw invalid(); }
    }
    private static ControlPlaneException invalid() {
        return new ControlPlaneException(400, "INVALID_SCENARIO_REQUEST", "UUID Idempotency-Key, workflowVersionId와 camera {id, uid}를 입력하세요.");
    }
    private static ControlPlaneException notFound() {
        return new ControlPlaneException(404, "SCENARIO_SESSION_NOT_FOUND", "영상 점검 세션을 찾을 수 없습니다.");
    }
    private static ControlPlaneException frameNotFound() {
        return new ControlPlaneException(404, "FRAME_NOT_FOUND", "확정된 영상 프레임이 없습니다.");
    }
}
