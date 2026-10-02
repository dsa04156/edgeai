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
    private final VDLifecycleService lifecycle;
    public VirtualDeviceService(VirtualDeviceRepository repository,DeviceRepository devices,ProfileRepository profiles,NodeRepository nodes,Clock clock,VDLifecycleService lifecycle) {
        this.repository=repository;this.devices=devices;this.profiles=profiles;this.nodes=nodes;this.clock=clock;this.lifecycle=lifecycle;
    }
    @Transactional
    public VirtualDeviceRepository.Creation create(String body) {
        var input=VirtualDeviceInput.create(body);
        // Retransmission must not revalidate sources that have since been replaced or released.
        var previous=repository.findByKey(input.key());
        if(previous.isPresent())return replay(previous.get(),input.digest());
        var spec=profile(input.profileVersionId());validatePlacement(input.configuration().placement());
        boolean created=repository.create(input.key(),input.configuration().displayName(),input.profileVersionId(),spec.serviceProfileVersionId(),input.configuration().placement(),input.digest());
        var value=repository.findByKey(input.key()).orElseThrow();
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
        var configuration=input.configuration();var spec=profile(vd.profileVersionId());validatePlacement(configuration.placement());
        var devicesById=validateSources(spec,configuration.sources());var active=repository.activeSources(id);
        var current=new TreeMap<String,UUID>();active.forEach(b->current.put(b.sourceKey(),b.deviceId()));
        if(vd.displayName().equals(configuration.displayName()) && vd.placement().equals(configuration.placement()) && current.equals(configuration.sources()))return vd;
        var now=now(vd);repository.update(id,configuration.displayName(),configuration.placement(),vd.state(),now);
        var updated=find(id,false);bindChanges(updated,active,configuration.sources(),devicesById,now);lifecycle.registryChanged(updated);return updated;
    }
    @Transactional
    public VirtualDevice release(UUID id) {
        var vd=find(id,true);if(vd.state()==VirtualDevice.State.RELEASED)return vd;
        var now=now(vd);repository.update(id,vd.displayName(),vd.placement(),VirtualDevice.State.RELEASED,now);
        for(var binding:repository.activeSources(id))repository.close(binding.id(),vd.revision()+1,now);
        var released=find(id,false);lifecycle.registryChanged(released);return released;
    }
    private void bindChanges(VirtualDevice vd,List<VDSourceBinding> current,Map<String,UUID> requested,Map<UUID,Device> sources,Instant now) {
        var unchanged=new HashSet<String>();
        for(var binding:current) {
            if(binding.deviceId().equals(requested.get(binding.sourceKey())))unchanged.add(binding.sourceKey());
            else repository.close(binding.id(),vd.revision(),now);
        }
        requested.forEach((key,id)->{
            if(!unchanged.contains(key)) {
                var device=sources.get(id);
                repository.bind(new VDSourceBinding(UUID.randomUUID(),vd.id(),key,id,device.profileVersionId(),device.sourceMode(),vd.revision(),null,now,null));
            }
        });
    }
    private VDProfileSpec profile(UUID id) {
        var spec=VirtualDeviceInput.spec(requireProfile(id,ProfileIdentity.Kind.VD).specJson());
        ServiceExecutionInput.parseSpec(requireProfile(spec.serviceProfileVersionId(),ProfileIdentity.Kind.SERVICE).specJson());
        for(var source:spec.sources().values())requireProfile(source.deviceProfileVersionId(),ProfileIdentity.Kind.DEVICE);
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
    private Map<UUID,Device> validateSources(VDProfileSpec spec,Map<String,UUID> requested) {
        if(!spec.sources().keySet().containsAll(requested.keySet()) || spec.sources().entrySet().stream()
            .anyMatch(e->e.getValue().required() && !requested.containsKey(e.getKey())))
            throw error(409,"VD_SOURCE_INCOMPATIBLE","선언된 원본 키와 필수 원본을 확인하세요.");
        var sources=new HashMap<UUID,Device>();
        // Stable ordering prevents overlapping VD source sets from locking Devices in reverse order.
        for(var id:requested.values().stream().distinct().sorted().toList()) {
            var device=devices.find(id,true).orElseThrow(()->error(404,"VD_NOT_FOUND","원본 Device가 없습니다."));
            if(device.state()!=Device.State.ACTIVE)throw error(409,"VD_SOURCE_INCOMPATIBLE","해제된 Device는 원본으로 연결할 수 없습니다.");
            sources.put(id,device);
        }
        requested.forEach((key,id)->{
            var requirement=spec.sources().get(key);var device=sources.get(id);
            if(!requirement.deviceProfileVersionId().equals(device.profileVersionId()) || !requirement.sourceModes().contains(device.sourceMode()))
                throw error(409,"VD_SOURCE_INCOMPATIBLE","원본 Device의 Profile 버전 또는 sourceMode가 VD 조건과 다릅니다.");
        });
        return sources;
    }
    private Instant now(VirtualDevice vd) { var now=clock.instant();return now.isBefore(vd.updatedAt())?vd.updatedAt():now; }
    private static ControlPlaneException error(int status,String code,String message) { return new ControlPlaneException(status,code,message); }
}
