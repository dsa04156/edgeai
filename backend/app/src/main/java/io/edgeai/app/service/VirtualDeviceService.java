package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.Device;
import io.edgeai.domain.profile.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class VirtualDeviceService {
    private final VirtualDeviceRepository repository;
    private final DeviceRepository devices;
    private final ProfileRepository profiles;
    private final NodeRepository nodes;
    private final Clock clock;
    private final ManagedDeviceService managed;

    @org.springframework.beans.factory.annotation.Autowired private ContinuousExecutionService continuous;
    public VirtualDeviceService(VirtualDeviceRepository repository,DeviceRepository devices,ProfileRepository profiles,NodeRepository nodes,Clock clock,ManagedDeviceService managed) {
        this.repository=repository;this.devices=devices;this.profiles=profiles;this.nodes=nodes;this.clock=clock;this.managed=managed;
    }
    @Transactional
    public VirtualDeviceRepository.Creation create(String body) {
        var input=VirtualDeviceInput.create(body);
        // Retransmission must not revalidate sources that have since been replaced or released.
        var previous=repository.findByKey(input.key());
        if(previous.isPresent())return replay(previous.get(),input.digest());
        var spec=profile(input.profileVersionId());validatePlacement(input.configuration().placement());
        if(spec.continuous() && input.configuration().placement().nodeId()!=null)throw new IllegalArgumentException("Continuous processing uses the shared engine placement");
        boolean created=repository.create(input.key(),input.configuration().displayName(),input.profileVersionId(),spec.serviceProfileVersionId(),input.configuration().placement(),input.digest());
        var value=repository.findByKey(input.key()).orElseThrow(()->error(409,"VD_CONFLICT","이미 사용된 가상 센서 키입니다."));
        if(!created)return replay(value,input.digest());
        var sources=validateSources(spec,input.configuration().sources());
        bindChanges(value,List.of(),input.configuration().sources(),sources,now(value));
        return new VirtualDeviceRepository.Creation(value,true);
    }
    private VirtualDeviceRepository.Creation replay(VirtualDevice value,String digest) {
        if(!value.creationDigest().equals(digest))throw error(409,"VD_CONFLICT","같은 VD 키에 다른 생성 입력이 있습니다.");
        return new VirtualDeviceRepository.Creation(value,false);
    }
    public List<VirtualDevice> list(int limit,int offset) { NodeService.page(limit,offset);return repository.list(limit+1,offset); }
    private VirtualDevice find(UUID id,boolean lock) {
        return repository.find(id,lock).orElseThrow(()->error(404,"VD_NOT_FOUND","VD를 찾을 수 없습니다."));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public VirtualDeviceRepository.Snapshot detail(UUID id) {
        var vd=find(id,false);var history=repository.sourceHistory(id,101);
        return new VirtualDeviceRepository.Snapshot(vd,repository.activeSources(id),history.stream().limit(100).toList(),history.size()>100);
    }
    @Transactional
    public VirtualDevice update(UUID id,String body) {
        var input=VirtualDeviceInput.update(body);var vd=find(id,true);
        if(vd.state()==VirtualDevice.State.RELEASED)throw error(409,"VD_RELEASED","해제된 VD는 수정할 수 없습니다.");
        if(vd.revision()!=input.revision())throw error(409,"VD_CONFLICT","VD가 변경되었습니다. 다시 조회한 revision으로 수정하세요.");
        var configuration=input.configuration();UUID profileId=input.profileVersionId()==null?vd.profileVersionId():input.profileVersionId();var spec=profile(profileId);validatePlacement(configuration.placement());
        if(spec.continuous()!=profile(vd.profileVersionId()).continuous())throw new IllegalArgumentException("Execution mode cannot change; register a new virtual device");
        if(spec.continuous() && configuration.placement().nodeId()!=null)throw new IllegalArgumentException("Continuous processing uses the shared engine placement");
        var devicesById=validateSources(spec,configuration.sources());var active=repository.activeSources(id);
        if(spec.continuous() && continuous!=null)continuous.validateEdit(id,WorkflowInput.JSON.decode(spec.continuousJson()),configuration.sources().size());
        var current=new TreeMap<String,VDSourceSelection>();active.forEach(b->current.put(b.sourceKey(),new VDSourceSelection(b.sourceId(),b.properties(),b.maxAgeSeconds())));
        if(profileId.equals(vd.profileVersionId()) && vd.displayName().equals(configuration.displayName()) && vd.placement().equals(configuration.placement()) && current.equals(configuration.sources())
            && active.stream().allMatch(b->sameIdentity(b,devicesById.get(b.sourceKey()))))return vd;
        var now=now(vd);repository.reconfigure(id,configuration.displayName(),profileId,spec.serviceProfileVersionId(),configuration.placement(),now);
        var updated=find(id,false);bindChanges(updated,active,configuration.sources(),devicesById,now);if(continuous!=null)continuous.registrationChanged(updated);return updated;
    }
    @Transactional
    public VirtualDevice release(UUID id) {
        var vd=find(id,true);if(vd.state()==VirtualDevice.State.RELEASED)return vd;
        var now=now(vd);repository.update(id,vd.displayName(),vd.placement(),VirtualDevice.State.RELEASED,now);
        for(var binding:repository.activeSources(id))repository.close(binding.id(),vd.revision()+1,now);
        var released=find(id,false);if(continuous!=null)continuous.registrationChanged(released);return released;
    }
    @Transactional
    public void deleteRegistration(UUID id) {
        var vd=find(id,true);
        if(vd.state()!=VirtualDevice.State.RELEASED) throw error(409,"VD_IN_USE","가상 장치를 먼저 해제하고 실행 종료를 확인하세요.");
        if(!repository.canDeleteRegistration(id)) throw error(409,"VD_IN_USE","실행·작업 이력이 참조하는 가상 장치는 영구 삭제할 수 없습니다.");
        try {repository.deleteRegistration(id);}
        catch(org.springframework.dao.DataIntegrityViolationException e) {throw error(409,"VD_IN_USE","연결 또는 실행 참조가 남아 있어 삭제할 수 없습니다. 새로고침 후 확인하세요.");}
    }
    private void bindChanges(VirtualDevice vd,List<VDSourceBinding> current,Map<String,VDSourceSelection> requested,Map<String,VDSourceBinding> sources,Instant now) {
        var unchanged=new HashSet<String>();
        for(var binding:current) {
            if(sameIdentity(binding,sources.get(binding.sourceKey())))unchanged.add(binding.sourceKey());
            else repository.close(binding.id(),vd.revision(),now);
        }
        requested.forEach((key,selection)->{
            if(!unchanged.contains(key)) {
                var source=sources.get(key);
                repository.bind(new VDSourceBinding(UUID.randomUUID(),vd.id(),key,source.deviceId(),source.deviceProfileVersionId(),source.sourceMode(),vd.revision(),null,now,null,source.managedDeviceId(),source.deviceUid(),source.deviceModel(),source.properties(),source.maxAgeSeconds()));
            }
        });
    }
    private static boolean sameIdentity(VDSourceBinding binding,VDSourceBinding source) {
        return source!=null && binding.sourceId().equals(source.sourceId()) && Objects.equals(binding.deviceUid(),source.deviceUid()) && Objects.equals(binding.deviceModel(),source.deviceModel())
            && binding.properties().equals(source.properties()) && binding.maxAgeSeconds()==source.maxAgeSeconds();
    }
    private VDProfileSpec profile(UUID id) {
        var spec=VirtualDeviceInput.spec(requireProfile(id,ProfileIdentity.Kind.VD).specJson());
        return spec;
    }
    private ProfileVersion requireProfile(UUID id,ProfileIdentity.Kind kind) {
        var profile=profiles.find(id).orElseThrow(()->error(404,"VD_NOT_FOUND","참조할 Profile 버전이 없습니다."));
        if(profile.identity().kind()!=kind)throw new IllegalArgumentException("Profile kind mismatch");
        return profile;
    }
    private void validatePlacement(VirtualDevice.Placement placement) {
        if(placement.nodeId()!=null && nodes.find(placement.nodeId()).isEmpty())throw error(404,"VD_NOT_FOUND","배치 대상으로 지정한 Node가 없습니다.");
    }
    private Map<String,VDSourceBinding> validateSources(VDProfileSpec spec,Map<String,VDSourceSelection> requested) {
        if(!spec.sources().keySet().containsAll(requested.keySet()) || spec.sources().entrySet().stream()
            .anyMatch(e->e.getValue().required() && !requested.containsKey(e.getKey())))
            throw error(409,"VD_SOURCE_INCOMPATIBLE","선언된 원본 키와 필수 원본을 확인하세요.");
        var sources=new HashMap<String,VDSourceBinding>();
        var continuousTypes=new TreeMap<String,ProcessingExpression.Type>();
        repository.lockManagedSources(requested.values().stream().map(VDSourceSelection::deviceId).toList());
        for(var entry:requested.entrySet()) {
            var selection=entry.getValue();var requirement=spec.sources().get(entry.getKey());
            var device=managed.source(selection.deviceId(),requirement.deviceModel(),null);
            var sensorProfile=DeviceProfileCompiler.map(device.get("profile"));
            var declared=(List<?>)sensorProfile.getOrDefault("properties",List.of());
            if(selection.properties().size()!=1 || declared.size()!=1)throw new IllegalArgumentException("Continuous inputs require one separate measurement per Device");
            var property=DeviceProfileCompiler.map(declared.getFirst());
            if(!selection.properties().getFirst().equals(property.get("name")))throw error(409,"VD_SOURCE_INCOMPATIBLE","선택한 측정 항목이 센서 규격에 없습니다.");
            continuousTypes.put(entry.getKey(),ContinuousRuleCompiler.inputType((String)property.get("dataType")));
            sources.put(entry.getKey(),new VDSourceBinding(null,null,null,null,null,"virtual".equals(sensorProfile.get("kind"))?Device.SourceMode.SYNTHETIC:Device.SourceMode.LIVE,
                0,null,null,null,selection.deviceId(),UUID.fromString((String)device.get("uid")),(String)device.get("model"),selection.properties(),selection.maxAgeSeconds()));
        }
        if(spec.continuous())ContinuousRuleCompiler.compile(new UUID(0,0),new UUID(0,0),WorkflowInput.JSON.decode(spec.continuousJson()),continuousTypes);
        return sources;
    }
    private Instant now(VirtualDevice vd) { var now=clock.instant();return now.isBefore(vd.updatedAt())?vd.updatedAt():now; }
    private static ControlPlaneException error(int status,String code,String message) { return new ControlPlaneException(status,code,message); }
}
