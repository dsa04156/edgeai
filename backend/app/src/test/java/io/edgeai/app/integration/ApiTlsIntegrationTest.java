package io.edgeai.app.integration;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP and native HTTPS share the actual Spring security chains and PostgreSQL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "edgeai.api-tls.enabled=true","server.address=127.0.0.1","spring.security.user.name=tls-test"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ApiTlsIntegrationTest {
    private static final Path ROOT=certificate();
    private static final int TLS_PORT=port();
    private static final String PASSWORD=UUID.randomUUID().toString();
    @LocalServerPort int httpPort;
    private static int port(){try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}
        catch(Exception e){throw new IllegalStateException("Cannot reserve isolated TLS port");}}
    private static Path certificate(){try{
        var root=Files.createTempDirectory("edgeai-api-tls-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var process=new ProcessBuilder("openssl","req","-x509","-nodes","-newkey","rsa:2048","-days","1",
            "-subj","/CN=localhost","-addext","subjectAltName=DNS:localhost","-keyout",root.resolve("key.pem").toString(),
            "-out",root.resolve("certificate.pem").toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException();}
        if(process.exitValue()!=0)throw new IllegalStateException();
        Files.setPosixFilePermissions(root.resolve("key.pem"),PosixFilePermissions.fromString("rw-------"));return root;
    }catch(Exception e){throw new IllegalStateException("Cannot create isolated API TLS identity; private details suppressed");}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("edgeai.api-tls.port",()->TLS_PORT);
        registry.add("edgeai.api-tls.certificate-file",()->ROOT.resolve("certificate.pem").toString());
        registry.add("edgeai.api-tls.private-key-file",()->ROOT.resolve("key.pem").toString());
        registry.add("spring.security.user.password",()->PASSWORD);
    }
    @AfterAll static void clean()throws Exception{try(var files=Files.walk(ROOT)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    private HttpClient trusted()throws Exception{
        var keys=KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null,null);
        try(var input=Files.newInputStream(ROOT.resolve("certificate.pem"))){keys.setCertificateEntry("test",CertificateFactory.getInstance("X.509").generateCertificate(input));}
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(keys);
        var tls=SSLContext.getInstance("TLS");tls.init(null,managers.getTrustManagers(),null);
        return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build();
    }
    private HttpResponse<Void> request(HttpClient client,String origin,String path,boolean auth,boolean write)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(5));
        if(auth)builder.header("Authorization","Basic "+Base64.getEncoder().encodeToString(("tls-test:"+PASSWORD).getBytes(StandardCharsets.UTF_8)));
        if(write)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        return client.send(builder.build(),HttpResponse.BodyHandlers.discarding());
    }
    @Test void realHttpAndHttpsApplyTheSameHealthAuthenticationAndCsrfRules()throws Exception{
        try(var client=trusted()){
            for(String origin:List.of("http://127.0.0.1:"+httpPort,"https://localhost:"+TLS_PORT)){
                var health=request(client,origin,"/actuator/health/readiness",false,false);assertThat(health.statusCode()).isEqualTo(200);
                if(origin.startsWith("https"))assertThat(health.sslSession().orElseThrow().getProtocol()).isIn("TLSv1.2","TLSv1.3");
                assertThat(request(client,origin,"/api/v1/profiles/SERVICE",false,false).statusCode()).isEqualTo(401);
                assertThat(request(client,origin,"/api/v1/profiles/SERVICE",true,false).statusCode()).isEqualTo(200);
                assertThat(request(client,origin,"/api/v1/workflows",true,true).statusCode()).isEqualTo(403);
                assertThat(request(client,origin,"/internal/v1/attempts/"+UUID.randomUUID()+"/claim",true,true).statusCode()).isIn(401,403);
            }
        }
    }
    @Test void untrustedCertificateAndWrongHostnameFailTlsHandshake()throws Exception{
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()){
            assertThatThrownBy(()->request(client,"https://localhost:"+TLS_PORT,"/actuator/health/readiness",false,false)).isInstanceOf(SSLHandshakeException.class);
        }
        try(var client=trusted()){
            assertThatThrownBy(()->request(client,"https://127.0.0.1:"+TLS_PORT,"/actuator/health/readiness",false,false)).isInstanceOf(SSLHandshakeException.class);
        }
    }
}
