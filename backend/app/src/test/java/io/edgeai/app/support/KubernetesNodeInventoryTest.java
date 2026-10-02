package io.edgeai.app.support;
import io.edgeai.adapters.kubernetes.KubernetesNodeInventory;
import io.edgeai.app.service.NodeService;
import io.edgeai.app.service.NodeSyncService;
import io.edgeai.domain.node.NodeInventory;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesNodeInventoryTest {
    @Test void parsesRealNodeShapeAndFetchesAllConsistentPagesWithoutHttpProxyUpgrade() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        server.createContext("/api/v1/nodes",exchange->{
            if (exchange.getRequestHeaders().containsKey("Upgrade")) {
                exchange.sendResponseHeaders(500,-1);exchange.close();return;
            }
            boolean next=exchange.getRequestURI().getRawQuery().contains("continue=");calls.incrementAndGet();
            String body="{\"kind\":\"NodeList\",\"metadata\":{\"resourceVersion\":\"42\",\"continue\":\""+(next?"":"next-page")+"\"},\"items\":["+node(next?second:first,next?"False":"True")+"]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try(var inventory=new KubernetesNodeInventory("http://127.0.0.1:"+server.getAddress().getPort(),"","")) {
            var result=inventory.snapshot(Instant.now());assertThat(result).hasSize(2);assertThat(calls).hasValue(2);
            assertThat(result.getFirst().id()).isEqualTo(first);assertThat(result.getFirst().observedStatus()).isEqualTo("READY");
            assertThat(result.get(1).observedStatus()).isEqualTo("NOT_READY");
            assertThat(result.getFirst().labelsJson()).contains("amd64");
        } finally { server.stop(0); }
    }
    @Test void failedOrMalformedOrRepeatedPageIsNotAnEmptySuccessfulSnapshot() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var mode=new AtomicInteger();
        server.createContext("/api/v1/nodes",exchange->{
            int value=mode.get();String body=value==0?"{}":"{\"kind\":\"NodeList\",\"metadata\":{\"resourceVersion\":\"42\",\"continue\":\"repeat\"},\"items\":[]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(value==2?403:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try(var inventory=new KubernetesNodeInventory("http://127.0.0.1:"+server.getAddress().getPort(),"","")) {
            for(int value=0;value<3;value++){mode.set(value);assertThatThrownBy(()->inventory.snapshot(Instant.now())).isInstanceOf(IllegalStateException.class);}
        } finally {server.stop(0);}
        assertThatThrownBy(()->new KubernetesNodeInventory("http://remote.example","","")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KubernetesNodeInventory("https://user:password@example.com","","")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failedObservationDoesNotMarkPreviousNodesRemoved() {
        var inventory=mock(NodeInventory.class);var service=mock(NodeService.class);
        when(inventory.snapshot(any())).thenThrow(new IllegalStateException("failed"));
        new NodeSyncService(inventory,service,Clock.systemUTC()).synchronize();
        verifyNoInteractions(service);
    }
    private String node(UUID id,String ready) { return """
        {"metadata":{"uid":"%s","name":"test-node","labels":{"kubernetes.io/arch":"amd64"}},
         "status":{"nodeInfo":{"architecture":"amd64","operatingSystem":"linux"},
         "allocatable":{"cpu":"4","memory":"8Gi"},"conditions":[{"type":"Ready","status":"%s"}]}}
        """.formatted(id,ready); }
}
