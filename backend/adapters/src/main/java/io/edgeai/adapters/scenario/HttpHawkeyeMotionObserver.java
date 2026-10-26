package io.edgeai.adapters.scenario;

import io.edgeai.domain.scenario.HawkeyeMotionObserver;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** JSON over HTTP/1.1 to the configured motion observer service; bodies are bounded and redirects are not followed. */
public final class HttpHawkeyeMotionObserver implements HawkeyeMotionObserver, AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int LIMIT = 2 << 20;
    private final URI base;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public HttpHawkeyeMotionObserver(String url) {
        base = URI.create(url.replaceAll("/+$", ""));
        if (!"http".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
            || base.getFragment() != null || !base.getPath().isEmpty())
            throw new IllegalArgumentException("Hawkeye motion observer URL must be an HTTP base URL without path or credentials");
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
    }

    @Override public Reply health() { return document(send(request("/health").GET())); }
    @Override public Reply put(UUID sessionId, Map<String, Object> source, int leaseSeconds) {
        byte[] body = json.writeValueAsBytes(Map.of("source", source, "leaseSeconds", leaseSeconds));
        return document(send(request("/observers/" + sessionId).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofByteArray(body))));
    }
    @Override public Reply delete(UUID sessionId) { return document(send(request("/observers/" + sessionId).DELETE())); }
    @Override public Reply live(UUID sessionId) { return document(send(request("/observers/" + sessionId + "/live").GET())); }
    @Override public Optional<byte[]> frame(UUID sessionId, String epoch, long seq) {
        if (!epoch.matches("[0-9a-zA-Z-]{1,64}")) throw new IllegalArgumentException("observer epoch");
        var response = send(request("/observers/" + sessionId + "/frames/" + seq + "?epoch=" + epoch).GET());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() == 409) throw new Superseded();
        if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").equals("image/jpeg")) throw new Unavailable();
        return Optional.of(response.body());
    }

    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT); }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) {
        var pending = client.sendAsync(request.build(), ignored -> new LimitedBody());
        try { return pending.get(TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Unavailable(); }
        catch (ExecutionException | TimeoutException e) { throw new Unavailable(); }
        finally { pending.cancel(true); }
    }

    private Reply document(HttpResponse<byte[]> response) {
        if (response.statusCode() >= 500 && response.statusCode() != 503) throw new Unavailable();
        try {
            if (json.readValue(response.body(), Object.class) instanceof Map<?, ?> document) return new Reply(response.statusCode(), document);
        } catch (RuntimeException ignored) {}
        throw new Unavailable();
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
            if (size > LIMIT) { subscription.cancel(); delegate.onError(new Unavailable()); }
            else delegate.onNext(values);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
