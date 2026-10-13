package io.edgeai.app.integration;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real TCP fault: headers succeed, response body never completes within the deadline. */
@SpringBootTest
@AutoConfigureMockMvc
class DataStoreDeadlineIntegrationTest {
    @Autowired MockMvc mvc;
    static final HttpServer server;
    static final Path token;
    static final java.util.concurrent.ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    static {
        try {
            token=Files.createTempFile("edgeai-deadline-", ".credential");
            Files.writeString(token,"owned-test-credential-for-stalled-server");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(executor);
            server.createContext("/api/v2/query", exchange->{
                try(exchange){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('x');exchange.getResponseBody().flush();Thread.sleep(12000);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            server.start();
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("edgeai.data-store.influx.url",()->"http://127.0.0.1:"+server.getAddress().getPort());
        r.add("edgeai.data-store.influx.org",()->"deadline-test");
        r.add("edgeai.data-store.influx.token-file",()->token.toString());
    }
    @AfterAll static void cleanup() throws Exception {server.stop(0);executor.shutdownNow();Files.deleteIfExists(token);}
    @Test void stalledResponseReturnsUnavailableWithinCompleteBodyDeadline() throws Exception {
        long started=System.nanoTime();
        mvc.perform(get("/api/v1/data-store")).andExpect(status().isServiceUnavailable());
        assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(8))<0,"Complete body deadline must bound the public API");
    }
}
