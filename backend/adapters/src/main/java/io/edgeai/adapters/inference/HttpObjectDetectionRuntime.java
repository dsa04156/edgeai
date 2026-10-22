package io.edgeai.adapters.inference;

import io.edgeai.domain.inference.ObjectDetectionRuntime;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.inference.ObjectDetectionRuntime.Failure.Reason.*;

/** JSON over HTTP/1.1 to GET /health and POST /predict of one configured base URL. */
public final class HttpObjectDetectionRuntime implements ObjectDetectionRuntime, AutoCloseable {
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(20);
    private static final int LIMIT = 1 << 20;
    private final URI health, predict;
    private final Duration timeout;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public HttpObjectDetectionRuntime(String url, Duration timeout) {
        URI base = URI.create(url.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
            || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Inference runtime URL must be an HTTP(S) base URL without credentials");
        // The published API promises at most 20 seconds of server wait.
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0)
            throw new IllegalArgumentException("Inference timeout must be positive and at most 20 seconds");
        health = URI.create(base + "/health");
        predict = URI.create(base + "/predict");
        this.timeout = timeout;
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(HEALTH_TIMEOUT).build();
    }

    @Override public Map<?, ?> health() {
        var response = exchange(HttpRequest.newBuilder(health).timeout(HEALTH_TIMEOUT).GET().build(), HEALTH_TIMEOUT);
        if (response.statusCode() != 200) throw new Failure(UNAVAILABLE);
        return document(response.body());
    }

    @Override public Map<?, ?> predict(String imageBase64) {
        byte[] payload = json.writeValueAsBytes(Map.of("imageBase64", imageBase64));
        var response = exchange(HttpRequest.newBuilder(predict).timeout(timeout).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), timeout);
        return switch (response.statusCode()) {
            case 200 -> document(response.body());
            case 400, 415 -> throw new Failure(REJECTED);
            case 413 -> throw new Failure(TOO_LARGE);
            case 429, 503 -> throw new Failure(BUSY);
            default -> throw new Failure(INVALID_RESPONSE);
        };
    }

    // The bounded body completes the future, so one deadline covers headers and body.
    private HttpResponse<byte[]> exchange(HttpRequest request, Duration deadline) {
        var pending = client.sendAsync(request, ignored -> new LimitedBody());
        try { return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(UNAVAILABLE); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Failure failure) throw failure;
            throw new Failure(UNAVAILABLE);
        }
        catch (TimeoutException e) { throw new Failure(UNAVAILABLE); }
        finally { pending.cancel(true); }
    }

    private Map<?, ?> document(byte[] body) {
        try {
            if (json.readValue(body, Object.class) instanceof Map<?, ?> document) return document;
        } catch (RuntimeException ignored) {}
        throw new Failure(INVALID_RESPONSE);
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
            if (size > LIMIT) { subscription.cancel(); delegate.onError(new Failure(INVALID_RESPONSE)); }
            else delegate.onNext(values);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
