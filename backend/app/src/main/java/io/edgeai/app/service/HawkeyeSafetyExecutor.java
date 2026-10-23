package io.edgeai.app.service;

import io.edgeai.app.config.HawkeyeSafetyProperties;
import io.edgeai.app.dto.HawkeyeSafetyRunResponse;
import io.edgeai.app.support.HawkeyeOutput;
import io.edgeai.app.support.HawkeyeSafetyStatus;
import io.edgeai.domain.repository.HawkeyeSafetyRepository;
import io.edgeai.domain.scenario.ClassificationRuntime;
import io.edgeai.domain.scenario.HawkeyeRuntime;
import io.edgeai.domain.scenario.HawkeyeSafetyRun;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs one Hawkeye inference per run in this process, under the same lease rules as the GPU/NPU comparison:
 * the owner renews a database lease, and a run whose owner disappears is marked INTERRUPTED and never replayed.
 */
@Component
public class HawkeyeSafetyExecutor {
    private static final int MAX_OUTPUT_CHARS = 4000;
    private final UUID owner = UUID.randomUUID();
    private final HawkeyeSafetyRepository repository;
    private final HawkeyeRuntime runtime;
    private final HawkeyeSafetyProperties properties;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService runs = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("hawkeye-safety").daemon().factory());
    private final ScheduledExecutorService leases = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("hawkeye-safety-lease").daemon().factory());

    public HawkeyeSafetyExecutor(HawkeyeSafetyRepository repository, ObjectProvider<HawkeyeRuntime> runtime,
            HawkeyeSafetyProperties properties, @Value("${edgeai.recovery.inspect-only:false}") boolean inspection) {
        this.repository = repository;
        this.runtime = runtime.getIfAvailable();
        this.properties = properties;
        if (properties.heartbeat().compareTo(properties.lease()) >= 0)
            throw new IllegalStateException("Hawkeye scenario heartbeat must be shorter than the lease");
        // Inspection mode never writes; otherwise sweep immediately so a restarted process reports lost runs.
        if (!inspection) leases.scheduleWithFixedDelay(this::heartbeat, 0, properties.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
    }

    public UUID owner() { return owner; }

    public void submit(UUID id) {
        running.add(id);
        try { runs.execute(() -> execute(id)); }
        catch (RejectedExecutionException e) { running.remove(id); repository.interruptOwned(owner, "PROCESS_STOPPED"); }
    }

    private void heartbeat() {
        try {
            if (!running.isEmpty()) repository.renew(owner, Set.copyOf(running), properties.lease());
            int lost = repository.interruptExpired("OWNER_LOST");
            if (lost > 0) LoggerFactory.getLogger(HawkeyeSafetyExecutor.class).warn("Marked {} Hawkeye safety run(s) INTERRUPTED after lease expiry", lost);
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(HawkeyeSafetyExecutor.class).warn("Hawkeye safety lease maintenance deferred ({})", error.getClass().getSimpleName());
        }
    }

    @PreDestroy void shutdown() {
        leases.shutdownNow();
        // Record INTERRUPTED before interrupting the run thread, so the interrupt cannot be recorded as FAILED.
        try { repository.interruptOwned(owner, "PROCESS_STOPPED"); }
        catch (RuntimeException error) { LoggerFactory.getLogger(HawkeyeSafetyExecutor.class).warn("Hawkeye safety runs left to lease expiry ({})", error.getClass().getSimpleName()); }
        runs.shutdownNow();
    }

    private void execute(UUID id) {
        try {
            var run = repository.find(id).orElseThrow();
            if (runtime == null) { repository.fail(id, owner, "SCENARIO_DISABLED", "안전 점검 런타임이 설정되지 않았습니다."); return; }
            var status = HawkeyeSafetyStatus.of(runtime, properties);
            if (!status.ready()) { repository.fail(id, owner, "TARGET_NOT_READY", "런타임 상태 " + status.state()); return; }
            var reported = status.reported();
            var target = new HawkeyeSafetyRunResponse.Target(reported.nodeName(), reported.model(), reported.modelDigest(),
                reported.ollamaVersion(), status.promptSha256());
            if (!repository.begin(id, owner, HawkeyeSafetyService.snapshot(target))) return;
            var sample = properties.sample(run.sampleId());
            long started = System.nanoTime();
            Map<?, ?> document;
            try { document = runtime.classify(sample.id(), properties.dataset().samplesSha256()); }
            catch (ClassificationRuntime.Failure failure) {
                repository.fail(id, owner, "RUNTIME_" + failure.reason().name(), "Hawkeye 런타임 호출 실패");
                return;
            }
            double roundTripMs = (System.nanoTime() - started) / 1e6;
            if (!sample.id().equals(document.get("sampleId")) || !target.nodeName().equals(document.get("nodeName"))
                || !target.model().equals(document.get("model")) || !target.modelDigest().equals(document.get("modelDigest"))
                || !target.modelDigest().equals(document.get("loadedDigest"))) {
                repository.fail(id, owner, "IDENTITY_MISMATCH", "응답의 노드·모델이 시작 시 확인한 값과 다릅니다.");
                return;
            }
            // The exact pinned bytes must have reached the model.
            if (!sample.sha256().equals(document.get("inputSha256"))) {
                repository.fail(id, owner, "INPUT_MISMATCH", "모델에 전달된 샘플 SHA-256이 고정값과 다릅니다.");
                return;
            }
            var result = result(document, sample.sha256(), roundTripMs);
            if (result == null) { repository.fail(id, owner, "INVALID_RESPONSE", "런타임 응답 형식이 올바르지 않습니다."); return; }
            if (result.category() == null)
                repository.finish(id, owner, result, "UNPARSEABLE_OUTPUT", "모델 출력 첫 줄에서 범주 하나를 읽지 못했습니다. 안전으로 간주하지 않습니다.");
            else repository.finish(id, owner, result, null, null);
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(HawkeyeSafetyExecutor.class).warn("Hawkeye safety run failed ({})", error.getClass().getSimpleName());
            try { repository.fail(id, owner, "INTERNAL_ERROR", "안전 점검 실행 중 서버 오류가 발생했습니다."); } catch (RuntimeException ignored) {}
        } finally {
            running.remove(id);
        }
    }

    /** The model's answer with timings and placement; null when the document is malformed. */
    private static HawkeyeSafetyRun.Result result(Map<?, ?> document, String inputSha256, double roundTripMs) {
        if (!(document.get("content") instanceof String content) || content.length() > MAX_OUTPUT_CHARS
            || !(document.get("placement") instanceof Map<?, ?> placement)) return null;
        String processor = HawkeyeSafetyStatus.text(placement.get("processor"));
        Long size = HawkeyeSafetyStatus.bytes(placement.get("sizeBytes")), vram = HawkeyeSafetyStatus.bytes(placement.get("sizeVramBytes"));
        Double total = number(document.get("totalMs")), load = number(document.get("loadMs"));
        if (processor == null || size == null || vram == null || vram > size || total == null || total < 0 || total > roundTripMs + 1
            || load == null || load < 0 || load > total) return null;
        var doneReason = document.get("doneReason") == null ? null : HawkeyeSafetyStatus.text(document.get("doneReason"));
        var parsed = content.isBlank() ? null : HawkeyeOutput.parse(content);
        return new HawkeyeSafetyRun.Result(parsed == null ? null : parsed.category(), parsed == null ? null : parsed.explanation(), content,
            doneReason, inputSha256, processor, size, vram, total, load, roundTripMs, count(document.get("promptEvalCount")),
            count(document.get("evalCount")));
    }
    private static Integer count(Object value) {
        return value instanceof BigInteger number && number.signum() >= 0 && number.bitLength() < 32 ? number.intValue() : null;
    }
    private static Double number(Object value) {
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)) return number;
        }
        return null;
    }
}
