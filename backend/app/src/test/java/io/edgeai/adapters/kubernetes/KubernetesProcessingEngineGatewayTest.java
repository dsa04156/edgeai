package io.edgeai.adapters.kubernetes;

import com.sun.net.httpserver.*;
import io.edgeai.adapters.kubernetes.KubernetesProcessingEngineGateway;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class KubernetesProcessingEngineGatewayTest {
    private HttpServer server;
    private KubernetesProcessingEngineGateway gateway;
    private final JsonMapper json = new JsonMapper();
    private final UUID uid = UUID.randomUUID();
    private String fault = "";
    private volatile Throwable fixtureFailure;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond); server.start();
        gateway = new KubernetesProcessingEngineGateway("http://127.0.0.1:"+server.getAddress().getPort(), "", "", "ekuiper", "edgeai-processing", "my-ekuiper-0");
    }
    @AfterEach void stop() { gateway.close(); server.stop(0); assertThat(fixtureFailure).isNull(); }
    private void respond(HttpExchange exchange) {
        try {
            Object reply;
            if (exchange.getRequestURI().getPath().equals("/apis/authentication.k8s.io/v1/tokenreviews")) {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                var request = json.readTree(exchange.getRequestBody().readAllBytes());
                assertThat(request.path("spec").path("audiences").path(0).asText()).isEqualTo(KubernetesProcessingEngineGateway.AUDIENCE);
                reply = Map.of("status", Map.of("authenticated", !fault.equals("authentication"), "audiences", List.of(fault.equals("audience") ? "other" : KubernetesProcessingEngineGateway.AUDIENCE),
                    "user", Map.of("username", fault.equals("account") ? "system:serviceaccount:ekuiper:default" : "system:serviceaccount:ekuiper:edgeai-processing",
                        "extra", Map.of("authentication.kubernetes.io/pod-name", List.of(fault.equals("name") ? "other" : "my-ekuiper-0"),
                            "authentication.kubernetes.io/pod-uid", fault.equals("unbound") ? List.of() : List.of(uid.toString())))));
            } else {
                assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/v1/namespaces/ekuiper/pods/my-ekuiper-0");
                var metadata = new HashMap<String,Object>(Map.of("name", "my-ekuiper-0", "namespace", "ekuiper", "uid", fault.equals("recreated") ? UUID.randomUUID().toString() : uid.toString(),
                    "labels", Map.of("app.kubernetes.io/part-of", "edgeai", "edgeai.io/processing-engine", fault.equals("owner") ? "other" : "central")));
                if (fault.equals("deleting")) metadata.put("deletionTimestamp", "2026-10-13T00:00:00Z");
                reply = Map.of("metadata", metadata, "spec", Map.of("serviceAccountName", "edgeai-processing", "nodeName", "ordinary-worker"), "status", Map.of("phase", fault.equals("phase") ? "Pending" : "Running"));
            }
            byte[] body = json.writeValueAsBytes(reply);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } catch (Throwable error) { fixtureFailure = error; } finally { exchange.close(); }
    }
    @Test void authenticatesAnAudienceBoundTokenOnlyForTheCurrentOwnedEnginePod() {
        var identity = gateway.authenticate("projected-token");
        assertThat(identity.podUid()).isEqualTo(uid);
        assertThat(identity.podName()).isEqualTo("my-ekuiper-0");
        for (var rejected : List.of("authentication", "audience", "account", "unbound", "name", "recreated", "deleting", "owner", "phase")) {
            fault = rejected;
            assertThatThrownBy(() -> gateway.authenticate("projected-token"))
                .as(rejected).isInstanceOf(RuntimeGatewayException.class)
                .extracting(error -> ((RuntimeGatewayException)error).reason())
                .isEqualTo(RuntimeGatewayException.Reason.AUTH_REJECTED);
        }
    }
}
