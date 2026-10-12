package io.edgeai.app.service;

import io.edgeai.domain.node.SensorRegistrationSource;
import io.edgeai.domain.node.SensorRegistrationSource.*;
import io.edgeai.app.exception.ControlPlaneException;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SensorRegistrationService {
    private final SensorRegistrationSource source;
    private final InfrastructureService infrastructure;
    public SensorRegistrationService(ObjectProvider<SensorRegistrationSource> sources, InfrastructureService infrastructure) {
        source = sources.getIfAvailable(); this.infrastructure = infrastructure;
    }
    private <T> T call(Supplier<T> work) {
        if (source == null) throw new ControlPlaneException(410, "SENSOR_API_REPLACED", "/api/devices의 KubeEdge 장치 API를 사용하세요.");
        try { return work.get(); }
        catch (Failure e) {
            throw new ControlPlaneException(switch (e.kind()) { case INVALID -> 400; case CONFLICT -> 409; case UNAVAILABLE, UNKNOWN -> 502; },
                "SENSOR_REGISTRATION_" + e.kind().name(), e.getMessage());
        }
    }
    public Catalog catalog() { return call(() -> source.catalog()); }
    public Result register(Request request) {
        try { return call(() -> source.register(request)); }
        finally { infrastructure.invalidate(); }
    }
}
