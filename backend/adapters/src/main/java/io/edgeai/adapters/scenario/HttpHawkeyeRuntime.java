package io.edgeai.adapters.scenario;

import io.edgeai.domain.scenario.ClassificationRuntime.Failure;
import io.edgeai.domain.scenario.HawkeyeRuntime;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.scenario.ClassificationRuntime.Failure.Reason.*;

/** JSON over HTTP/1.1 to GET /health, GET /samples/{id} and POST /classify of the configured Hawkeye adapter. */
public final class HttpHawkeyeRuntime implements HawkeyeRuntime, AutoCloseable {
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
    // A run is asynchronous, so a cold model load may take longer than a browser request window.
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(300);
    private static final int LIMIT = 1 << 20;
    private final URI base;
    private final Duration timeout;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public HttpHawkeyeRuntime(String url, Duration timeout) {
        base = URI.create(url.replaceAll("/+$", ""));
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
            || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Hawkeye runtime URL must be an HTTP(S) base URL without credentials");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0)
            throw new IllegalArgumentException("Hawkeye runtime timeout must be positive and at most 300 seconds");
        this.timeout = timeout;
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(HEALTH_TIMEOUT).build();
    }

    @Override public Map<?, ?> health() {
        var response = exchange(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(HEALTH_TIMEOUT).GET().build(), HEALTH_TIMEOUT);
        // 503 carries the NOT_READY identity document.
        if (response.statusCode() != 200 && response.statusCode() != 503) throw new Failure(UNAVAILABLE);
        return document(response.body());
    }

    @Override public byte[] sample(String sampleId) {
        var response = exchange(HttpRequest.newBuilder(URI.create(base + "/samples/" + sampleId)).timeout(HEALTH_TIMEOUT).GET().build(), HEALTH_TIMEOUT);
        if (response.statusCode() != 200) throw new Failure(response.statusCode() == 404 ? REJECTED : UNAVAILABLE);
        return response.body();
    }

    @Override public Map<?, ?> classify(String sampleId, String samplesSha256) {
        byte[] payload = json.writeValueAsBytes(Map.of("sampleId", sampleId, "samplesSha256", samplesSha256));
        var response = exchange(HttpRequest.newBuilder(URI.create(base + "/classify")).timeout(timeout).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), timeout);
        return switch (response.statusCode()) {
            case 200 -> document(response.body());
            case 400, 409 -> throw new Failure(REJECTED);
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
