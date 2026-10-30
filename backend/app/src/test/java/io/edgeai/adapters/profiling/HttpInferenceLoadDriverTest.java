package io.edgeai.adapters.profiling;

import com.sun.net.httpserver.HttpServer;
import io.edgeai.domain.aimodel.InferenceLoadDriver.HttpLoad;
import io.edgeai.domain.aimodel.InferenceLoadDriver.ProbeResult;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** The narrow HTTP load against a real local server: fixed target, no redirects, bounded responses, counted outcomes. */
@Timeout(60)
class HttpInferenceLoadDriverTest {
    private HttpServer server;
    private HttpInferenceLoadDriver driver;
    private final List<String> received=new CopyOnWriteArrayList<>();
    private final AtomicInteger calls=new AtomicInteger();
    private volatile int status=200;
    private volatile int bodyBytes=16;
    private volatile long delayMillis;
    private volatile String location;

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/",exchange->{
            calls.incrementAndGet();
            received.add(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+exchange.getRequestHeaders().getFirst("Content-Type")+" "
                +new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try{if(delayMillis>0)Thread.sleep(delayMillis);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(location!=null)exchange.getResponseHeaders().add("Location",location);
            exchange.sendResponseHeaders(status,bodyBytes);
            try(var out=exchange.getResponseBody()){byte[] chunk=new byte[8192];for(int left=bodyBytes;left>0;left-=chunk.length)out.write(chunk,0,Math.min(left,chunk.length));}
            catch(java.io.IOException ignored){}
        });
        server.start();
        driver=new HttpInferenceLoadDriver();
    }
    @AfterEach void stop(){driver.close();server.stop(0);}

    private HttpLoad load(int concurrency,int timeout){
        return new HttpLoad(server.getAddress().getPort(),"/v2/models/digits-centroid/infer","POST","application/json","{\"inputs\":[]}".getBytes(),concurrency,timeout);
    }

    @Test void sendsTheSampleRequestToThePodIpOnlyAndCountsSuccesses() throws Exception {
        assertThat(driver.probe(load(1,5),"127.0.0.1")).isEqualTo(ProbeResult.READY);
        var handle=driver.start(load(3,5),"127.0.0.1",Duration.ofSeconds(30));
        while(handle.progress().succeeded()<30)Thread.sleep(10);
        var p=handle.stop();
        assertThat(handle.finished()).isTrue();
        assertThat(p.succeeded()).isGreaterThanOrEqualTo(30);assertThat(p.failed()).isZero();
        assertThat(p.statusCounts()).containsEntry("200",p.succeeded());
        assertThat(p.latencyP50Millis()).isPositive();assertThat(p.latencyP95Millis()).isLessThanOrEqualTo(p.latencyMaxMillis());
        long after=calls.get();Thread.sleep(100);
        assertThat(calls.get()).isEqualTo(after); // stopped means no more requests
        assertThat(received).allMatch(r->r.equals("POST /v2/models/digits-centroid/infer application/json {\"inputs\":[]}"));
        assertThat(handle.stop()).isEqualTo(p); // idempotent
    }
    @Test void errorsRedirectsLargeAndSlowAnswersAreNotSuccesses() throws Exception {
        status=500;
        assertThat(driver.probe(load(1,5),"127.0.0.1")).isEqualTo(ProbeResult.HTTP_ERROR);
        var handle=driver.start(load(2,5),"127.0.0.1",Duration.ofSeconds(30));
        while(handle.progress().requests()<10)Thread.sleep(5);
        var p=handle.stop();
        assertThat(p.succeeded()).isZero();assertThat(p.statusCounts()).containsKey("500");assertThat(p.latencyP95Millis()).isNull();

        status=302;location="http://example.invalid/elsewhere";calls.set(0);
        assertThat(driver.probe(load(1,5),"127.0.0.1")).isEqualTo(ProbeResult.HTTP_ERROR);
        assertThat(calls.get()).isEqualTo(1); // the redirect was not followed

        status=200;location=null;bodyBytes=HttpInferenceLoadDriver.MAX_RESPONSE_BYTES+8193;
        handle=driver.start(load(1,5),"127.0.0.1",Duration.ofSeconds(30));
        while(handle.progress().requests()<2)Thread.sleep(5);
        assertThat(handle.stop().statusCounts()).containsKey("TOO_LARGE").doesNotContainKey("200");

        bodyBytes=16;delayMillis=2000;
        assertThat(driver.probe(load(1,1),"127.0.0.1")).isEqualTo(ProbeResult.TIMEOUT);
    }
    @Test void aClosedPortIsAConnectionFailureAndHostNamesAreNeverTargets() throws Exception {
        int closed;try(var socket=new ServerSocket(0)){closed=socket.getLocalPort();}
        var unreachable=new HttpLoad(closed,"/","GET",null,null,1,2);
        assertThat(driver.probe(unreachable,"127.0.0.1")).isEqualTo(ProbeResult.CONNECTION_FAILED);
        var handle=driver.start(unreachable,"127.0.0.1",Duration.ofSeconds(30));
        while(handle.progress().requests()<2)Thread.sleep(5);
        var p=handle.stop();
        assertThat(p.connectionFailures()).isEqualTo(p.requests());
        for(String host:List.of("localhost","example.com","10.0.0.1/evil","",""+"1".repeat(50)))
            assertThatThrownBy(()->driver.probe(load(1,1),host)).isInstanceOf(IllegalArgumentException.class);
        assertThat(HttpInferenceLoadDriver.target(load(1,1),"fd00::1").toString()).startsWith("http://[fd00:0:0:0:0:0:0:1]:");
    }
    /** A model server that accepts and then stalls: before the headers, or after the headers and one byte of the body. */
    private static ServerSocket stalling(boolean headers,List<java.net.Socket> held) throws Exception {
        var socket=new ServerSocket(0,50,java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(()->{
            try {
                while(true) {
                    var s=socket.accept();held.add(s);
                    Thread.ofVirtual().start(()->{
                        try {
                            var in=s.getInputStream();byte[] buffer=new byte[8192];int n;var seen=new StringBuilder();
                            while(!seen.toString().contains("\r\n\r\n") && (n=in.read(buffer))>0)seen.append(new String(buffer,0,n,StandardCharsets.ISO_8859_1));
                            if(headers){s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\n\r\n{".getBytes(StandardCharsets.ISO_8859_1));s.getOutputStream().flush();}
                        } catch(java.io.IOException ignored){}
                    });
                }
            } catch(java.io.IOException ignored){}
        });
        return socket;
    }
    /**
     * Requests stuck on a stalled model (60 s request timeout) neither delay stop(), before or after the load deadline,
     * nor close(): both end within a short bounded time and the unfinished requests are not counted.
     */
    @Test void stopAndCloseAreBoundedWhileResponsesStall() throws Exception {
        var held=new CopyOnWriteArrayList<java.net.Socket>();
        try(var body=stalling(true,held);var headers=stalling(false,held)) {
            for(var target:List.of(body,headers)) {
                var stalled=new HttpLoad(target.getLocalPort(),"/infer","POST","application/json","{}".getBytes(),4,60);
                // After the deadline: the workers are still inside their 60 s exchanges.
                var late=driver.start(stalled,"127.0.0.1",Duration.ofMillis(300));
                Thread.sleep(800);
                long t0=System.nanoTime();
                var p=late.stop();
                assertThat(Duration.ofNanos(System.nanoTime()-t0)).isLessThan(Duration.ofSeconds(3));
                assertThat(p.requests()).isZero();
                Thread.sleep(200);assertThat(late.finished()).isTrue();
                // Before the deadline.
                var early=driver.start(stalled,"127.0.0.1",Duration.ofSeconds(30));
                Thread.sleep(300);
                t0=System.nanoTime();
                assertThat(early.stop().requests()).isZero();
                assertThat(Duration.ofNanos(System.nanoTime()-t0)).isLessThan(Duration.ofSeconds(3));
            }
            // close() with exchanges in flight on both kinds of stall.
            var a=driver.start(new HttpLoad(body.getLocalPort(),"/infer","GET",null,null,4,60),"127.0.0.1",Duration.ofSeconds(30));
            var b=driver.start(new HttpLoad(headers.getLocalPort(),"/infer","GET",null,null,4,60),"127.0.0.1",Duration.ofSeconds(30));
            Thread.sleep(300);
            long t0=System.nanoTime();
            driver.close();
            assertThat(Duration.ofNanos(System.nanoTime()-t0)).isLessThan(Duration.ofSeconds(3));
            t0=System.nanoTime();
            a.stop();b.stop();
            assertThat(Duration.ofNanos(System.nanoTime()-t0)).isLessThan(Duration.ofSeconds(5));
            assertThat(a.finished() && b.finished()).isTrue();
        } finally { for(var s:held)s.close(); }
    }
    @Test void latencyQuantilesUseABoundedHistogram() {
        long[] histogram=new long[HttpInferenceLoadDriver.BUCKETS];
        for(int i=1;i<=1000;i++)histogram[HttpInferenceLoadDriver.bucket(i)]++;
        double p95=HttpInferenceLoadDriver.quantile(histogram,1000,.95,1000);
        assertThat(p95).isBetween(950.0,950*1.05);
        assertThat(HttpInferenceLoadDriver.quantile(histogram,1000,.50,1000)).isBetween(500.0,500*1.05);
        assertThat(HttpInferenceLoadDriver.bucket(1e9)).isEqualTo(HttpInferenceLoadDriver.BUCKETS-1);
        assertThat(HttpInferenceLoadDriver.quantile(new long[HttpInferenceLoadDriver.BUCKETS],0,.95,0)).isNull();
    }
}
