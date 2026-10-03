package io.edgeai.app.config;
import io.edgeai.app.service.DeviceStreamTokenService;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name={"edgeai.stream.enabled","edgeai.stream.bindings-enabled"},havingValue="true")
class StreamBindingConfiguration {
    @Bean DeviceStreamTokenService deviceStreamTokens(@Value("${edgeai.stream.device-key-file}") String file){return new DeviceStreamTokenService(Path.of(file));}
    @Bean StreamConnectionSettings streamConnection(@Value("${edgeai.stream.broker-url}") String broker,@Value("${edgeai.stream.client-url:}") String client,
            @Value("${edgeai.stream.ca-file}") String ca){return StreamConnectionSettings.read(client.isBlank()?broker:client,Path.of(ca));}
}
