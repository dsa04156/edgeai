package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.runtime.RuntimeGatewayException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.databind.json.JsonMapper;
import static io.edgeai.domain.runtime.RuntimeGatewayException.Reason.*;

/** Bounded requests, fresh projected API credentials and CA verification. Never exposes response bodies in failures. */
final class KubernetesRuntimeHttp implements AutoCloseable {
    private final URI origin;
    private final String tokenFile;
    private final HttpClient client;
    private final JsonMapper json=new JsonMapper();
    record Reply(int status,byte[] body) {}
    KubernetesRuntimeHttp(String url,String tokenFile,String caFile) {
        origin=URI.create(url.replaceAll("/+$",""));this.tokenFile=tokenFile;
        if(origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null || !origin.getPath().isEmpty())
            throw new IllegalArgumentException("Kubernetes URL must be an origin");
        if(!origin.getScheme().equals("https") && !(origin.getScheme().equals("http") && Set.of("127.0.0.1","localhost","[::1]").contains(origin.getHost())))
            throw new IllegalArgumentException("Kubernetes requires HTTPS outside loopback");
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER);
        if(origin.getScheme().equals("http"))builder.version(HttpClient.Version.HTTP_1_1);
        if(!caFile.isBlank())builder.sslContext(tls(caFile));client=builder.build();
    }
    Reply request(String method,String path,Object body,Duration timeout) {
        try {
            var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout).header("Accept","application/json");
            if(!tokenFile.isBlank())request.header("Authorization","Bearer "+Files.readString(Path.of(tokenFile)).trim());
            var data=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
            if(body!=null)request.header("Content-Type","application/json");
            var response=client.send(request.method(method,data).build(),info->new LimitedBody());
            return new Reply(response.statusCode(),response.body());
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new RuntimeGatewayException(UNAVAILABLE); }
          catch(Exception e) { throw new RuntimeGatewayException(UNAVAILABLE); }
    }
    private static SSLContext tls(String file) {
        try(var stream=Files.newInputStream(Path.of(file))) {
            var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);int i=0;
            for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(stream))store.setCertificateEntry("ca-"+i++,certificate);
            if(i==0)throw new IllegalArgumentException();
            var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
            var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);return context;
        } catch(Exception e) { throw new IllegalArgumentException("Cannot configure Kubernetes CA"); }
    }
    public void close() { client.close(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;private long size;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        public void onNext(List<ByteBuffer> values){
            for(var value:values)size+=value.remaining();
            if(size>8388608){subscription.cancel();delegate.onError(new IOException("Response exceeds limit"));}
            else delegate.onNext(values);
        }
        public void onError(Throwable e){delegate.onError(e);}
        public void onComplete(){delegate.onComplete();}
    }
}
