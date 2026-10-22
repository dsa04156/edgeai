package io.edgeai.app.service;

import io.edgeai.app.dto.ScenarioCapabilityResponse;
import io.edgeai.app.dto.ScenarioImageResultResponse;
import io.edgeai.app.dto.ScenarioRunResponse;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.ImageComparisonSnapshot;
import io.edgeai.app.support.ImageComparisonTargets;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.repository.Creation;
import io.edgeai.domain.repository.ImageComparisonRepository;
import io.edgeai.domain.scenario.ImageComparisonRun;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** GPU/NPU comparison over the fixed dataset: one active run, executed by ImageComparisonExecutor. */
@Service
public class ImageComparisonService {
    public static final String ID = "gpu-npu-image-comparison";
    public static final int MAX_IMAGES = 100;
    private static final JsonDocuments JSON = new JsonDocuments();
    private final ImageComparisonRepository repository;
    private final ImageComparisonExecutor executor;
    private final ImageComparisonTargets targets;
    private final ScenarioCapabilityResponse.Dataset dataset;
    private final Duration lease;

    public ImageComparisonService(ImageComparisonRepository repository, ImageComparisonExecutor executor,
            ObjectProvider<ImageComparisonTargets> targets, @Value("${edgeai.scenario.lease:60s}") Duration lease,
            @Value("${edgeai.scenario.dataset.name}") String name, @Value("${edgeai.scenario.dataset.version}") String version,
            @Value("${edgeai.scenario.dataset.preprocessing}") String preprocessing,
            @Value("${edgeai.scenario.dataset.label-source:}") String labelSource,
            @Value("${edgeai.scenario.dataset.manifest-sha256}") String manifestSha256,
            @Value("${edgeai.scenario.dataset.inputs-sha256}") String inputsSha256,
            @Value("${edgeai.scenario.dataset.image-count}") int imageCount) {
        this.repository = repository;
        this.executor = executor;
        this.targets = targets.getIfAvailable();
        this.lease = lease;
        // Labels are scored only when their source is documented in configuration.
        dataset = new ScenarioCapabilityResponse.Dataset(name, version, preprocessing, labelSource.isBlank() ? null : labelSource,
            manifestSha256, inputsSha256, imageCount);
    }

    public ScenarioCapabilityResponse capability() {
        var active = repository.active().orElse(null);
        if (targets == null) return new ScenarioCapabilityResponse(ID, false, MAX_IMAGES, dataset, List.of(), active);
        // Both health probes run together, so the page waits at most one probe timeout.
        var statuses = targets.targets().stream().map(t -> CompletableFuture.supplyAsync(() -> targets.status(t))).toList();
        var items = new ArrayList<ScenarioCapabilityResponse.Target>();
        for (int i = 0; i < statuses.size(); i++) {
            var target = targets.targets().get(i);
            var status = statuses.get(i).join();
            var identity = status.identity();
            items.add(new ScenarioCapabilityResponse.Target(target.id(), target.modelName(), target.modelSha256(), target.provider(),
                status.state(), identity == null ? null : new ScenarioCapabilityResponse.Reported(identity.nodeName(), identity.deviceName(),
                    identity.provider(), identity.precision(), identity.runtime(), identity.modelSha256())));
        }
        return new ScenarioCapabilityResponse(ID, true, MAX_IMAGES, dataset, items, active);
    }

    public Creation<ScenarioRunResponse> start(String key, String body) {
        UUID idempotency = UUID.fromString(key);
        if (!key.equals(idempotency.toString())) throw invalid();
        int count = count(body);
        if (targets == null) throw new ControlPlaneException(503, "SCENARIO_DISABLED", "GPU/NPU 비교 런타임이 설정되지 않았습니다.");
        var id = UUID.randomUUID();
        if (repository.create(id, idempotency, count, executor.owner(), lease)) {
            executor.submit(id);
            return new Creation<>(detail(id), true);
        }
        var existing = repository.byIdempotencyKey(idempotency);
        if (existing.isPresent()) {
            if (existing.get().requestedCount() != count)
                throw new ControlPlaneException(409, "IDEMPOTENCY_CONFLICT", "같은 Idempotency-Key에 다른 이미지 수가 있습니다.");
            return new Creation<>(detail(existing.get().id()), false);
        }
        throw new ControlPlaneException(409, "SCENARIO_RUN_ACTIVE", "이미 진행 중인 비교 실행이 있습니다. 끝나거나 중지된 뒤 시작하세요.");
    }

    /** Idempotent: a finished run is returned unchanged. */
    public ScenarioRunResponse stop(UUID id) {
        find(id);
        repository.stop(id);
        return detail(id);
    }

    public List<ImageComparisonRun> list(int limit, int offset) {
        WorkflowService.page(limit, offset);
        return repository.list(limit + 1, offset);
    }

    public ScenarioRunResponse detail(UUID id) {
        var run = find(id);
        var snapshot = run.snapshotJson() == null ? null : ImageComparisonSnapshot.parse(run.snapshotJson());
        return response(run, snapshot, snapshot == null ? null : comparison(run, snapshot, repository.results(id, 0, MAX_IMAGES)));
    }

    public record ResultPage(List<ScenarioImageResultResponse> items, Integer nextOffset) {}

    /** Pages over dispatched images; nextOffset is set only when a later dispatched image exists. */
    public ResultPage results(UUID id, int limit, int offset) {
        if (limit < 1 || limit > MAX_IMAGES || offset < 0 || offset > MAX_IMAGES) throw invalid();
        var run = find(id);
        if (run.snapshotJson() == null) return new ResultPage(List.of(), null);
        var snapshot = ImageComparisonSnapshot.parse(run.snapshotJson());
        var byImage = new TreeMap<Integer, Map<String, ImageComparisonRun.Result>>();
        for (var result : repository.results(id, offset, limit))
            byImage.computeIfAbsent(result.imageIndex(), k -> new HashMap<>()).put(result.target(), result);
        var items = new ArrayList<ScenarioImageResultResponse>();
        for (int index = offset; index < Math.min(offset + limit, run.dispatchedCount()); index++) {
            var sample = snapshot.samples().get(index);
            var recorded = byImage.getOrDefault(index, Map.of());
            var gpu = outcome(recorded.get("GPU")); var npu = outcome(recorded.get("NPU"));
            Boolean agreement = gpu != null && npu != null && gpu.top1() != null && npu.top1() != null ? gpu.top1().equals(npu.top1()) : null;
            items.add(new ScenarioImageResultResponse(index, sample.path(), sample.label(), sample.referenceTop1(), sample.tensorSha256(),
                gpu, npu, agreement));
        }
        return new ResultPage(items, offset + limit < run.dispatchedCount() ? offset + limit : null);
    }

    public static ScenarioRunResponse summary(ImageComparisonRun run) { return response(run, null, null); }

    private ImageComparisonRun find(UUID id) {
        return repository.find(id).orElseThrow(() -> new ControlPlaneException(404, "SCENARIO_RUN_NOT_FOUND", "비교 실행을 찾을 수 없습니다."));
    }

    private static int count(String body) {
        if (!(JSON.parse(body, 1024) instanceof Map<?, ?> request) || !request.keySet().equals(Set.of("imageCount"))
            || !(request.get("imageCount") instanceof BigInteger value) || value.compareTo(BigInteger.ONE) < 0
            || value.compareTo(BigInteger.valueOf(MAX_IMAGES)) > 0) throw invalid();
        return value.intValue();
    }

    private static ScenarioRunResponse response(ImageComparisonRun run, ImageComparisonSnapshot snapshot, ScenarioRunResponse.Comparison comparison) {
        ScenarioRunResponse.Snapshot view = null;
        if (snapshot != null) {
            var d = snapshot.dataset();
            view = new ScenarioRunResponse.Snapshot(new ScenarioRunResponse.Dataset(d.kind(), d.preprocessing(), d.manifestSha256(),
                d.inputsSha256(), d.imageCount()), snapshot.targets().stream().map(t -> new ScenarioRunResponse.Target(t.id(), t.modelName(),
                    t.modelSha256(), t.nodeName(), t.deviceName(), t.provider(), t.precision(), t.runtime())).toList());
        }
        return new ScenarioRunResponse(run.id(), run.state(), run.requestedCount(), run.dispatchedCount(), run.createdAt(), run.startedAt(),
            run.stopRequestedAt(), run.finishedAt(), run.failureCode(), run.failureMessage(), view, comparison);
    }

    private ScenarioRunResponse.Comparison comparison(ImageComparisonRun run, ImageComparisonSnapshot snapshot, List<ImageComparisonRun.Result> results) {
        boolean trusted = dataset.labelSource() != null;
        var top1 = new HashMap<String, Map<Integer, Integer>>();
        for (var result : results)
            if ("SUCCEEDED".equals(result.status()))
                top1.computeIfAbsent(result.target(), k -> new HashMap<>()).put(result.imageIndex(), outcome(result).top1());
        var gpu = top1.getOrDefault("GPU", Map.of()); var npu = top1.getOrDefault("NPU", Map.of());
        var paired = gpu.keySet().stream().filter(npu::containsKey).sorted().toList();
        int agreement = (int) paired.stream().filter(i -> gpu.get(i).equals(npu.get(i))).count();
        var summaries = new ArrayList<ScenarioRunResponse.TargetSummary>();
        for (var target : ImageComparisonRun.TARGETS) {
            var own = results.stream().filter(r -> r.target().equals(target)).toList();
            var succeeded = own.stream().filter(r -> "SUCCEEDED".equals(r.status())).toList();
            var latencies = succeeded.stream().mapToDouble(ImageComparisonRun.Result::inferenceMs).sorted().toArray();
            double inferenceSeconds = Arrays.stream(latencies).sum() / 1000;
            double successRoundTripSeconds = succeeded.stream().mapToDouble(ImageComparisonRun.Result::roundTripMs).sum() / 1000;
            // Failed and timed-out calls also spent wall time on this target.
            double callWallSeconds = own.stream().mapToDouble(ImageComparisonRun.Result::roundTripMs).sum() / 1000;
            var predictions = top1.getOrDefault(target, Map.of());
            Integer correct = trusted ? (int) paired.stream().filter(i -> predictions.get(i) == snapshot.samples().get(i).label()).count() : null;
            summaries.add(new ScenarioRunResponse.TargetSummary(target, own.size(), succeeded.size(), own.size() - succeeded.size(),
                latencies.length == 0 ? null : inferenceSeconds * 1000 / latencies.length, percentile(latencies, 0.5), percentile(latencies, 0.95),
                succeeded.isEmpty() ? null : successRoundTripSeconds * 1000 / succeeded.size(),
                inferenceSeconds > 0 ? succeeded.size() / inferenceSeconds : null, callWallSeconds,
                callWallSeconds > 0 ? succeeded.size() / callWallSeconds : null, correct));
        }
        Double runWall = run.startedAt() == null ? null
            : Duration.between(run.startedAt(), run.finishedAt() == null ? Instant.now() : run.finishedAt()).toNanos() / 1e9;
        return new ScenarioRunResponse.Comparison(summaries, paired.size(), agreement, trusted, runWall,
            runWall == null || runWall <= 0 ? null : paired.size() / runWall);
    }

    /** Linear interpolation between closest ranks, as PostgreSQL percentile_cont. */
    private static Double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return null;
        double position = p * (sorted.length - 1);
        int lower = (int) Math.floor(position), upper = (int) Math.ceil(position);
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower);
    }

    private static ScenarioImageResultResponse.Outcome outcome(ImageComparisonRun.Result result) {
        if (result == null) return null;
        if (!"SUCCEEDED".equals(result.status()))
            return new ScenarioImageResultResponse.Outcome(result.status(), null, List.of(), null, result.roundTripMs(), result.failureCode());
        var topK = new ArrayList<ScenarioImageResultResponse.Prediction>();
        for (var item : (List<?>) JSON.decode(result.topKJson())) {
            var entry = (Map<?, ?>) item;
            topK.add(new ScenarioImageResultResponse.Prediction(((Number) entry.get("classIndex")).intValue(), ((Number) entry.get("score")).doubleValue()));
        }
        return new ScenarioImageResultResponse.Outcome(result.status(), topK.getFirst().classIndex(), topK, result.inferenceMs(),
            result.roundTripMs(), null);
    }

    private static ControlPlaneException invalid() {
        return new ControlPlaneException(400, "INVALID_SCENARIO_REQUEST", "UUID Idempotency-Key와 1~100 사이의 imageCount를 입력하세요.");
    }
}
