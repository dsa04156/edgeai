package io.edgeai.app.service;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.dto.*;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class VDExecutionService {
    private final VirtualDeviceRepository vds;
    private final VDRuntimeRepository runtimes;
    private final VDLifecycleService lifecycle;
    private final ObjectProvider<RuntimeSettings> settings;
    private final boolean vdEnabled;
    private final Clock clock;
    public VDExecutionService(VirtualDeviceRepository vds,VDRuntimeRepository runtimes,VDLifecycleService lifecycle,
            ObjectProvider<RuntimeSettings> settings,@Value("${edgeai.vd.enabled:false}") boolean vdEnabled,Clock clock) {
        this.vds=vds;this.runtimes=runtimes;this.lifecycle=lifecycle;this.settings=settings;this.vdEnabled=vdEnabled;this.clock=clock;
    }
    public enum Action { PROVISION,REPLACE,DRAIN }
    private boolean enabled() { return vdEnabled && settings.getIfAvailable()!=null; }
    @Transactional
    public Creation<VDOperationResponse> request(UUID id,String key,String body,Action action) {
        String requestKey="public:"+WorkflowInput.uuid(key);long revision=VirtualDeviceInput.lifecycleRevision(body);
        if(!enabled())throw new ControlPlaneException(503,"VD_EXECUTION_DISABLED","VD 실행 기능이 비활성화되어 있습니다.");
        vds.find(id,true).orElseThrow(()->new ControlPlaneException(404,"VD_NOT_FOUND","VD를 찾을 수 없습니다."));
        boolean created=runtimes.byKey(id,requestKey).isEmpty();
        var value=action==Action.DRAIN?lifecycle.drain(id,revision,requestKey)
            :lifecycle.provision(id,revision,requestKey,settings.getObject(),action==Action.REPLACE);
        return new Creation<>(VDOperationResponse.from(value),created);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public VDExecutionResponse status(UUID id) {
        vds.find(id,false).orElseThrow(()->new ControlPlaneException(404,"VD_NOT_FOUND","VD를 찾을 수 없습니다."));
        var now=clock.instant();var history=runtimes.history(id,101);var bindings=runtimes.bindings(id,101);var operations=runtimes.operations(id,101);
        return new VDExecutionResponse(id,enabled(),now,VDRuntimeResponse.from(runtimes.current(id).orElse(null),now),
            runtimes.pending(id).map(VDOperationResponse::from).orElse(null),history.stream().limit(100).map(r->VDRuntimeResponse.from(r,now)).toList(),history.size()>100,
            bindings.stream().limit(100).toList(),bindings.size()>100,operations.stream().limit(100).map(VDOperationResponse::from).toList(),operations.size()>100);
    }
    public Optional<VDOperationResponse> operation(UUID id) { return runtimes.operation(id).map(VDOperationResponse::from); }
}
