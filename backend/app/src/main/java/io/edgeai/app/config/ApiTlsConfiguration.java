package io.edgeai.app.config;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Both connectors use the same servlet context and authentication/filter chains. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="edgeai.api-tls.enabled",havingValue="true")
class ApiTlsConfiguration {
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> apiTlsConnector(
            @Value("${edgeai.api-tls.port}") int port,@Value("${server.port}") int primaryPort,
            @Value("${server.address}") InetAddress address,
            @Value("${edgeai.api-tls.certificate-file}") String certificate,
            @Value("${edgeai.api-tls.private-key-file}") String key) {
        if(port<1 || port>65535 || port==primaryPort)throw new IllegalArgumentException("Additional API TLS port must be distinct and between 1 and 65535");
        var certificatePath=readable(certificate);var keyPath=readable(key);
        return factory->{
            var connector=new Connector(Http11NioProtocol.class.getName());
            connector.setPort(port);connector.setScheme("https");connector.setSecure(true);
            var protocol=(Http11NioProtocol)connector.getProtocolHandler();
            protocol.setAddress(address);protocol.setSSLEnabled(true);
            var host=new SSLHostConfig();host.setProtocols("TLSv1.2,TLSv1.3");
            var identity=new SSLHostConfigCertificate(host,SSLHostConfigCertificate.Type.UNDEFINED);
            identity.setCertificateFile(certificatePath.toString());identity.setCertificateKeyFile(keyPath.toString());
            host.addCertificate(identity);protocol.addSslHostConfig(host);
            factory.addAdditionalConnectors(connector);
        };
    }
    private static Path readable(String file){
        try{
            var path=Path.of(file);
            if(!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path) || Files.size(path)<1 || Files.size(path)>1048576)
                throw new IllegalArgumentException();
            return path;
        }catch(Exception error){throw new IllegalArgumentException("API TLS certificate and private key must be readable, bounded absolute files");}
    }
}
