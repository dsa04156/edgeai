package io.edgeai.app.service;

import io.edgeai.domain.node.SensorAccessSource;
import io.edgeai.domain.node.SensorAccessSource.*;
import io.edgeai.app.exception.ControlPlaneException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SensorService {
    private final SensorAccessSource source;
    public SensorService(ObjectProvider<SensorAccessSource> sources) { source=sources.getIfAvailable(); }
    private <T> T call(Supplier<T> work) {
        if (source==null) throw new ControlPlaneException(503,"SENSOR_DISABLED","EdgeX 센서 연결이 비활성화되어 있습니다.");
        try { return work.get(); }
        catch (Failure e) { throw new ControlPlaneException(switch(e.kind()) {case NOT_FOUND->404;case INVALID->400;case LOCKED->409;case UNAVAILABLE->502;},"SENSOR_"+e.kind().name(),e.getMessage()); }
    }
    public Readings readings(String device,String resource,int limit) {return call(()->source.readings(device,resource,limit));}
    public Commands commands(String device) {return call(()->source.commands(device));}
    public CommandResult execute(String device,String command,String method,Map<String,String> values) {return call(()->source.execute(device,command,method,values));}
}
