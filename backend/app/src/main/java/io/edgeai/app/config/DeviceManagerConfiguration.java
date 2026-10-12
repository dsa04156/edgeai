package io.edgeai.app.config;

import io.edgeai.adapters.kubernetes.KubeEdgeDeviceResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
class DeviceManagerConfiguration {
    @Bean
    io.edgeai.domain.node.SensorInventorySource kubeEdgeSensorInventory(io.edgeai.app.service.ManagedDeviceService service) {
        return () -> ((java.util.List<?>)service.list().get("items")).stream().map(value -> {
            var d=io.edgeai.app.support.DeviceProfileCompiler.map(value);
            var profile=io.edgeai.app.support.DeviceProfileCompiler.map(d.get("profile"));
            var properties=((java.util.List<?>)profile.get("properties")).stream().map(p -> (String)io.edgeai.app.support.DeviceProfileCompiler.map(p).get("name")).toList();
            return new io.edgeai.domain.node.InfrastructureSource.Sensor((String)d.get("id"),(String)d.get("targetNode"),(String)d.get("model"),"KubeEdge Mapper","UNLOCKED",(String)d.get("state"),properties);
        }).toList();
    }

    @Bean(destroyMethod="close") @ConditionalOnProperty(name="edgeai.infrastructure.source",havingValue="api")
    KubeEdgeDeviceResources apiDeviceResources(@Value("${edgeai.kubernetes.url}") String url,@Value("${edgeai.kubernetes.token-file:}") String token,@Value("${edgeai.kubernetes.ca-file:}") String ca,@Value("${edgeai.devices.namespace:edgeai-devices}") String ns){return new KubeEdgeDeviceResources(url,token,ca,ns);}
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="edgeai.infrastructure.source",havingValue="kubectl")
    KubeEdgeDeviceResources localDeviceResources(@Value("${edgeai.infrastructure.context:}") String context,@Value("${edgeai.devices.namespace:edgeai-devices}") String ns){return new KubeEdgeDeviceResources(context,ns);}
}
