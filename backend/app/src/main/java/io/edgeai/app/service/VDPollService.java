package io.edgeai.app.service;

import io.edgeai.app.config.VDPrincipal;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated supervisor liveness and drain protocol; Task allocation is the next integration boundary. */
@Service
@ConditionalOnProperty(name={"edgeai.runtime.enabled","edgeai.vd.enabled"},havingValue="true")
public class VDPollService {
    private final VirtualDeviceRepository vds;
    private final VDPollRepository polls;
    private final VDLifecycleService lifecycle;
    private final Clock clock;
    private final int leaseSeconds;
    public VDPollService(VirtualDeviceRepository vds,VDPollRepository polls,VDLifecycleService lifecycle,Clock clock,@Value("${edgeai.vd.lease-seconds:30}") int leaseSeconds) {
        if(leaseSeconds<1 || leaseSeconds>60)throw new IllegalArgumentException("Invalid VD lease duration");
        this.vds=vds;this.polls=polls;this.lifecycle=lifecycle;this.clock=clock;this.leaseSeconds=leaseSeconds;
    }
    @Transactional
    public Map<String,Object> poll(VDPrincipal principal,byte[] body) {
        var input=VDPollInput.parse(body);
        if(!principal.runtimeId().equals(input.runtimeId()) || !principal.vdId().equals(input.vdId()) || principal.generation()!=input.generation() || !principal.pod().podUid().equals(input.podUid()))throw conflict("VD_RUNTIME_FENCED");
        vds.find(principal.vdId(),true).orElseThrow(()->conflict("VD_RUNTIME_FENCED"));
        var r=lifecycle.get(principal.runtimeId());
        if(!r.vdId().equals(principal.vdId()) || r.generation()!=principal.generation())throw conflict("VD_RUNTIME_FENCED");
        var previous=polls.find(r.id()).orElse(null);
        if(previous==null ? input.sequence()!=0 : !previous.sessionId().equals(input.sessionId()) || input.sequence()<previous.sequence()
            || input.sequence()>previous.sequence()+1 || input.sequence()==previous.sequence() && !input.digest().equals(previous.requestDigest()))throw conflict("VD_POLL_CONFLICT");
        // No Task has yet been assigned by this endpoint. Never acknowledge unowned exits or infer a Result from exitCode.
        if(!input.active().isEmpty() || !input.completed().isEmpty())throw conflict("VD_TASK_UNKNOWN");
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);if(now.isBefore(r.updatedAt()))now=r.updatedAt();
        int lease=lease(r,now);
        r=lifecycle.attest(r.id(),input.podUid(),principal.pod().nodeUid(),principal.pod().nodeName(),input.sessionId(),principal.pod().ready(),lease);
        if(input.state().equals("DRAINING") && r.desiredState().equals("RUNNING"))r=lifecycle.retire(r.id(),input.sessionId());
        if(r.desiredState().equals("DRAINING")) {
            // No allocations exist, and this authenticated report has empty active/completed sets.
            // An idle supervisor exits on DRAIN, so persist STOP before granting its exit.
            lifecycle.drained(r.id(),input.sessionId());r=lifecycle.get(r.id());
        }
        String command=switch(r.desiredState()){case "RUNNING"->"RUN";case "DRAINING"->"DRAIN";default->"STOP";};
        polls.save(new VDPoll(r.id(),input.sessionId(),input.sequence(),input.digest(),command,previous==null?r.updatedAt():previous.createdAt(),r.updatedAt()));
        return Map.of("runtimeId",r.id().toString(),"generation",r.generation(),"podUid",input.podUid().toString(),"sessionId",input.sessionId().toString(),"sequence",input.sequence(),
            "command",command,"leaseSeconds",lease,"assignments",List.of(),"cancelAttempts",List.of(),"acknowledgedAttempts",List.of());
    }
    private int lease(VDRuntime r,Instant now) {
        long value=leaseSeconds;
        if(r.readyAt()==null)value=Math.min(value,Duration.between(now,r.startupDeadline()).getSeconds());
        if(r.drainDeadline()!=null)value=Math.min(value,Duration.between(now,r.drainDeadline()).getSeconds());
        if(value<1)throw conflict("VD_RUNTIME_FENCED");return (int)value;
    }
    private static ControlPlaneException conflict(String code){return new ControlPlaneException(409,code,"VD 실행 신원·session·순번과 배정 상태를 확인하세요.");}
}
