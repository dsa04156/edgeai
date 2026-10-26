package io.edgeai.app.service;

import io.edgeai.app.config.HawkeyeVideoProperties;
import io.edgeai.app.dto.HawkeyeVideoCapabilityResponse;
import io.edgeai.app.dto.HawkeyeVideoSessionResponse;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeScope;
import io.edgeai.domain.scenario.HawkeyeMotionObserver;
import io.edgeai.domain.scenario.HawkeyeVideoSession;
import io.edgeai.domain.storage.ArtifactFiles;
import io.edgeai.domain.storage.ArtifactStoreUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hawkeye video sessions run one published SERVICE workflow as consecutive bounded BATCH Runs (windows) over one
 * pinned RTSP camera. Each window is an ordinary platform Run; this service only orders them.
 * <ul>
 * <li>FRAMES versions (capture → infer): each window reads its own frames, so frames between two windows are not read.</li>
 * <li>MOTION versions (a {@code snapshot} task): the motion observer service reads the pinned camera continuously for
 * the whole session and keeps its tracker state; each window Run persists the next contiguous part of that
 * observation. This service starts the observer with the session's pinned camera, renews its lease every poll, ends
 * the session when the observer fails, expires or is replaced by another instance (epoch), and stops it at the end.</li>
 * </ul>
 */
@Service
public class HawkeyeVideoService {
    public static final String FRAMES_PORT = "frames";
    public static final String FRAMES_MEDIA_TYPE = "application/x-tar";
    /** A 10 s window of the 24 fps preview is about 240 JPEGs of about 70 KB. */
    static final long MAX_FRAMES_BYTES = 32L * 1024 * 1024;
    /** Verified archives kept for frame reads: playback reads every frame of a window, one download per archive. */
    private static final int CACHED_ARCHIVES = 2;
    /** Task key that marks a continuous-observation (MOTION) workflow version. */
    public static final String SNAPSHOT_TASK = "snapshot";
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
    /** Members of recently read frames archives by their immutable stored identity (object version and digest). */
    private final Map<io.edgeai.domain.storage.VerifiedArtifact, Map<String, byte[]>> archives = new LinkedHashMap<>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<io.edgeai.domain.storage.VerifiedArtifact, Map<String, byte[]>> eldest) { return size() > CACHED_ARCHIVES; }
    };
    private final ScheduledExecutorService scheduler;
    private final Optional<HawkeyeMotionObserver> observer;
    private final Map<UUID, String> modes = new ConcurrentHashMap<>();
    /** Last successful observer renewal per running MOTION session (in-memory; a restart starts from the session record). */
    private final Map<UUID, Instant> renewed = new ConcurrentHashMap<>();

    public HawkeyeVideoService(HawkeyeVideoRepository repository, ExecutionService executions, ExecutionRepository runs,
            RuntimeRepository runtimes, WorkflowRepository workflows, Optional<ArtifactFiles> files, TransactionTemplate transactions,
            HawkeyeVideoProperties properties, RuntimeScope scope, Clock clock, @Value("${edgeai.runtime.enabled:false}") boolean runtimeEnabled,
            @Value("${edgeai.recovery.inspect-only:false}") boolean inspection, Optional<HawkeyeMotionObserver> observer) {
        this.observer = observer;
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
            .map(v -> new HawkeyeVideoCapabilityResponse.Version(v.id(), v.version(), v.createdAt(), mode(v.id()))).toList();
        return new HawkeyeVideoCapabilityResponse(properties.enabled(), properties.enabled() && executes, properties.workflowKey(),
            workflow == null ? null : new HawkeyeVideoCapabilityResponse.Workflow(workflow.id(), workflow.key(), workflow.displayName()),
            versions, properties.maxWindows(), repository.active().orElse(null), observerStatus());
    }

    /** MOTION when the version has a snapshot task (continuous observation), otherwise FRAMES. Versions are immutable. */
    String mode(UUID versionId) {
        return modes.computeIfAbsent(versionId, id -> workflows.definitions(id).stream().anyMatch(d -> d.key().equals(SNAPSHOT_TASK)) ? "MOTION" : "FRAMES");
    }

    private HawkeyeVideoCapabilityResponse.Observer observerStatus() {
        if (observer.isEmpty()) return null;
        try {
            var reply = observer.get().health();
            var health = reply.document();
            String status = reply.status() == 200 && Boolean.TRUE.equals(health.get("ready")) ? "READY" : "NOT_READY";
            return new HawkeyeVideoCapabilityResponse.Observer(status, text(health.get("nodeName")), text(health.get("pod")),
                text(health.get("provider")), health.get("model") instanceof Map<?, ?> model ? model : null, text(health.get("error")));
        } catch (HawkeyeMotionObserver.Unavailable e) {
            return new HawkeyeVideoCapabilityResponse.Observer("UNAVAILABLE", null, null, null, null, null);
        }
    }
    private static String text(Object value) { return value instanceof String s && !s.isEmpty() ? s : null; }

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
        boolean motion = mode(versionId).equals("MOTION");
        if (motion) {
            var status = observerStatus();
            if (status == null) throw new ControlPlaneException(503, "OBSERVER_DISABLED", "연속 관측 서비스가 이 환경에 설정되지 않았습니다.");
            if (!status.status().equals("READY"))
                throw new ControlPlaneException(503, "OBSERVER_NOT_READY", "연속 관측 서비스가 준비되지 않았습니다 (" + status.status() + ").");
        }
        var camera = (Map<?, ?>) request.get("camera");
        var now = clock.instant();
        var session = new HawkeyeVideoSession(UUID.randomUUID(), idempotency, digest, versionId, (String) camera.get("id"),
            UUID.fromString((String) camera.get("uid")), null, properties.maxWindows(), "RUNNING", null, null, null, now, now, null, null, null);
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
        // The observer starts with the camera the first window pinned; its window waits for it.
        if (motion) observe(created);
        return new Creation<>(detail(created), true);
    }

    /** Ends the session, then cancels its unfinished window; no later window is created once this commits. */
    public HawkeyeVideoSessionResponse stop(UUID id, String body) {
        if (!(JSON.parse(body, 64) instanceof Map<?, ?> empty) || !empty.isEmpty()) throw invalid();
        transactions.executeWithoutResult(status -> {
            var session = repository.lock(id).orElseThrow(() -> notFound());
            if (!session.state().equals("RUNNING")) return;
            repository.end(id, "STOPPED", "USER_STOPPED", null, null, clock.instant());
            cancelWindows(id);
        });
        release(id);
        return detail(id);
    }

    private void cancelWindows(UUID id) {
        for (var window : repository.windows(id)) {
            var run = runs.run(window.runId(), true).orElseThrow();
            if (!HawkeyeVideoSession.FINISHED_RUN.contains(run.state()) && !run.state().equals("CANCELLING"))
                executions.cancelRun(run.id(), "{}");
        }
    }

    /**
     * Starts or renews the observer of a running MOTION session. A refused renewal (observer failed, expired, stopped,
     * watching another source) or a different observer instance ends the session as OBSERVER_FAILED and cancels its
     * open window; an unreachable observer does so once the lease has run out without a renewal.
     */
    @SuppressWarnings("unchecked")
    void observe(UUID id) {
        var session = repository.find(id).orElseThrow();
        if (!session.state().equals("RUNNING") || session.cameraSourceJson() == null || !mode(session.workflowVersionId()).equals("MOTION")) return;
        if (observer.isEmpty()) { failObserver(id, "OBSERVER_DISABLED", "연속 관측 서비스가 설정되지 않았습니다."); return; }
        var source = (Map<String, Object>) JSON.decode(session.cameraSourceJson());
        HawkeyeMotionObserver.Reply reply;
        try {
            reply = observer.get().put(id, source, (int) properties.observerLease().toSeconds());
        } catch (HawkeyeMotionObserver.Unavailable e) {
            var last = renewed.getOrDefault(id, session.observerStartedAt() == null ? session.createdAt() : session.observerStartedAt());
            if (clock.instant().isAfter(last.plus(properties.observerLease())))
                failObserver(id, "OBSERVER_UNAVAILABLE", "연속 관측 서비스에 " + properties.observerLease().toSeconds() + "초 넘게 연결하지 못했습니다.");
            return;
        }
        if (reply.status() != 200 && reply.status() != 201) {
            String detail = "";
            try {
                if (observer.get().live(id).document().get("failure") instanceof Map<?, ?> failure) detail = " · " + failure.get("code");
            } catch (HawkeyeMotionObserver.Unavailable ignored) {}
            failObserver(id, reply.error().startsWith("OBSERVER_") ? reply.error() : "OBSERVER_" + reply.error(),
                "연속 관측이 계속되지 않았습니다 (" + reply.error() + detail + ").");
            return;
        }
        String epoch = text(reply.document().get("epoch"));
        if (epoch == null) { failObserver(id, "OBSERVER_INVALID", "연속 관측 서비스 응답 형식이 맞지 않습니다."); return; }
        // A stop that committed while this call was in flight may have sent its DELETE first; the observer this call
        // started or renewed is stopped again instead of reading until its lease ends.
        if (!repository.find(id).orElseThrow().state().equals("RUNNING")) { release(id); return; }
        if (session.observerEpoch() == null) repository.recordObserver(id, epoch, clock.instant());
        else if (!session.observerEpoch().equals(epoch)) {
            // Another observer instance would start a new track history under the same session.
            failObserver(id, "OBSERVER_LOST", "관측 서비스가 다시 시작되어 이전 추적 상태가 사라졌습니다.");
            return;
        }
        renewed.put(id, clock.instant());
    }

    private void failObserver(UUID id, String code, String message) {
        transactions.executeWithoutResult(status -> {
            if (!repository.lock(id).orElseThrow().state().equals("RUNNING")) return;
            repository.end(id, "FAILED", "OBSERVER_FAILED", code, message, clock.instant());
            cancelWindows(id);
        });
        release(id);
    }

    /** Stops the observer of an ended session; its lease expiry stops it anyway when this call does not arrive. */
    private void release(UUID id) {
        renewed.remove(id);
        var session = repository.find(id).orElseThrow();
        if (observer.isEmpty() || session.state().equals("RUNNING") || !mode(session.workflowVersionId()).equals("MOTION")) return;
        try { observer.get().delete(id); } catch (HawkeyeMotionObserver.Unavailable ignored) {}
    }

    /** Latest live observation of a running MOTION session, proxied from its own observer instance. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> live(UUID id) {
        var session = liveSession(id);
        HawkeyeMotionObserver.Reply reply;
        try { reply = observer.get().live(id); }
        catch (HawkeyeMotionObserver.Unavailable e) { throw observerUnavailable(); }
        if (reply.status() == 404) throw observerUnavailable();
        var live = reply.document();
        if (reply.status() != 200 || !id.toString().equals(live.get("sessionId"))) throw observerUnavailable();
        if (session.observerEpoch() != null && !session.observerEpoch().equals(live.get("epoch")))
            throw new ControlPlaneException(409, "OBSERVER_LOST", "관측 서비스가 다시 시작되어 이 세션의 실시간 관측이 없습니다.");
        var identity = live.get("observer") instanceof Map<?, ?> m ? new TreeMap<String, Object>((Map<String, Object>) m) : new TreeMap<String, Object>();
        identity.put("epoch", live.get("epoch"));
        var response = new LinkedHashMap<String, Object>();
        response.put("sessionId", id.toString());
        response.put("state", live.get("state"));
        response.put("failure", live.get("failure"));
        response.put("lastError", live.get("lastError"));
        response.put("observer", identity);
        response.put("stats", live.get("stats"));
        response.put("latest", live.get("latest"));
        return response;
    }

    public byte[] liveFrame(UUID id, long seq) {
        var session = liveSession(id);
        if (seq < 1 || session.observerEpoch() == null) throw frameNotFound();
        byte[] frame;
        try { frame = observer.get().frame(id, session.observerEpoch(), seq).orElseThrow(HawkeyeVideoService::frameNotFound); }
        catch (HawkeyeMotionObserver.Unavailable e) { throw observerUnavailable(); }
        catch (HawkeyeMotionObserver.Superseded e) {
            throw new ControlPlaneException(409, "OBSERVER_LOST", "관측 서비스가 다시 시작되어 이 세션의 실시간 프레임이 없습니다.");
        }
        int n = frame.length;
        if (n < 4 || (frame[0] & 255) != 255 || (frame[1] & 255) != 216 || (frame[n - 2] & 255) != 255 || (frame[n - 1] & 255) != 217)
            throw observerUnavailable();
        return frame;
    }

    private HawkeyeVideoSession liveSession(UUID id) {
        var session = repository.find(id).orElseThrow(HawkeyeVideoService::notFound);
        if (!mode(session.workflowVersionId()).equals("MOTION") || observer.isEmpty())
            throw new ControlPlaneException(409, "SESSION_NOT_LIVE", "연속 관측 버전의 세션만 실시간 관측을 제공합니다.");
        if (!session.state().equals("RUNNING"))
            throw new ControlPlaneException(409, "SESSION_NOT_LIVE", "종료된 세션입니다. 저장된 구간 결과를 확인하세요.");
        return session;
    }
    private static ControlPlaneException observerUnavailable() {
        return new ControlPlaneException(503, "OBSERVER_UNAVAILABLE", "연속 관측 서비스의 응답을 받지 못했습니다.");
    }

    public List<HawkeyeVideoSession> list(int limit, int offset) {
        WorkflowService.page(limit, offset);
        return repository.list(limit + 1, offset);
    }

    public HawkeyeVideoSessionResponse summary(HawkeyeVideoSession session) {
        return HawkeyeVideoSessionResponse.from(session, mode(session.workflowVersionId()), repository.windows(session.id()), false);
    }

    public HawkeyeVideoSessionResponse detail(UUID id) {
        var session = repository.find(id).orElseThrow(HawkeyeVideoService::notFound);
        return HawkeyeVideoSessionResponse.from(session, mode(session.workflowVersionId()), repository.windows(id), true);
    }

    /** Creates the next window or ends the session; a failed or cancelled window ends it rather than being skipped. */
    void advance(UUID id) {
        observe(id);
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
        if (!repository.find(id).orElseThrow().state().equals("RUNNING")) release(id);
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
        var parameters = mode(session.workflowVersionId()).equals("MOTION")
            ? Map.of("observer", Map.of("sessionId", session.id().toString(), "window", seq)) : Map.of();
        var body = JSON.canonical(Map.of("workflowVersionId", session.workflowVersionId().toString(), "parameters", parameters,
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
     * when the Result was committed and again on download; the frame must also match its manifest SHA-256. The members
     * of the last few downloaded archives are kept, so a window's frames are read with one download.
     */
    public byte[] frame(UUID taskId, int seq) {
        var task = runs.task(taskId).orElseThrow(HawkeyeVideoService::frameNotFound);
        if (!repository.windowRun(task.runId())) throw frameNotFound();
        var artifact = runtimes.result(taskId).stream().flatMap(r -> r.outputs().stream()).filter(o -> o.port().equals(FRAMES_PORT))
            .map(o -> o.artifact()).findFirst().orElseThrow(HawkeyeVideoService::frameNotFound);
        if (!FRAMES_MEDIA_TYPE.equals(artifact.mediaType()) || artifact.bytes() > MAX_FRAMES_BYTES) throw frameNotFound();
        var store = files.orElseThrow(ArtifactStoreUnavailableException::new);
        synchronized (archives) {
            var members = archives.get(artifact);
            if (members != null) return HawkeyeFrames.frame(members, seq);
        }
        if (!readers.tryAcquire()) throw new ControlPlaneException(429, "RESULT_PREVIEW_BUSY", "결과 조회가 진행 중입니다. 잠시 후 다시 조회하세요.");
        try {
            var directory = Files.createTempDirectory("edgeai-frames-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var file = directory.resolve("frames.tar");
            try {
                // Concurrent first reads of one archive wait for a single download.
                synchronized (archives) {
                    var members = archives.get(artifact);
                    if (members == null) {
                        store.downloadFile(artifact, file);
                        members = HawkeyeFrames.members(Files.readAllBytes(file));
                        archives.put(artifact, members);
                    }
                    return HawkeyeFrames.frame(members, seq);
                }
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
