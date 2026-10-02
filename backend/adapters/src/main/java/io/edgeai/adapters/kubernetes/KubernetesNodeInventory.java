package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.node.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.databind.json.JsonMapper;

/** Uses only the core/v1 read API. Incomplete/failed lists never become successful empty snapshots. */
public final class KubernetesNodeInventory implements NodeInventory, AutoCloseable {
    private final URI origin;
    private final String tokenFile;
    private final HttpClient client;
    private final JsonMapper json=new JsonMapper();
    public KubernetesNodeInventory(String url,String tokenFile,String caFile) {
        origin=URI.create(url.replaceAll("/+$",""));this.tokenFile=tokenFile;
        if (origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null || !origin.getPath().isEmpty())
            throw new IllegalArgumentException("Kubernetes URL must be an origin");
        if (!origin.getScheme().equals("https") && !(origin.getScheme().equals("http") && Set.of("127.0.0.1","localhost","[::1]").contains(origin.getHost())))
            throw new IllegalArgumentException("Kubernetes requires HTTPS outside loopback");
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER);
        // kubectl proxy cannot forward the cleartext HTTP/2 upgrade handshake.
        if (origin.getScheme().equals("http")) builder.version(HttpClient.Version.HTTP_1_1);
        if (!caFile.isBlank()) builder.sslContext(tls(caFile));
        client=builder.build();
    }
    private static SSLContext tls(String file) {
        try (var stream=Files.newInputStream(Path.of(file))) {
            var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
            int index=0;
            for (var cert:CertificateFactory.getInstance("X.509").generateCertificates(stream)) store.setCertificateEntry("ca-"+index++,cert);
            if (index==0) throw new IllegalArgumentException("Empty Kubernetes CA");
            var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
            var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);return context;
        } catch (Exception e) { throw new IllegalArgumentException("Cannot configure Kubernetes CA"); }
    }
    @Override public List<ExecutionNode> snapshot(Instant observedAt) {
        var nodes=new ArrayList<ExecutionNode>();var ids=new HashSet<UUID>();var continuations=new HashSet<String>();
        String continuation="", resourceVersion=null;long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        try {
            do {
                if (System.nanoTime()>deadline || continuations.size()>20) throw new IllegalStateException("Node listing exceeded budget");
                String path="/api/v1/nodes?limit=500"+(continuation.isEmpty()?"":"&continue="+URLEncoder.encode(continuation,StandardCharsets.UTF_8));
                var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5)).header("Accept","application/json");
                if (!tokenFile.isBlank()) request.header("Authorization","Bearer "+Files.readString(Path.of(tokenFile)).trim());
                var response=client.send(request.GET().build(),info->new LimitedBody());
                if (response.statusCode()!=200) throw new IllegalStateException("Node API returned HTTP "+response.statusCode());
                var root=json.readTree(response.body());
                if (!root.path("items").isArray() || !root.path("kind").asText().equals("NodeList")) throw new IllegalStateException("Invalid NodeList");
                String version=root.path("metadata").path("resourceVersion").asText();
                if (version.isBlank() || (resourceVersion!=null && !resourceVersion.equals(version))) throw new IllegalStateException("Inconsistent NodeList");
                resourceVersion=version;
                for (var node:root.path("items")) {
                    var metadata=node.path("metadata");var status=node.path("status");var info=status.path("nodeInfo");
                    UUID id=UUID.fromString(metadata.path("uid").asText());
                    if (!ids.add(id)) throw new IllegalStateException("Duplicate Node UID");
                    String ready="UNKNOWN";
                    for (var condition:status.path("conditions")) if (condition.path("type").asText().equals("Ready"))
                        ready=switch(condition.path("status").asText()) { case "True"->"READY";case "False"->"NOT_READY";default->"UNKNOWN"; };
                    var labels=metadata.path("labels");
                    nodes.add(new ExecutionNode(id,metadata.path("name").asText(),info.path("architecture").asText(),info.path("operatingSystem").asText(),ready,
                        status.path("allocatable").path("cpu").asText(),status.path("allocatable").path("memory").asText(),labels.isObject()?json.writeValueAsString(labels):"{}",observedAt));
                }
                continuation=root.path("metadata").path("continue").asText("");
                if (!continuation.isEmpty() && !continuations.add(continuation)) throw new IllegalStateException("Repeated continuation");
            } while (!continuation.isEmpty());
            return List.copyOf(nodes);
        } catch (InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Node observation interrupted"); }
          catch (IOException e) { throw new IllegalStateException("Node API unavailable"); }
    }
    @Override public void close() { client.close(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value;delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> values) {
            for (var value:values) size+=value.remaining();
            if (size>8388608) { subscription.cancel();delegate.onError(new IOException("Node response too large")); }
            else delegate.onNext(values);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
