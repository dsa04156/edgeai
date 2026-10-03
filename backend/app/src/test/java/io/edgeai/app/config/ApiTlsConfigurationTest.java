package io.edgeai.app.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerException;
import static org.assertj.core.api.Assertions.*;

class ApiTlsConfigurationTest {
    @TempDir Path folder;
    private ApplicationContextRunner context(){return new ApplicationContextRunner()
        .withInitializer(c->c.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
        .withUserConfiguration(ApiTlsConfiguration.class)
        .withPropertyValues("server.port=18080","server.address=127.0.0.1","edgeai.api-tls.port=18443",
            "edgeai.api-tls.certificate-file=","edgeai.api-tls.private-key-file=");}
    @Test void disabledByDefaultNeedsNoTlsMaterial(){
        context().run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean("apiTlsConnector");});
    }
    @Test void enabledWithoutReadableMaterialFailsConfiguration(){
        context().withPropertyValues("edgeai.api-tls.enabled=true").run(c->{assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasRootCauseMessage("API TLS certificate and private key must be readable, bounded absolute files");});
    }
    @Test void invalidAndConflictingPortsFailBeforeOpeningAListener()throws Exception{
        var certificate=folder.resolve("certificate.pem");var key=folder.resolve("key.pem");
        Files.writeString(certificate,"test-only");Files.writeString(key,"test-only");
        for(int port:new int[]{0,-1,65536,18080})context().withPropertyValues("edgeai.api-tls.enabled=true","edgeai.api-tls.port="+port,
            "edgeai.api-tls.certificate-file="+certificate,"edgeai.api-tls.private-key-file="+key).run(c->{
                assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasRootCauseMessage("Additional API TLS port must be distinct and between 1 and 65535");});
    }
    @Test void invalidPemPreventsTheActualTomcatServerFromStarting()throws Exception{
        var certificate=folder.resolve("invalid-certificate.pem");var key=folder.resolve("invalid-key.pem");
        Files.writeString(certificate,"not a certificate");Files.writeString(key,"not a private key");
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        var factory=new TomcatServletWebServerFactory(0);
        new ApiTlsConfiguration().apiTlsConnector(port,0,java.net.InetAddress.getLoopbackAddress(),certificate.toString(),key.toString()).customize(factory);
        assertThatThrownBy(()->{var server=factory.getWebServer();try{server.start();}finally{server.stop();}}).isInstanceOf(WebServerException.class);
    }
}
