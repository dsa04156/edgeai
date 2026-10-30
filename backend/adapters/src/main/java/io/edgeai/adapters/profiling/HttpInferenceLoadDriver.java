package io.edgeai.adapters.profiling;

import io.edgeai.domain.aimodel.InferenceLoadDriver;
import java.nio.ByteBuffer;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Narrow closed-loop HTTP load for one profiling run: a fixed number of workers each send the run's sample request and
 * wait for the answer before the next one, for a fixed duration. Only {@code http://<podIp>:<port><path>} is called
 * (the caller passes the IP Kubernetes reported for the owned Pod), redirects are never followed and at most
 * {@link #MAX_RESPONSE_BYTES} of each answer is read and discarded. This is not a general load tester or Runner.
 */
public final class HttpInferenceLoadDriver implements InferenceLoadDriver, AutoCloseable {
    public static final int MAX_RESPONSE_BYTES = 1 << 20;
    /** Fixed geometric latency buckets: 0.1 ms × 1.05^i up to ~200 s; quantiles are the bucket upper bound (≤5 % above the true value). */
    static final double BUCKET_BASE = 0.1, BUCKET_RATIO = 1.05;
    static final int BUCKETS = 300;
    /** Total time stop() or close() waits for workers or exchanges, whatever a stalled model server does. */
    static final Duration STOP_WAIT = Duration.ofSeconds(2);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client;

    public HttpInferenceLoadDriver() {
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).executor(executor).build();
    }
    /** Aborts every exchange still in flight; {@link HttpClient#close} would instead wait for stalled responses to finish. */
    @Override public void close() {
        client.shutdownNow();
        executor.shutdownNow();
        try { client.awaitTermination(STOP_WAIT); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static URI target(HttpLoad load, String podIp) {
        InetAddress address;
        try {
            // Literal IPv4 or IPv6 only, so no name is ever resolved and no host but the Pod's address is contacted.
            if (podIp == null || !(podIp.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || podIp.matches("[0-9a-fA-F:.]{2,45}") && podIp.contains(":")))
                throw new IllegalArgumentException("Pod IP required");
            address = InetAddress.getByName(podIp); // a literal: no DNS lookup for an address of this form
        } catch (UnknownHostException e) { throw new IllegalArgumentException("Invalid Pod IP"); }
        String host = address instanceof Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
        if (load.port() < 1 || load.port() > 65535 || !load.path().startsWith("/")) throw new IllegalArgumentException("Invalid target");
        return URI.create("http://" + host + ":" + load.port() + load.path());
    }
    private HttpRequest request(HttpLoad load, URI uri) {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(load.requestTimeoutSeconds()));
        if (load.method().equals("GET")) builder.GET();
        else builder.header("Content-Type", load.contentType()).POST(HttpRequest.BodyPublishers.ofByteArray(load.body()));
        return builder.build();
    }
    /**
     * Status code, or a negative class: -1 timeout, -2 connection, -3 other I/O, -4 response too large. The request
     * timeout bounds the whole exchange including the body, which is counted and discarded, never kept.
     */
    private int send(HttpRequest request, int timeoutSeconds) {
        var future = client.sendAsync(request, info -> new Discarding());
        try {
            var response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return response.body() ? -4 : response.statusCode();
        } catch (TimeoutException e) { future.cancel(true); return -1; }
          catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); return -3; }
          catch (ExecutionException e) {
              Throwable cause = e.getCause();
              if (cause instanceof HttpTimeoutException) return -1;
              if (cause instanceof ConnectException || cause != null && cause.getCause() instanceof ConnectException) return -2;
              return -3;
          }
    }
    /** Counts the body without keeping it; completes with true (and stops reading) once it exceeds the limit. */
    private static final class Discarding implements HttpResponse.BodySubscriber<Boolean> {
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long size;
        public CompletionStage<Boolean> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        public void onNext(List<ByteBuffer> items) {
            for (var item : items) size += item.remaining();
            if (size > MAX_RESPONSE_BYTES && result.complete(true)) subscription.cancel();
        }
        public void onError(Throwable e) { result.completeExceptionally(e); }
        public void onComplete() { result.complete(false); }
    }
    private static String label(int code) {
        return switch (code) { case -1 -> "TIMEOUT"; case -2 -> "CONNECTION"; case -4 -> "TOO_LARGE"; default -> code < 0 ? "IO" : Integer.toString(code); };
    }

    @Override public ProbeResult probe(HttpLoad load, String podIp) {
        int code = send(request(load, target(load, podIp)), load.requestTimeoutSeconds());
        if (code >= 200 && code < 300) return ProbeResult.READY;
        if (code == -1) return ProbeResult.TIMEOUT;
        if (code == -2) return ProbeResult.CONNECTION_FAILED;
        return ProbeResult.HTTP_ERROR;
    }

    @Override public Handle start(HttpLoad load, String podIp, Duration duration) {
        if (load.concurrency() < 1 || load.concurrency() > 16 || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Invalid load");
        var request = request(load, target(load, podIp));
        var run = new Run(Instant.now(), System.nanoTime() + duration.toNanos());
        for (int i = 0; i < load.concurrency(); i++) run.workers.add(Thread.ofVirtual().name("model-profiling-load").start(() -> run.work(request, load.requestTimeoutSeconds())));
        return run;
    }

    private final class Run implements Handle {
        private final Instant started;
        private final long deadline;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final List<Thread> workers = new CopyOnWriteArrayList<>();
        private final Object lock = new Object();
        private final Map<String, Long> counts = new TreeMap<>();
        private final long[] histogram = new long[BUCKETS];
        private double latencyMax;
        private long requests, succeeded, connectionFailures;
        private volatile Instant ended;
        Run(Instant started, long deadline) { this.started = started; this.deadline = deadline; }

        void work(HttpRequest request, int timeoutSeconds) {
            while (!stopped.get() && System.nanoTime() < deadline) {
                long t0 = System.nanoTime();
                int code = send(request, timeoutSeconds);
                double millis = (System.nanoTime() - t0) / 1e6;
                if (stopped.get() && code < 0) break; // interrupted by stop: not a model failure
                synchronized (lock) {
                    requests++;
                    counts.merge(label(code), 1L, Long::sum);
                    if (code >= 200 && code < 300) { succeeded++; histogram[bucket(millis)]++; latencyMax = Math.max(latencyMax, millis); }
                    if (code == -2) connectionFailures++;
                }
                if (code == -2) sleep(200); // closed port: avoid a tight reconnect loop
            }
        }
        private void sleep(long millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        @Override public boolean finished() { return workers.stream().noneMatch(Thread::isAlive); }
        @Override public Progress progress() {
            synchronized (lock) {
                return new Progress(started, ended, requests, succeeded, requests - succeeded, connectionFailures, counts,
                    quantile(histogram, succeeded, .50, latencyMax), quantile(histogram, succeeded, .95, latencyMax), succeeded == 0 ? null : latencyMax);
            }
        }
        /**
         * Interrupts every worker, before or after the deadline: an interrupted worker cancels its exchange (stalled headers
         * or body included) and that unfinished request is not counted. All workers share one {@link #STOP_WAIT} budget.
         */
        @Override public Progress stop() {
            if (stopped.compareAndSet(false, true)) {
                workers.forEach(Thread::interrupt);
                long until = System.nanoTime() + STOP_WAIT.toNanos();
                for (var worker : workers) {
                    long left = until - System.nanoTime();
                    if (left <= 0) break;
                    try { worker.join(Duration.ofNanos(left)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                ended = Instant.now();
            }
            return progress();
        }
    }
    static int bucket(double millis) {
        if (!(millis > BUCKET_BASE)) return 0;
        return (int) Math.min(BUCKETS - 1, Math.ceil(Math.log(millis / BUCKET_BASE) / Math.log(BUCKET_RATIO)));
    }
    static double upper(int bucket) { return BUCKET_BASE * Math.pow(BUCKET_RATIO, bucket); }
    /** Approximate quantile: upper bound of the bucket holding the q-th sample, never above the observed maximum. */
    static Double quantile(long[] histogram, long total, double q, double max) {
        if (total == 0) return null;
        long rank = Math.max(1, (long) Math.ceil(q * total)), seen = 0;
        for (int i = 0; i < histogram.length; i++) if ((seen += histogram[i]) >= rank) return Math.min(upper(i), max);
        return max;
    }
}
