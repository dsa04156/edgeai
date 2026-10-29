package io.edgeai.adapters.profiling;

import io.edgeai.domain.resourceprofile.KrrRecommendation;
import io.edgeai.domain.resourceprofile.RecommendationSource;
import io.edgeai.domain.resourceprofile.WorkloadTarget;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the recommendation-only profiling bridge (profiling-bridge/) that runs the pinned KRR binary against the
 * Prometheus configured on the bridge. The request carries only a validated workload identity and history hours.
 */
public final class KrrBridgeClient implements RecommendationSource, AutoCloseable {
    private static final long MAX_BODY = 16L * 1024 * 1024;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient client;
    private final JsonMapper json = new JsonMapper();

    public KrrBridgeClient(String url, Duration timeout) {
        URI origin = URI.create(url.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
            || origin.getQuery() != null || origin.getFragment() != null || !origin.getPath().isEmpty())
            throw new IllegalArgumentException("KRR bridge URL must be an HTTP(S) origin without credentials");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("KRR bridge timeout must be 1s-10m");
        endpoint = origin.resolve("/v1/recommendations");
        this.timeout = timeout;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public KrrRecommendation recommend(WorkloadTarget target, int historyHours) {
        if (!target.kind().recommendable()) throw new IllegalArgumentException("KRR does not recommend bare Pods");
        var request = new LinkedHashMap<String, Object>();
        request.put("namespace", target.namespace()); request.put("kind", target.kind().name());
        request.put("name", target.name()); request.put("container", target.container()); request.put("historyHours", historyHours);
        var http = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
        byte[] body;
        try {
            var response = client.send(http, ignored -> new LimitedBody());
            if (response.statusCode() != 200)
                return KrrRecommendation.failed(KrrRecommendation.Status.UNAVAILABLE, "KRR bridge가 HTTP " + response.statusCode() + "로 응답했습니다.", historyHours, Map.of("tool", "krr"));
            body = response.body();
        } catch (HttpTimeoutException timedOut) {
            return KrrRecommendation.failed(KrrRecommendation.Status.UNAVAILABLE, "KRR 실행이 제한 시간 안에 끝나지 않았습니다.", historyHours, Map.of("tool", "krr"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("KRR bridge interrupted");
        } catch (IOException unavailable) {
            return KrrRecommendation.failed(KrrRecommendation.Status.UNAVAILABLE, "KRR bridge에 연결할 수 없습니다.", historyHours, Map.of("tool", "krr"));
        }
        return decode(body, target, historyHours);
    }

    /** Public for deterministic tests with recorded bridge responses. */
    public KrrRecommendation decode(byte[] body, WorkloadTarget target, int historyHours) {
        JsonNode root;
        try { root = json.readTree(body); }
        catch (RuntimeException invalid) {
            return KrrRecommendation.failed(KrrRecommendation.Status.INVALID_REPORT, "KRR bridge 응답이 JSON이 아닙니다.", historyHours, Map.of("tool", "krr"));
        }
        var provenance = new TreeMap<String, Object>();
        provenance.put("version", root.path("krrVersion").asText(""));
        provenance.put("bridge", root.path("bridgeVersion").asText(""));
        provenance.put("image", root.path("krrImage").asText(""));
        provenance.put("requestedHistoryHours", historyHours);
        provenance.put("startedAt", root.path("startedAt").asText(""));
        provenance.put("finishedAt", root.path("finishedAt").asText(""));
        provenance.put("reportSha256", root.path("reportSha256").asText(""));
        var arguments = new ArrayList<String>();
        for (var argument : root.path("arguments")) arguments.add(argument.asText());
        provenance.put("arguments", arguments);
        String status = root.path("status").asText();
        if (!status.equals("COMPLETED")) {
            String reason = switch (status) {
                case "TIMEOUT" -> "KRR 실행이 bridge 제한 시간을 넘었습니다.";
                case "FAILED" -> "KRR 실행이 실패했습니다(exit " + root.path("exitCode").asText("?") + ").";
                default -> "KRR bridge 상태를 알 수 없습니다.";
            };
            return KrrRecommendation.failed(KrrRecommendation.Status.UNAVAILABLE, reason, historyHours, provenance);
        }
        Instant generated;
        try { generated = Instant.parse(root.path("finishedAt").asText()); }
        catch (RuntimeException invalid) { return KrrRecommendation.failed(KrrRecommendation.Status.INVALID_REPORT, "KRR 실행 시각이 없습니다.", historyHours, provenance); }
        return KrrReports.parse(root.path("report"), target, generated, provenance);
    }

    @Override public void close() { client.close(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> values) {
            for (var value : values) size += value.remaining();
            if (size > MAX_BODY) { subscription.cancel(); delegate.onError(new IOException("KRR bridge response too large")); }
            else delegate.onNext(values);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
