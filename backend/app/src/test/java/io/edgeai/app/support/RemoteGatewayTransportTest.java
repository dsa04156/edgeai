package io.edgeai.app.support;

import com.sun.net.httpserver.*;
import io.edgeai.adapters.remote.ReferenceRemoteGateway;
import io.edgeai.domain.remote.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.edgeai.domain.remote.RemoteGatewayException.Reason.*;

/** Untrusted HTTP responses exercise the actual client and artifact publication boundary. */
class RemoteGatewayTransportTest {
    @TempDir Path directory;
    private HttpServer server;
    private ExecutorService executor;
    private ReferenceRemoteGateway gateway;
    private Path token;
    private String origin;
    private final AtomicReference<HttpHandler> handler=new AtomicReference<>();
    private final AtomicInteger requests=new AtomicInteger();
    private final RemoteIdentity id=new RemoteIdentity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1);
    private final Instant deadline=Instant.now().plusSeconds(60);
    private final JsonDocuments json=new JsonDocuments();

    @BeforeEach void start() throws Exception {
        token=directory.resolve("token");Files.writeString(token,UUID.randomUUID().toString().replace("-",""));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);serve(server);
        origin="http://127.0.0.1:"+server.getAddress().getPort();gateway=client(Duration.ofSeconds(2));
        handler.set(exchange->reply(exchange,200,"application/json",json.canonical(receipt(id)).getBytes(StandardCharsets.UTF_8)));
    }
    private void serve(HttpServer target) {
        executor=Executors.newVirtualThreadPerTaskExecutor();target.setExecutor(executor);
        target.createContext("/",exchange->{requests.incrementAndGet();try{handler.get().handle(exchange);}finally{exchange.close();}});target.start();
    }
    private ReferenceRemoteGateway client(Duration timeout){return new ReferenceRemoteGateway(origin,token,null,timeout,"SYNTHETIC");}
    @AfterEach void stop(){if(gateway!=null)gateway.close();if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}
    private Map<String,Object> receipt(RemoteIdentity identity) {
        var value=new LinkedHashMap<String,Object>();value.put("identity",Map.of("allocationId",identity.allocationId().toString(),"runId",identity.runId().toString(),"taskId",identity.taskId().toString(),"attemptId",identity.attemptId().toString(),"epoch",identity.epoch()));
        value.put("revision",1);value.put("requestDigest","sha256:"+"a".repeat(64));value.put("state","ALLOCATED");value.put("expiresAt",deadline.toString());
        value.put("failureReason",null);value.put("sourceMode","SYNTHETIC");value.put("outputs",List.of());return value;
    }
    private static void reply(HttpExchange exchange,int code,String media,byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type",media);exchange.sendResponseHeaders(code,data.length);exchange.getResponseBody().write(data);
    }
    private void rejected(Runnable call,RemoteGatewayException.Reason reason){assertThatThrownBy(call::run).isInstanceOfSatisfying(RemoteGatewayException.class,e->{assertThat(e.reason()).isEqualTo(reason);assertThat(e.getMessage()).isEqualTo(reason.name());assertThat(e.getCause()).isNull();});}
    private RemoteFile output(byte[] bytes) throws Exception{return new RemoteFile("output",bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"application/json");}
    private void noTemporaryFiles() throws Exception{try(var files=Files.list(directory)){assertThat(files.noneMatch(p->p.getFileName().toString().endsWith(".part"))).isTrue();}}

    @Test void rejectsRedirectsWithoutForwardingCredentials() {
        handler.set(exchange->{exchange.getResponseHeaders().set("Location",origin+"/stolen");reply(exchange,307,"application/json","{}".getBytes());});
        rejected(()->gateway.inspect(id),INVALID_RESPONSE);assertThat(requests.get()).isEqualTo(1);
    }
    @Test void statusFailuresRemainSanitizedAndDownloadDoesNotPublishErrorBodies() throws Exception {
        var metadata=output("{}".getBytes());Path target=directory.resolve("result");
        for(var failure:Map.of(401,AUTH_REJECTED,403,AUTH_REJECTED,409,CONFLICT,422,UNSUPPORTED,429,UNAVAILABLE,500,UNAVAILABLE).entrySet()) {
            handler.set(exchange->reply(exchange,failure.getKey(),"application/json","{\"detail\":\"provider-sensitive-body\"}".getBytes()));
            rejected(()->gateway.inspect(id),failure.getValue());rejected(()->gateway.downloadOutput(id,metadata,target),failure.getValue());assertThat(Files.exists(target)).isFalse();
        }
        noTemporaryFiles();handler.set(exchange->reply(exchange,404,"application/json","{}".getBytes()));assertThat(gateway.inspect(id)).isEmpty();
        rejected(()->gateway.start(id),NOT_FOUND);rejected(()->gateway.cancel(id),NOT_FOUND);
    }
    @Test void rejectsForeignIdentitySourceModeAndImpossibleTerminalStates() {
        var foreign=new RemoteIdentity(id.allocationId(),id.runId(),id.taskId(),UUID.randomUUID(),2);
        var wrongSource=receipt(id);wrongSource.put("sourceMode","EXTERNAL");var missingOutput=receipt(id);missingOutput.put("state","SUCCEEDED");
        var fakeFailure=receipt(id);fakeFailure.put("failureReason","WORKLOAD_FAILED");var overflow=receipt(id);overflow.put("revision",9007199254740992L);
        for(var invalid:List.of(receipt(foreign),wrongSource,missingOutput,fakeFailure,overflow)) {
            handler.set(exchange->reply(exchange,200,"application/json",json.canonical(invalid).getBytes(StandardCharsets.UTF_8)));rejected(()->gateway.inspect(id),INVALID_RESPONSE);
        }
    }
    @Test void oversizedDuplicateAndTrailingMetadataNeverBecomeObservations() {
        String valid=json.canonical(receipt(id));
        for(String invalid:List.of(" ".repeat(262145),valid+"{}",valid.replace("\"revision\":1","\"revision\":1,\"revision\":2"))) {
            handler.set(exchange->reply(exchange,200,"application/json",invalid.getBytes(StandardCharsets.UTF_8)));rejected(()->gateway.inspect(id),INVALID_RESPONSE);
        }
        handler.set(exchange->reply(exchange,200,"text/html",valid.getBytes(StandardCharsets.UTF_8)));rejected(()->gateway.inspect(id),INVALID_RESPONSE);
    }
    @Test void wholeBodyDeadlineCancelsAStalledDownloadAndCleansPartialFile() throws Exception {
        byte[] bytes="abcd".getBytes();var metadata=output(bytes);Path target=directory.resolve("result");
        handler.set(exchange->{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,4);exchange.getResponseBody().write('a');exchange.getResponseBody().flush();try{Thread.sleep(1500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        long started=System.nanoTime();try(var impatient=client(Duration.ofMillis(250))){rejected(()->impatient.downloadOutput(id,metadata,target),UNAVAILABLE);}
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(1));assertThat(Files.exists(target)).isFalse();noTemporaryFiles();
    }
    @Test void sameSizeCorruptionAndOversizedOutputCannotPublish() throws Exception {
        var metadata=output("abcd".getBytes());Path target=directory.resolve("result");
        for(byte[] body:List.of("xxxx".getBytes(),"abcde".getBytes())) {
            handler.set(exchange->reply(exchange,200,"application/json",body));rejected(()->gateway.downloadOutput(id,metadata,target),INTEGRITY_FAILED);assertThat(Files.exists(target)).isFalse();noTemporaryFiles();
        }
    }
    @Test void destinationCreatedDuringDownloadIsPreserved() throws Exception {
        byte[] data="abcd".getBytes();var metadata=output(data);Path target=directory.resolve("result");
        handler.set(exchange->{Files.writeString(target,"already-owned");reply(exchange,200,"application/json",data);});
        rejected(()->gateway.downloadOutput(id,metadata,target),INTEGRITY_FAILED);assertThat(Files.readString(target)).isEqualTo("already-owned");noTemporaryFiles();
    }
    @Test void canonicalReplayPreservesLargeNumbersAndValidUnicodeButRejectsInvalidStrings() {
        var captured=new ArrayList<String>();
        handler.set(exchange->{String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);captured.add(body);var response=receipt(id);response.put("requestDigest",exchange.getRequestHeaders().getFirst("X-EdgeAI-Request-Digest"));reply(exchange,201,"application/json",json.canonical(response).getBytes(StandardCharsets.UTF_8));});
        var first=new RemoteWork(id,"{}","{\"n\":9007199254740993,\"v\":1.00,\"label\":\"가😀\"}",List.of(),deadline);
        var reordered=new RemoteWork(id,"{}","{\"label\":\"가😀\",\"v\":1,\"n\":9007199254740993}",List.of(),deadline);
        assertThat(gateway.reserve(first).requestDigest()).isEqualTo(gateway.reserve(reordered).requestDigest());assertThat(captured.getFirst()).contains("9007199254740993","가😀");assertThat(captured.getFirst()).isEqualTo(captured.getLast());
        for(String bad:List.of("{\"s\":\"\\ud800\"}","{\"s\":\"\\udc00\"}","{\"s\":\"\\u0000\"}","{\"n\":1,\"n\":2}"))rejected(()->gateway.reserve(new RemoteWork(id,"{}",bad,List.of(),deadline)),INVALID_INPUT);
        assertThat(requests.get()).isEqualTo(2);
    }
    @Test void tokenRotationAndInvalidOriginFailBeforeSendingSensitiveData() throws Exception {
        var seen=new ArrayList<String>();handler.set(exchange->{seen.add(exchange.getRequestHeaders().getFirst("Authorization"));reply(exchange,200,"application/json",json.canonical(receipt(id)).getBytes());});
        gateway.inspect(id);Files.writeString(token,UUID.randomUUID().toString().replace("-",""));gateway.inspect(id);assertThat(seen.getFirst().equals(seen.getLast())).isFalse();
        Files.writeString(token,"bad\nheader");rejected(()->gateway.inspect(id),AUTH_REJECTED);assertThat(requests.get()).isEqualTo(2);
        for(String invalid:List.of("http://localhost:80","http://example.com","https://user:password@example.com","https://example.com/path","https://example.com?token=x"))assertThatThrownBy(()->new ReferenceRemoteGateway(invalid,token,null,Duration.ofSeconds(1),"SYNTHETIC")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void customCaIsTrustedButUnknownCaAndWrongHostnameAreRejected() throws Exception {
        gateway.close();server.stop(0);executor.shutdownNow();
        Path storeFile=directory.resolve("server.p12");String password=UUID.randomUUID().toString();
        var generate=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","server","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=reference.invalid","-ext","SAN=IP:127.0.0.1","-storetype","PKCS12","-keystore",storeFile.toString(),"-storepass",password,"-noprompt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(generate.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(generate.exitValue()).isZero();
        var store=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(storeFile)){store.load(in,password.toCharArray());}
        Path ca=directory.resolve("ca.pem");Files.writeString(ca,"-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(store.getCertificate("server").getEncoded())+"\n-----END CERTIFICATE-----\n");
        var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,password.toCharArray());var ssl=SSLContext.getInstance("TLS");ssl.init(keys.getKeyManagers(),null,null);
        var https=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);https.setHttpsConfigurator(new HttpsConfigurator(ssl));server=https;serve(server);origin="https://127.0.0.1:"+server.getAddress().getPort();
        gateway=new ReferenceRemoteGateway(origin,token,ca,Duration.ofSeconds(2),"SYNTHETIC");assertThat(gateway.inspect(id)).isPresent();int before=requests.get();
        try(var untrusted=client(Duration.ofSeconds(2))){rejected(()->untrusted.inspect(id),UNAVAILABLE);}
        try(var wrongHost=new ReferenceRemoteGateway("https://localhost:"+server.getAddress().getPort(),token,ca,Duration.ofSeconds(2),"SYNTHETIC")){rejected(()->wrongHost.inspect(id),UNAVAILABLE);}
        assertThat(requests.get()).isEqualTo(before);
    }
}
