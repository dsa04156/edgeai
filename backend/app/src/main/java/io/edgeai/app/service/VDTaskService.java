package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeInstance;
import io.edgeai.domain.vd.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** One VD lock serializes poll receipts, slot ownership, cancellation and producer commits. No network I/O. */
@Service
public class VDTaskService {
    private final VirtualDeviceRepository vds;
    private final VDRuntimeRepository supervisors;
    private final VDTaskRepository allocations;
    private final RuntimeRepository runtimes;
    private final ExecutionRepository executions;
    private final RuntimeLifecycleService lifecycle;
    private final ObjectProvider<RunnerTokenService> tokens;
    private final Clock clock;
    public VDTaskService(VirtualDeviceRepository vds,VDRuntimeRepository supervisors,VDTaskRepository allocations,RuntimeRepository runtimes,
            ExecutionRepository executions,RuntimeLifecycleService lifecycle,ObjectProvider<RunnerTokenService> tokens,Clock clock) {
        this.vds=vds;this.supervisors=supervisors;this.allocations=allocations;this.runtimes=runtimes;this.executions=executions;this.lifecycle=lifecycle;this.tokens=tokens;this.clock=clock;
    }
    public record Exchange(List<Map<String,Object>> assignments,List<String> cancelAttempts,List<String> acknowledgedAttempts,boolean drained) {}
    @Transactional(propagation=Propagation.MANDATORY)
    public Exchange exchange(VDRuntime supervisor,VDPollInput.Request input,boolean replay) {
        var reported=new HashSet<UUID>();
        // Check the whole report before mutating anything. A failed report rolls back its heartbeat too.
        for(var a:input.active()) { var allocation=owned(supervisor,input,a);if(!allocation.open())throw fenced();reported.add(a.id()); }
        for(var a:input.completed()) {
            var allocation=owned(supervisor,input,a);
            if(!allocation.open() && (!allocation.closeReason().equals("PROCESS_EXIT") || !Objects.equals(allocation.exitCode(),a.exitCode()) ||
                !Objects.equals(allocation.completionSequence(),input.sequence())))throw fenced();
            reported.add(a.id());
        }
        for(var allocation:allocations.open(supervisor.id()))reconcile(allocation.runtimeId());
        var acknowledged=new ArrayList<String>();
        for(var a:input.completed()) {
            lifecycle.finishVD(a.id(),"PROCESS_EXIT",input.sequence(),a.exitCode());acknowledged.add(a.id().toString());
        }
        if(!replay)for(var allocation:allocations.open(supervisor.id())) {
            var r=runtimes.runtime(allocation.runtimeId()).orElseThrow();
            if(allocation.assignedSequence()<input.sequence() && !reported.contains(r.attemptId())) {
                // Advancing sequence proves the earlier response was applied. The supervisor retains every
                // started child in active/completed until acknowledged; an absent unclaimed assignment never started.
                lifecycle.finishVD(r.attemptId(),"NOT_STARTED",input.sequence(),null);
            }
        }
        if(!replay && supervisor.ready(clock.instant()) && input.state().equals("RUNNING")) {
            int capacity=VDRuntimeDocuments.launch(supervisor).maxConcurrentTasks();
            var used=new HashSet<>(allocations.open(supervisor.id()).stream().map(VDTaskAllocation::slot).toList());
            for(UUID id:allocations.pending(supervisor.vdId(),128)) {
                if(used.size()>=capacity)break;
                reconcile(id);var r=runtimes.runtime(id).orElseThrow();
                if(!r.desiredState().equals("RUNNING"))continue;
                int slot=1;while(used.contains(slot))slot++;
                lifecycle.allocateVD(r.attemptId(),supervisor.id(),slot,input.sequence());used.add(slot);
            }
        }
        var assigned=new ArrayList<Map<String,Object>>();var cancelled=new ArrayList<String>();
        for(var allocation:allocations.open(supervisor.id())) {
            var r=runtimes.runtime(allocation.runtimeId()).orElseThrow();
            if(!r.desiredState().equals("RUNNING"))cancelled.add(r.attemptId().toString());
            else if(allocation.assignedSequence()==input.sequence()) {
                // An assignment response may have been lost just before drain. Still deliver its identity as a
                // cancellation (without executing it); the next report supplies the NOT_STARTED proof.
                if(!supervisor.desiredState().equals("RUNNING"))cancelled.add(r.attemptId().toString());
                else assigned.add(Map.of("attemptId",r.attemptId().toString(),"epoch",r.epoch(),"claimToken",tokens.getObject().issue(r)));
            }
        }
        return new Exchange(List.copyOf(assigned),List.copyOf(cancelled),List.copyOf(acknowledged),allocations.open(supervisor.id()).isEmpty());
    }
    private VDTaskAllocation owned(VDRuntime supervisor,VDPollInput.Request input,VDPollInput.Attempt report) {
        var r=runtimes.byAttempt(report.id()).orElseThrow(VDTaskService::fenced);
        var a=allocations.byRuntime(r.id()).orElseThrow(VDTaskService::fenced);
        if(!r.vd() || r.epoch()!=report.epoch() || !a.vdRuntimeId().equals(supervisor.id()) || !a.vdId().equals(supervisor.vdId()) ||
            a.generation()!=input.generation() || !a.sessionId().equals(input.sessionId()) || !a.podUid().equals(input.podUid()) || input.sequence()<=a.assignedSequence())throw fenced();
        return a;
    }
    @Transactional
    public void reconcile(UUID runtimeId) {
        var initial=runtimes.runtime(runtimeId).orElseThrow();if(!initial.vd())throw fenced();
        vds.find(initial.vdId(),true).orElseThrow();executions.run(initial.runId(),true).orElseThrow();
        var r=runtimes.runtime(runtimeId).orElseThrow();if(r.observedState().equals("TERMINATED"))return;
        var allocation=allocations.byRuntime(r.id()).orElse(null);var now=clock.instant();
        if(r.desiredState().equals("RUNNING")) {
            if(allocation==null) {
                if(vds.find(r.vdId(),false).orElseThrow().state()!=VirtualDevice.State.REGISTERED)lifecycle.observeFailure(r.attemptId(),"RUNTIME_LOST");
                else if(!now.isBefore(r.expiresAt()))lifecycle.observeFailure(r.attemptId(),"DISPATCH_TIMEOUT");
            } else {
                var vr=supervisors.runtime(allocation.vdRuntimeId()).orElseThrow();
                if(vr.terminal() || vr.desiredState().equals("STOPPED") || vr.leaseUntil()==null || !now.isBefore(vr.leaseUntil()) ||
                    vr.drainDeadline()!=null && !now.isBefore(vr.drainDeadline()))lifecycle.observeFailure(r.attemptId(),"RUNTIME_LOST");
                else if(!now.isBefore(r.expiresAt()))lifecycle.observeFailure(r.attemptId(),"RUNTIME_TIMEOUT");
            }
        }
        r=runtimes.runtime(runtimeId).orElseThrow();
        if(r.desiredState().equals("STOPPED")) {
            if(allocation==null)lifecycle.finishVD(r.attemptId(),"UNASSIGNED",null,null);
            else if(supervisors.runtime(allocation.vdRuntimeId()).orElseThrow().terminal())lifecycle.finishVD(r.attemptId(),"POD_GONE",null,null);
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void supervisorGone(UUID id) {
        if(!supervisors.runtime(id).orElseThrow().terminal())throw fenced();
        for(var a:allocations.open(id))lifecycle.finishVD(runtimes.runtime(a.runtimeId()).orElseThrow().attemptId(),"POD_GONE",null,null);
    }
    public boolean hasOpen(UUID supervisorId){return !allocations.open(supervisorId).isEmpty();}
    private static ControlPlaneException fenced(){return new ControlPlaneException(409,"VD_TASK_FENCED","현재 VD 배정·작업 epoch와 완료 보고를 확인하세요.");}
}
