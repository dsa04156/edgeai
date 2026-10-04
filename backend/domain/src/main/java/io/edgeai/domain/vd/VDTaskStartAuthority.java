package io.edgeai.domain.vd;

import io.edgeai.domain.runtime.RuntimeInstance;
import java.time.Instant;
import java.util.*;

/** Original child admission; assignment and supervisor identity are not interchangeable with Pod exit. */
public record VDTaskStartAuthority(RuntimeInstance runtime,VDTaskAllocation allocation,VDRuntime supervisor,
        String workDigest,UUID offloadId,Instant startDeadline,Instant admittedAt) {
    public VDTaskStartAuthority {
        Objects.requireNonNull(runtime);Objects.requireNonNull(allocation);Objects.requireNonNull(supervisor);Objects.requireNonNull(admittedAt);
        if(!runtime.vd() || !runtime.id().equals(allocation.runtimeId()) || !runtime.vdId().equals(allocation.vdId()) ||
                !allocation.vdRuntimeId().equals(supervisor.id()) || !allocation.vdId().equals(supervisor.vdId()) ||
                allocation.generation()!=supervisor.generation() || !Objects.equals(allocation.sessionId(),supervisor.sessionId()) ||
                !Objects.equals(allocation.podUid(),supervisor.podUid()) || !Objects.equals(runtime.producerPodUid(),supervisor.podUid()) ||
                !Objects.equals(runtime.nodeUid(),supervisor.nodeUid()) || !Objects.equals(runtime.nodeName(),supervisor.nodeName()) ||
                !runtime.namespace().equals(supervisor.namespace()) || runtime.producerPodUid()==null || runtime.nodeUid()==null || runtime.nodeName()==null ||
                supervisor.sessionId()==null || supervisor.readyAt()==null || supervisor.leaseUntil()==null || runtime.expiresAt()==null ||
                !allocation.open() || allocation.slot()<0 || allocation.assignedSequence()<0 || allocation.assignedAt()==null ||
                admittedAt.isBefore(runtime.createdAt()) || admittedAt.isBefore(allocation.assignedAt()) || admittedAt.isBefore(supervisor.readyAt()) ||
                !admittedAt.isBefore(runtime.expiresAt()) || !admittedAt.isBefore(supervisor.leaseUntil()) ||
                supervisor.drainDeadline()!=null && !admittedAt.isBefore(supervisor.drainDeadline()) ||
                workDigest==null || !workDigest.matches("sha256:[a-f0-9]{64}") || (offloadId==null)!=(startDeadline==null))
            throw new IllegalArgumentException("Invalid VD task start authority");
    }
    public String objectKey(){return "authority/vd-task-start/"+runtime.id()+".json";}
    public Map<String,Object> document(){
        var value=new TreeMap<String,Object>();
        value.put("apiVersion","edgeai.vd.task.start/v1");value.put("runtimeId",runtime.id().toString());
        value.put("runId",runtime.runId().toString());value.put("taskId",runtime.taskId().toString());value.put("attemptId",runtime.attemptId().toString());
        value.put("epoch",runtime.epoch());value.put("namespace",runtime.namespace());value.put("vdId",allocation.vdId().toString());
        value.put("allocationId",allocation.id().toString());value.put("vdRuntimeId",supervisor.id().toString());
        value.put("generation",allocation.generation());value.put("sessionId",allocation.sessionId().toString());
        value.put("podName",supervisor.podName());value.put("podUid",allocation.podUid().toString());
        value.put("nodeUid",runtime.nodeUid().toString());value.put("nodeName",runtime.nodeName());
        value.put("slot",allocation.slot());value.put("assignedSequence",allocation.assignedSequence());value.put("assignedAt",allocation.assignedAt().toString());
        value.put("configurationDigest",supervisor.configurationDigest());value.put("readyAt",supervisor.readyAt().toString());
        value.put("supervisorLeaseUntil",supervisor.leaseUntil().toString());value.put("supervisorDrainDeadline",supervisor.drainDeadline()==null?null:supervisor.drainDeadline().toString());
        value.put("workDigest",workDigest);value.put("expiresAt",runtime.expiresAt().toString());
        value.put("offloadId",offloadId==null?null:offloadId.toString());value.put("startDeadline",startDeadline==null?null:startDeadline.toString());
        value.put("admittedAt",admittedAt.toString());return Collections.unmodifiableMap(value);
    }
}
