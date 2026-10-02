package io.edgeai.app.service;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Durable VD lifecycle. Network/Pod observations are supplied by a separate authenticated gateway. */
@Service
public class VDLifecycleService {
    private final VirtualDeviceRepository vds;
    private final VDRuntimeRepository runtimes;
    private final ProfileRepository profiles;
    private final NodeRepository nodes;
    private final Clock clock;
    private static final JsonDocuments JSON=new JsonDocuments();
    public VDLifecycleService(VirtualDeviceRepository vds,VDRuntimeRepository runtimes,ProfileRepository profiles,NodeRepository nodes,Clock clock) {
        this.vds=vds;this.runtimes=runtimes;this.profiles=profiles;this.nodes=nodes;this.clock=clock;
    }
    @Transactional
    public VDOperation provision(UUID id,long revision,String key,RuntimeSettings settings,boolean replace) {
        var vd=lock(id);String digest=requestDigest(replace?"REPLACE":"PROVISION",revision,settings);
        var replay=replay(id,key,digest);if(replay!=null)return replay;
        if(vd.state()!=VirtualDevice.State.REGISTERED)throw error("VD_RELEASED","해제된 VD는 실행할 수 없습니다.");
        if(vd.revision()!=revision)throw error("VD_CONFLICT","현재 VD revision으로 요청하세요.");
        var current=runtimes.current(id).orElse(null);
        if(replace && current==null)throw error("VD_RUNTIME_NOT_FOUND","교체할 VD 실행이 없습니다.");
        if(current!=null && !current.namespace().equals(settings.namespace()))throw error("VD_SCOPE_CONFLICT","실행 namespace는 변경할 수 없습니다.");
        return begin(vd,key,digest,configuration(vd,settings),replace,false);
    }
    @Transactional
    public VDOperation drain(UUID id,long revision,String key) {
        var vd=lock(id);String digest=requestDigest("DRAIN",revision,null);
        var replay=replay(id,key,digest);if(replay!=null)return replay;
        if(vd.revision()!=revision)throw error("VD_CONFLICT","현재 VD revision으로 요청하세요.");
        return begin(vd,key,digest,null,false,true);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void registryChanged(VirtualDevice vd) {
        var current=runtimes.current(vd.id()).orElse(null);if(current==null)return;
        var pending=runtimes.pending(vd.id()).orElse(null);
        if(vd.state()==VirtualDevice.State.RELEASED) {
            begin(vd,"registry:"+vd.revision(),requestDigest("DRAIN",vd.revision(),null),null,false,true);return;
        }
        var settings=VDRuntimeDocuments.settings(pending!=null && pending.configurationJson()!=null?pending.configurationJson():current.configurationJson());
        String configuration=configuration(vd,settings),digest=VDRuntimeDocuments.digest(configuration);
        String desired=pending!=null && pending.configurationDigest()!=null?pending.configurationDigest():current.configurationDigest();
        if(digest.equals(desired))return; // Renaming does not replace the runtime or restart an in-flight operation.
        begin(vd,"registry:"+vd.revision(),requestDigest("REPLACE",vd.revision(),settings),configuration,true,false);
    }
    private VDOperation begin(VirtualDevice vd,String key,String requestDigest,String configuration,boolean replace,boolean drain) {
        validateKey(key);var now=now(vd);var current=runtimes.current(vd.id()).orElse(null);
        runtimes.pending(vd.id()).ifPresent(o->runtimes.finishOperation(o.id(),"SUPERSEDED","NEWER_REQUEST",now));
        String configDigest=configuration==null?null:VDRuntimeDocuments.digest(configuration);
        boolean reuse=!drain && !replace && current!=null && current.desiredState().equals("RUNNING") && current.configurationDigest().equals(configDigest);
        String kind=drain?"DRAIN":current==null || reuse?"PROVISION":"REPLACE";
        UUID source=current==null || reuse?null:current.id(),target=null;
        if(!drain && (current==null || reuse))target=reuse?current.id():create(vd,configuration,now).id();
        if(current!=null && !reuse)requestDrain(current,now);
        boolean complete=drain && current==null || reuse && current.ready(now);
        var op=new VDOperation(UUID.randomUUID(),vd.id(),key,requestDigest,kind,vd.revision(),configuration,configDigest,source,target,
            complete?"SUCCEEDED":"RUNNING",null,now,now,complete?now:null);
        runtimes.createOperation(op);return runtimes.operation(op.id()).orElseThrow();
    }
    private VDRuntime create(VirtualDevice vd,String configuration,Instant now) {
        long generation=runtimes.nextGeneration(vd.id());UUID id=UUID.randomUUID();
        var launch=VDRuntimeDocuments.launch(vd.id(),id,generation,configuration);
        var r=new VDRuntime(id,vd.id(),generation,vd.revision(),configuration,VDRuntimeDocuments.digest(configuration),launch.namespace(),launch.podName(),UUID.randomUUID(),
            "RUNNING","PENDING",null,null,null,null,null,null,now.plusSeconds(launch.startupSeconds()),null,null,now,now);
        runtimes.create(r);return r;
    }
    private void requestDrain(VDRuntime r,Instant now) {
        if(r.desiredState().equals("RUNNING")) {
            if(r.sessionId()==null)runtimes.stop(r.id(),null,now);
            else runtimes.drain(r.id(),now.plusSeconds(VDRuntimeDocuments.launch(r).drainSeconds()),now);
        }
    }
    @Transactional
    public VDRuntime submitted(UUID runtimeId,UUID podUid) {
        Objects.requireNonNull(podUid);var r=lockedRuntime(runtimeId);
        if(r.podUid()!=null && !r.podUid().equals(podUid))throw fenced();
        var now=now(r);runtimes.submitted(runtimeId,podUid,now);
        // Retained tombstones also clean up a late CREATE after a previous absence observation.
        if(r.desiredState().equals("STOPPED"))runtimes.command(runtimeId,"DELETE",now);
        return get(runtimeId);
    }
    /** A list observation may remove readiness or request cleanup, but cannot grant execution authority. */
    @Transactional
    public void observed(io.edgeai.domain.vd.VDGateway.Observation observation) {
        var r=lockedRuntime(observation.runtimeId());
        if(!r.vdId().equals(observation.vdId()) || r.generation()!=observation.generation() || !r.podName().equals(observation.name()))throw fenced();
        var now=now(r);
        if(r.desiredState().equals("STOPPED")) {
            // Do not overwrite the historical Pod UID when an old generation is recreated late.
            runtimes.command(r.id(),"DELETE",now);return;
        }
        if(r.podUid()!=null && !r.podUid().equals(observation.podUid())) { failure(r,"OWNERSHIP_CONFLICT",now);return; }
        if(r.podUid()==null)runtimes.submitted(r.id(),observation.podUid(),now);
        if(observation.terminating() || Set.of("Failed","Succeeded").contains(observation.phase()))failure(r,"POD_FAILED",now);
        else if(!observation.ready())runtimes.unready(r.id(),now);
    }
    @Transactional
    public VDRuntime attest(UUID runtimeId,UUID podUid,UUID nodeUid,String nodeName,UUID sessionId,boolean podReady,int leaseSeconds) {
        Objects.requireNonNull(podUid);Objects.requireNonNull(nodeUid);Objects.requireNonNull(sessionId);
        io.edgeai.domain.runtime.RuntimeNames.dns(nodeName,253);
        if(leaseSeconds<1 || leaseSeconds>60)throw new IllegalArgumentException("Lease must be 1..60 seconds");
        var r=lockedRuntime(runtimeId);var now=now(r);var launch=VDRuntimeDocuments.launch(r);
        if(r.terminal() || r.desiredState().equals("STOPPED") || !podUid.equals(r.podUid())
            || r.sessionId()!=null && (!sessionId.equals(r.sessionId()) || !nodeUid.equals(r.nodeUid()) || !nodeName.equals(r.nodeName()) || !now.isBefore(r.leaseUntil()))
            || r.readyAt()==null && !now.isBefore(r.startupDeadline())
            || r.drainDeadline()!=null && !now.isBefore(r.drainDeadline())
            || launch.targetNodeId()!=null && (!launch.targetNodeId().equals(nodeUid) || !launch.targetNodeName().equals(nodeName)))throw fenced();
        boolean ready=podReady && r.desiredState().equals("RUNNING");
        runtimes.attest(runtimeId,nodeUid,nodeName,sessionId,now.plusSeconds(leaseSeconds),ready,now);
        if(ready)runtimes.pending(r.vdId()).filter(o->runtimeId.equals(o.targetRuntimeId()))
            .ifPresent(o->runtimes.finishOperation(o.id(),"SUCCEEDED",null,now));
        return get(runtimeId);
    }
    @Transactional
    public void drained(UUID runtimeId,UUID sessionId) {
        var r=lockedRuntime(runtimeId);
        if(!r.desiredState().equals("DRAINING") || !Objects.equals(r.sessionId(),sessionId))throw fenced();
        runtimes.stop(runtimeId,null,now(r));
    }
    /** The supervisor retires before its bounded execution history is exhausted. */
    @Transactional
    public VDRuntime retire(UUID runtimeId,UUID sessionId) {
        var r=lockedRuntime(runtimeId);var vd=lock(r.vdId());
        if(r.terminal() || r.desiredState().equals("STOPPED") || !Objects.equals(r.sessionId(),sessionId))throw fenced();
        if(r.desiredState().equals("RUNNING")) {
            var settings=VDRuntimeDocuments.settings(r.configurationJson());
            begin(vd,"supervisor:"+runtimeId,requestDigest("SUPERVISOR_DRAIN",vd.revision(),settings),configuration(vd,settings),true,false);
        }
        return get(runtimeId);
    }
    @Transactional
    public void fail(UUID runtimeId,String reason) {
        if(!Set.of("STARTUP_TIMEOUT","LEASE_EXPIRED","POD_FAILED","RUNTIME_LOST","OWNERSHIP_CONFLICT").contains(reason))throw new IllegalArgumentException("Unknown VD failure");
        var r=lockedRuntime(runtimeId);if(r.terminal() || r.desiredState().equals("STOPPED"))return;
        failure(r,reason,now(r));
    }
    private void failure(VDRuntime r,String reason,Instant now) {
        runtimes.stop(r.id(),reason,now);
        runtimes.pending(r.vdId()).filter(o->r.id().equals(o.targetRuntimeId()))
            .ifPresent(o->runtimes.finishOperation(o.id(),"FAILED",reason,now));
    }
    @Transactional
    public void reconcile(UUID runtimeId) {
        var r=lockedRuntime(runtimeId);if(r.terminal())return;var now=now(r);
        if(r.desiredState().equals("RUNNING")) {
            if(r.readyAt()==null && !now.isBefore(r.startupDeadline()))failure(r,"STARTUP_TIMEOUT",now);
            else if(r.leaseUntil()!=null && !now.isBefore(r.leaseUntil()))failure(r,"LEASE_EXPIRED",now);
        } else if(r.desiredState().equals("DRAINING") && !now.isBefore(r.drainDeadline()))runtimes.stop(r.id(),"DRAIN_TIMEOUT",now);
    }
    @Transactional
    public VDRuntime confirmStopped(UUID runtimeId) {
        var r=lockedRuntime(runtimeId);var vd=lock(r.vdId());if(r.terminal())return r;
        if(!r.desiredState().equals("STOPPED"))throw fenced();
        if(!runtimes.createCommandComplete(runtimeId))throw error("VD_CREATE_UNRESOLVED","생성 명령이 확정되기 전에는 종료를 확정할 수 없습니다.");
        var now=now(r);runtimes.terminated(runtimeId,vd.revision(),now);
        var pending=runtimes.pending(vd.id()).orElse(null);
        if(pending!=null && runtimeId.equals(pending.sourceRuntimeId())) {
            if(pending.kind().equals("DRAIN"))runtimes.finishOperation(pending.id(),"SUCCEEDED",null,now);
            else if(vd.state()!=VirtualDevice.State.REGISTERED)runtimes.finishOperation(pending.id(),"SUPERSEDED","VD_RELEASED",now);
            else {
                String latest=configuration(vd,VDRuntimeDocuments.settings(pending.configurationJson()));
                if(!VDRuntimeDocuments.digest(latest).equals(pending.configurationDigest()))throw error("VD_CONFIG_CONFLICT","현재 설정과 실행 교체 요청이 다릅니다.");
                var target=create(vd,latest,now);runtimes.operationTarget(pending.id(),target.id(),now);
            }
        }
        return get(runtimeId);
    }
    @Transactional(readOnly=true)
    public VDRuntime get(UUID runtimeId) { return runtimes.runtime(runtimeId).orElseThrow(()->new ControlPlaneException(404,"VD_RUNTIME_NOT_FOUND","VD 실행을 찾을 수 없습니다.")); }
    private VDRuntime lockedRuntime(UUID id) { var initial=get(id);lock(initial.vdId());return get(id); }
    private VirtualDevice lock(UUID id) { return vds.find(id,true).orElseThrow(()->new ControlPlaneException(404,"VD_NOT_FOUND","VD를 찾을 수 없습니다.")); }
    private String configuration(VirtualDevice vd,RuntimeSettings settings) {
        var spec=VirtualDeviceInput.spec(profiles.find(vd.profileVersionId()).orElseThrow().specJson());
        ServiceExecutionInput.parseSpec(profiles.find(vd.serviceProfileVersionId()).orElseThrow().specJson());
        String nodeName=vd.placement().nodeId()==null?null:nodes.find(vd.placement().nodeId()).orElseThrow(()->error("VD_NODE_UNAVAILABLE","배치 대상 Node가 없습니다.")).name();
        return VDRuntimeDocuments.configuration(vd,vds.activeSources(vd.id()),spec,nodeName,settings);
    }
    private VDOperation replay(UUID id,String key,String digest) {
        validateKey(key);var old=runtimes.byKey(id,key).orElse(null);
        if(old!=null && !old.requestDigest().equals(digest))throw error("VD_OPERATION_CONFLICT","같은 요청 키에 다른 작업이 있습니다.");
        return old;
    }
    private static void validateKey(String key) { if(key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw new IllegalArgumentException("Invalid operation key"); }
    private static String requestDigest(String kind,long revision,RuntimeSettings settings) {
        if(revision<0 || revision>9007199254740991L)throw new IllegalArgumentException("Invalid revision");
        return JSON.digest("edgeai-vd-operation-v1",Map.of("kind",kind,"revision",revision,"scope",settings==null?Map.of():Map.of("namespace",settings.namespace(),"account",settings.serviceAccount(),"origin",settings.controlPlane().toString())));
    }
    private Instant now(VirtualDevice vd) { return later(clock.instant().truncatedTo(ChronoUnit.MICROS),vd.updatedAt()); }
    private Instant now(VDRuntime r) { return later(clock.instant().truncatedTo(ChronoUnit.MICROS),r.updatedAt()); }
    private static Instant later(Instant a,Instant b) { return a.isBefore(b)?b:a; }
    private static ControlPlaneException error(String code,String message) { return new ControlPlaneException(409,code,message); }
    private static ControlPlaneException fenced() { return error("VD_RUNTIME_FENCED","과거 실행 세대·Pod·session 또는 만료된 실행 권한입니다."); }
}
