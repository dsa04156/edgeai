package io.edgeai.app.service;

import io.edgeai.app.dto.*;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.VirtualDevice;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContinuousExecutionService {
    public enum Action { PROVISION, REPLACE, DRAIN }
    private final ContinuousRepository executions;
    private final VirtualDeviceRepository vds;
    private final ProfileRepository profiles;
    private final Clock clock;
    private final boolean enabled;
    private final long storageBudget;
    public ContinuousExecutionService(ContinuousRepository executions,VirtualDeviceRepository vds,ProfileRepository profiles,Clock clock,
            @Value("${edgeai.continuous.enabled:false}") boolean enabled,@Value("${edgeai.continuous.history-budget-gib:300}") long budget){this.executions=executions;this.vds=vds;this.profiles=profiles;this.clock=clock;this.enabled=enabled;if(budget<1 || budget>10000)throw new IllegalArgumentException("Invalid history storage budget");this.storageBudget=budget*1073741824L;}
    public boolean owns(UUID id){return vds.find(id,false).map(v->v.serviceProfileVersionId()==null).orElse(false);}
    public boolean enabled(){return enabled;}
    @Transactional
    public Creation<VDOperationResponse> request(UUID id,String key,String body,Action action) {
        if(!enabled)throw new ControlPlaneException(503,"CONTINUOUS_DISABLED","연속 처리 기능이 비활성화되어 있습니다.");
        UUID requestKey=WorkflowInput.uuid(key);long revision=VirtualDeviceInput.lifecycleRevision(body);
        var vd=vds.find(id,true).orElseThrow(()->new ControlPlaneException(404,"VD_NOT_FOUND","가상디바이스가 없습니다."));
        String desired=action==Action.DRAIN?"STOP":action==Action.REPLACE?"REPLACE":"START";
        var previous=executions.operationByKey(id,requestKey);
        if(previous.isPresent()) {
            var operation=previous.get();
            if(operation.requestedRevision()!=revision || !operation.kind().equals("CONTINUOUS_"+desired))throw new ControlPlaneException(409,"VD_CONFLICT","같은 요청 키의 실행 명령이 다릅니다.");
            return new Creation<>(VDOperationResponse.from(operation),false);
        }
        if(vd.revision()!=revision || vd.state()==VirtualDevice.State.RELEASED && !desired.equals("STOP"))throw new ControlPlaneException(409,"VD_CONFLICT","최신 revision과 등록 상태를 확인하세요.");
        executions.lockCapacity();
        if(!desired.equals("STOP")) {
            int before=executions.find(id).filter(ContinuousRepository.Execution::running).isPresent()?1:0;
            var config=DeviceProfileCompiler.map(WorkflowInput.JSON.decode(VirtualDeviceInput.spec(profiles.find(vd.profileVersionId()).orElseThrow().specJson()).continuousJson()));
            int count=((List<?>)config.get("outputs")).size();
            if(executions.activeDevices()+1-before>200 || executions.activeOutputs()+(before==0?count:0)>500)throw new ControlPlaneException(409,"CONTINUOUS_CAPACITY_EXCEEDED","공유 엔진의 검증된 실행 용량을 초과합니다.");
            if(executions.estimatedHistoryBytes()+(before==0?ContinuousCapacity.historyBytes(config,vds.activeSources(id).size()):0)>storageBudget)throw new ControlPlaneException(409,"CONTINUOUS_STORAGE_CAPACITY_EXCEEDED","계산 주기와 보존 기간의 예상 이력이 저장 용량을 초과합니다.");
        }
        return new Creation<>(VDOperationResponse.from(executions.request(id,requestKey,revision,desired,clock.instant())),true);
    }
    public Optional<VDOperationResponse> operation(UUID id){return executions.operation(id).map(VDOperationResponse::from);}
    public Map<String,Object> state(UUID id) {
        var result=new TreeMap<String,Object>();result.put("executionMode","CONTINUOUS_EKUIPER");result.put("enabled",enabled);
        var execution=executions.find(id);result.put("state",execution.map(ContinuousRepository.Execution::state).orElse("STOPPED"));
        result.put("reason",execution.map(ContinuousRepository.Execution::reason).orElse("NOT_STARTED"));
        execution.ifPresent(e->{result.put("generation",e.generation().toString());result.put("desiredRunning",e.running());result.put("updatedAt",e.updatedAt().toString());});
        executions.session("central").ifPresent(s->{result.put("engineId",s.engineId());result.put("enginePodUid",s.podUid().toString());result.put("engineLeaseExpiresAt",s.expiresAt().toString());if(s.expiresAt().isBefore(clock.instant()) && execution.map(ContinuousRepository.Execution::running).orElse(false)){result.put("state","ERROR");result.put("reason","ENGINE_LEASE_EXPIRED");}});
        return result;
    }
    public void registrationChanged(VirtualDevice vd) {
        var current=executions.find(vd.id());
        if(current.isPresent() && current.get().running())executions.request(vd.id(),UUID.randomUUID(),vd.revision(),vd.state()==VirtualDevice.State.RELEASED?"STOP":"REPLACE",clock.instant());
    }
    public void validateEdit(UUID id,Object config,int inputCount) {
        executions.lockCapacity();
        if(executions.find(id).filter(ContinuousRepository.Execution::running).isEmpty())return;
        var current=DeviceProfileCompiler.map(WorkflowInput.JSON.decode(VirtualDeviceInput.spec(profiles.find(vds.find(id,false).orElseThrow().profileVersionId()).orElseThrow().specJson()).continuousJson()));
        long bytes=executions.estimatedHistoryBytes()-ContinuousCapacity.historyBytes(current,vds.activeSources(id).size())+ContinuousCapacity.historyBytes(config,inputCount);
        int count=executions.activeOutputs()-((List<?>)current.get("outputs")).size()+((List<?>)DeviceProfileCompiler.map(config).get("outputs")).size();
        if(count>500 || bytes>storageBudget)throw new ControlPlaneException(409,"CONTINUOUS_CAPACITY_EXCEEDED","수정 후 실행·이력 용량이 제한을 초과합니다.");
    }
}
