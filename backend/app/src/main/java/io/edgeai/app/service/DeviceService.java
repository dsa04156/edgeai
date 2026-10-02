package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.DeviceInput;
import io.edgeai.domain.device.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DeviceService {
    private final DeviceRepository repository;
    private final ProfileRepository profiles;
    private final NodeService nodes;
    private final Clock clock;
    public DeviceService(DeviceRepository repository,ProfileRepository profiles,NodeService nodes,Clock clock) {
        this.repository=repository;this.profiles=profiles;this.nodes=nodes;this.clock=clock;
    }
    @Transactional
    public DeviceRepository.Creation<Device> create(String body) {
        var input=new DeviceInput(body,"key","displayName","profileVersionId","sourceMode");
        String key=input.text("key",100), name=input.text("displayName",128);
        ProfileIdentity.validateKey(key);
        var mode=Device.SourceMode.valueOf(input.text("sourceMode",16));
        UUID profileId=input.uuid("profileVersionId");
        var profile=profiles.find(profileId).orElseThrow(()->error(404,"DEVICE_NOT_FOUND","참조할 Profile 버전이 없습니다."));
        if (profile.identity().kind()!=ProfileIdentity.Kind.DEVICE) throw new IllegalArgumentException("DEVICE profile required");
        String digest=DeviceInput.JSON.digest("edgeai-device-create-v1",Map.of("key",key,"displayName",name,"profileVersionId",profileId.toString(),"sourceMode",mode.name()));
        boolean created=repository.create(key,name,profileId,mode,digest);
        Device value=repository.findByKey(key).orElseThrow();
        if (!value.creationDigest().equals(digest)) throw error(409,"DEVICE_CONFLICT","같은 장치 키에 다른 생성 입력이 있습니다.");
        return new DeviceRepository.Creation<>(value,created);
    }
    public List<Device> list(int limit,int offset) { NodeService.page(limit,offset);return repository.list(limit+1,offset); }
    private Device find(UUID id,boolean lock) { return repository.find(id,lock).orElseThrow(()->error(404,"DEVICE_NOT_FOUND","장치를 찾을 수 없습니다.")); }
    private Device active(UUID id) {
        var device=find(id,true);
        if (device.state()==Device.State.RELEASED) throw error(409,"DEVICE_RELEASED","해제된 장치입니다. 새 장치를 등록하세요.");
        return device;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DeviceSnapshot detail(UUID id) { return new DeviceSnapshot(find(id,false),repository.attachments(id),repository.sessions(id),repository.observations(id)); }
    @Transactional
    public Device rename(UUID id,String body) {
        var input=new DeviceInput(body,"revision","displayName");
        long revision=input.number("revision");String name=input.text("displayName",128);
        var device=active(id);
        if (revision!=device.revision()) throw error(409,"DEVICE_CONFLICT","다른 요청이 장치를 수정했습니다. 다시 조회하세요.");
        if (!device.displayName().equals(name)) repository.rename(id,name,clock.instant());
        return find(id,false);
    }
    @Transactional
    public Device release(UUID id) {
        var device=find(id,true);
        if (device.state()==Device.State.RELEASED) return device;
        if (repository.hasVirtualDeviceBindings(id)) throw error(409,"DEVICE_IN_USE","VD의 원본으로 연결된 장치입니다. VD 연결을 교체하거나 해제한 후 다시 시도하세요.");
        var now=clock.instant();repository.closeSessions(id,now);repository.closeAttachments(id,now);repository.release(id,now);
        return find(id,false);
    }
    @Transactional
    public DeviceAttachment attach(UUID id,UUID nodeId,String body) {
        String port=new DeviceInput(body,"port").text("port",128);
        active(id);
        if (!nodes.find(nodeId).status(clock.instant()).equals("READY")) throw error(409,"NODE_NOT_READY","노드가 Ready 상태인지 새 관측을 확인하세요.");
        var previous=repository.activeAttachment(id);
        if (previous.isPresent() && previous.get().nodeId().equals(nodeId) && previous.get().port().equals(port)) return previous.get();
        var now=clock.instant();repository.closeAttachments(id,now);
        var attachment=new DeviceAttachment(UUID.randomUUID(),id,nodeId,port,now,null);repository.attach(attachment);return attachment;
    }
    @Transactional
    public DeviceRepository.Creation<DeviceSession> openSession(UUID id,String body) {
        UUID bootId=new DeviceInput(body,"bootId").uuid("bootId");
        var device=active(id);var existing=repository.sessionByBootId(id,bootId);
        if (existing.isPresent()) {
            if (existing.get().closedAt()!=null) throw error(409,"STALE_SESSION","닫힌 bootId입니다. 새 재접속 UUID를 사용하세요.");
            return new DeviceRepository.Creation<>(existing.get(),false);
        }
        var now=clock.instant();repository.closeSessions(id,now);repository.advanceEpoch(id,now);
        var session=new DeviceSession(UUID.randomUUID(),id,bootId,device.sessionEpoch()+1,-1,now,null);
        repository.openSession(session);return new DeviceRepository.Creation<>(session,true);
    }
    @Transactional
    public DeviceRepository.Creation<DeviceObservation> observe(UUID id,String body) {
        var input=new DeviceInput(body,"sessionId","sequence","observedAt","status","attributes");
        UUID sessionId=input.uuid("sessionId");long sequence=input.number("sequence");
        var observedAt=input.instant("observedAt").truncatedTo(java.time.temporal.ChronoUnit.MICROS);String status=input.text("status",8);
        if (!Set.of("ONLINE","OFFLINE").contains(status)) throw new IllegalArgumentException("Invalid status");
        var attributes=input.object("attributes");
        active(id);
        var now=clock.instant();
        if (observedAt.isAfter(now.plusSeconds(30)) || observedAt.isBefore(now.minusSeconds(86400))) throw new IllegalArgumentException("Timestamp out of bounds");
        var session=repository.activeSession(id).orElseThrow(()->error(409,"STALE_SESSION","활성 세션을 먼저 시작하세요."));
        if (!session.id().equals(sessionId)) throw error(409,"STALE_SESSION","이전 세션의 보고입니다. 현재 세션을 사용하세요.");
        String digest=DeviceInput.JSON.digest("edgeai-observation-v1",Map.of("sessionId",sessionId.toString(),"sequence",sequence,"observedAt",observedAt.toString(),"status",status,"attributes",attributes));
        var previous=repository.observation(sessionId,sequence);
        if (previous.isPresent()) {
            if (!previous.get().digest().equals(digest)) throw error(409,"OBSERVATION_CONFLICT","같은 sequence에 다른 보고가 있습니다.");
            return new DeviceRepository.Creation<>(previous.get(),false);
        }
        if (sequence<=session.lastSequence()) throw error(409,"OBSERVATION_CONFLICT","sequence는 이전 보고보다 커야 합니다.");
        var observation=new DeviceObservation(UUID.randomUUID(),id,sessionId,sequence,observedAt,now,status,DeviceInput.JSON.canonical(attributes),digest);
        repository.observe(observation);return new DeviceRepository.Creation<>(observation,true);
    }
    private static ControlPlaneException error(int status,String code,String message) { return new ControlPlaneException(status,code,message); }
}
