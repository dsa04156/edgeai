package io.edgeai.app.service;

import io.edgeai.app.support.ImageComparisonSnapshot;
import io.edgeai.app.support.ImageComparisonTargets;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.repository.ImageComparisonRepository;
import io.edgeai.domain.scenario.ClassificationRuntime;
import io.edgeai.domain.scenario.ImageComparisonRun;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs comparison batches in this process. Each image is claimed with a conditional update before any runtime
 * call, so an accepted Stop prevents every later dispatch while the image in flight finishes. Leases are renewed
 * in the database; a run whose owner disappears is marked INTERRUPTED by any instance and never replayed.
 */
@Component
public class ImageComparisonExecutor {
    private static final JsonDocuments JSON = new JsonDocuments();
    private static final int TOP_K = 5;
    private final UUID owner = UUID.randomUUID();
    private final ImageComparisonRepository repository;
    private final ImageComparisonTargets targets;
    private final Duration lease;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService batches = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("image-comparison").daemon().factory());
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService leases = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("image-comparison-lease").daemon().factory());

    public ImageComparisonExecutor(ImageComparisonRepository repository, ObjectProvider<ImageComparisonTargets> targets,
            @Value("${edgeai.scenario.lease:60s}") Duration lease, @Value("${edgeai.scenario.heartbeat:5s}") Duration heartbeat,
            @Value("${edgeai.recovery.inspect-only:false}") boolean inspection) {
        this.repository = repository;
        this.targets = targets.getIfAvailable();
        this.lease = lease;
        if (heartbeat.compareTo(lease) >= 0) throw new IllegalStateException("Scenario heartbeat must be shorter than the lease");
        // Inspection mode never writes; otherwise sweep immediately so a restarted process reports lost runs.
        if (!inspection) leases.scheduleWithFixedDelay(this::heartbeat, 0, heartbeat.toMillis(), TimeUnit.MILLISECONDS);
    }

    public UUID owner() { return owner; }

    public void submit(UUID id) {
        running.add(id);
        try { batches.execute(() -> execute(id)); }
        catch (RejectedExecutionException e) { running.remove(id); repository.interruptOwned(owner, "PROCESS_STOPPED"); }
    }

    private void heartbeat() {
        try {
            if (!running.isEmpty()) repository.renew(owner, Set.copyOf(running), lease);
            int lost = repository.interruptExpired("OWNER_LOST");
            if (lost > 0) LoggerFactory.getLogger(ImageComparisonExecutor.class).warn("Marked {} image comparison run(s) INTERRUPTED after lease expiry", lost);
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(ImageComparisonExecutor.class).warn("Image comparison lease maintenance deferred ({})", error.getClass().getSimpleName());
        }
    }

    @PreDestroy void shutdown() {
        leases.shutdownNow();
        // Record INTERRUPTED before interrupting the batch thread, so the interrupt cannot be recorded as FAILED.
        try { repository.interruptOwned(owner, "PROCESS_STOPPED"); }
        catch (RuntimeException error) { LoggerFactory.getLogger(ImageComparisonExecutor.class).warn("Image comparison runs left to lease expiry ({})", error.getClass().getSimpleName()); }
        batches.shutdownNow();
        calls.shutdownNow();
    }

    private void execute(UUID id) {
        try {
            var run = repository.find(id).orElseThrow();
            var snapshot = prepare(id, run.requestedCount());
            if (snapshot == null || !repository.begin(id, owner, snapshot.json())) return;
            for (int index = 0; index < run.requestedCount(); index++) {
                if (!repository.claim(id, owner, index, lease)) break;
                var results = dispatch(snapshot, index);
                if (!repository.record(id, owner, results)) return;
                var failed = results.stream().filter(r -> "FAILED".equals(r.status())).toList();
                if (!failed.isEmpty()) {
                    var first = failed.getFirst();
                    repository.fail(id, owner, "TARGET_FAILED", first.target() + " 이미지 " + index + ": " + first.failureCode());
                    return;
                }
            }
            repository.complete(id, owner);
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(ImageComparisonExecutor.class).warn("Image comparison run failed ({})", error.getClass().getSimpleName());
            try { repository.fail(id, owner, "INTERNAL_ERROR", "비교 실행 중 서버 오류가 발생했습니다."); } catch (RuntimeException ignored) {}
        } finally {
            running.remove(id);
        }
    }

    /** Verifies both runtimes and the dataset before any image is dispatched; null after recording FAILED. */
    private ImageComparisonSnapshot prepare(UUID id, int count) {
        if (targets == null) { repository.fail(id, owner, "SCENARIO_DISABLED", "비교 런타임이 설정되지 않았습니다."); return null; }
        var reported = new ArrayList<ImageComparisonSnapshot.Target>();
        for (var target : targets.targets()) {
            var status = targets.status(target);
            if (!status.ready()) {
                repository.fail(id, owner, "TARGET_NOT_READY", target.id() + " 런타임 상태 " + status.state());
                return null;
            }
            var identity = status.identity();
            reported.add(new ImageComparisonSnapshot.Target(target.id(), target.modelName(), identity.modelSha256(), identity.nodeName(),
                identity.deviceName(), identity.provider(), identity.precision(), identity.runtime()));
        }
        ImageComparisonSnapshot.Dataset dataset = null;
        var samples = new ArrayList<ImageComparisonSnapshot.Sample>();
        for (var target : targets.targets()) {
            byte[] manifest;
            try { manifest = target.runtime().manifest(); }
            catch (ClassificationRuntime.Failure failure) {
                repository.fail(id, owner, "TARGET_NOT_READY", target.id() + " 데이터셋 manifest 조회 실패");
                return null;
            }
            // Equal digests of the exact bytes mean both runtimes hold the same ordered dataset rows.
            if (!targets.manifestSha256().equals(sha256(manifest))) {
                repository.fail(id, owner, "DATASET_MISMATCH", target.id() + " 데이터셋 manifest SHA-256 불일치");
                return null;
            }
            if (dataset == null) {
                try {
                    if (JSON.parse(new String(manifest, StandardCharsets.UTF_8), 1 << 20) instanceof Map<?, ?> document)
                        dataset = ImageComparisonSnapshot.dataset(document, targets.manifestSha256(), targets.inputsSha256(), samples, count);
                } catch (RuntimeException ignored) {}
                if (dataset == null) {
                    repository.fail(id, owner, "DATASET_MISMATCH", "데이터셋 manifest 형식이 올바르지 않거나 요청 수보다 작습니다.");
                    return null;
                }
            }
        }
        return new ImageComparisonSnapshot(dataset, reported, samples);
    }

    private List<ImageComparisonRun.Result> dispatch(ImageComparisonSnapshot snapshot, int index) {
        var sample = snapshot.samples().get(index);
        var pending = snapshot.targets().stream().map(target -> calls.submit(() -> call(target, sample))).toList();
        var results = new ArrayList<ImageComparisonRun.Result>();
        for (var future : pending) {
            try { results.add(future.get()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
            catch (ExecutionException e) { throw new IllegalStateException(e.getCause()); }
        }
        return results;
    }

    private ImageComparisonRun.Result call(ImageComparisonSnapshot.Target target, ImageComparisonSnapshot.Sample sample) {
        var runtime = targets.targets().stream().filter(t -> t.id().equals(target.id())).findFirst().orElseThrow().runtime();
        long started = System.nanoTime();
        Map<?, ?> document;
        try { document = runtime.classify(sample.index(), targets.manifestSha256()); }
        catch (ClassificationRuntime.Failure failure) { return failed(target, sample, started, "RUNTIME_" + failure.reason().name()); }
        double roundTripMs = (System.nanoTime() - started) / 1e6;
        if (!Integer.valueOf(sample.index()).equals(integer(document.get("index")))
            || !targets.manifestSha256().equals(document.get("manifestSha256")) || !target.modelSha256().equals(document.get("modelSha256"))
            || !target.nodeName().equals(document.get("nodeName")) || !target.deviceName().equals(document.get("deviceName"))
            || !target.provider().equals(document.get("provider")))
            return failed(target, sample, started, "IDENTITY_MISMATCH");
        // The same ordered input bytes must reach both targets.
        if (!sample.tensorSha256().equals(document.get("inputSha256"))) return failed(target, sample, started, "INPUT_MISMATCH");
        Double inferenceMs = number(document.get("inferenceMs"));
        if (inferenceMs == null || inferenceMs < 0 || inferenceMs > roundTripMs + 1) return failed(target, sample, started, "INVALID_RESPONSE");
        if (!(document.get("topK") instanceof List<?> items) || items.isEmpty() || items.size() > TOP_K)
            return failed(target, sample, started, "INVALID_RESPONSE");
        var topK = new ArrayList<Map<String, Object>>();
        Double previous = null;
        for (var item : items) {
            if (!(item instanceof Map<?, ?> entry) || entry.size() != 2) return failed(target, sample, started, "INVALID_RESPONSE");
            Integer classIndex = ImageComparisonSnapshot.classIndex(entry.get("classIndex"));
            Double score = number(entry.get("score"));
            if (classIndex == null || score == null || (previous != null && score > previous)
                || topK.stream().anyMatch(other -> other.get("classIndex").equals(classIndex)))
                return failed(target, sample, started, "INVALID_RESPONSE");
            previous = score;
            topK.add(Map.of("classIndex", classIndex, "score", score));
        }
        return new ImageComparisonRun.Result(sample.index(), target.id(), "SUCCEEDED", JSON.canonical(topK), sample.tensorSha256(),
            inferenceMs, roundTripMs, null);
    }

    private static ImageComparisonRun.Result failed(ImageComparisonSnapshot.Target target, ImageComparisonSnapshot.Sample sample, long started, String code) {
        return new ImageComparisonRun.Result(sample.index(), target.id(), "FAILED", null, null, null, (System.nanoTime() - started) / 1e6, code);
    }
    private static Integer integer(Object value) {
        return value instanceof BigInteger number && number.bitLength() < 32 ? number.intValue() : null;
    }
    private static Double number(Object value) {
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)) return number;
        }
        return null;
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
