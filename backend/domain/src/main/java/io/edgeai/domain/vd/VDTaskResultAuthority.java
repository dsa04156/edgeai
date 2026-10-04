package io.edgeai.domain.vd;

import io.edgeai.domain.runtime.RuntimeInstance;
import io.edgeai.domain.storage.TaskResult;
import java.util.*;

/** Immutable accepted result and original child assignment; publication needs no live supervisor. */
public record VDTaskResultAuthority(RuntimeInstance runtime,TaskResult result,VDTaskAllocation allocation,VDRuntime supervisor) {
    public VDTaskResultAuthority {
        Objects.requireNonNull(runtime);Objects.requireNonNull(result);Objects.requireNonNull(allocation);Objects.requireNonNull(supervisor);
        if(!runtime.vd() || runtime.remote() || result.remoteAllocationId()!=null || !supervisor.id().equals(result.vdRuntimeId()) ||
                !runtime.id().equals(result.runtimeId()) || !runtime.taskId().equals(result.taskId()) ||
                !runtime.attemptId().equals(result.attemptId()) || runtime.epoch()!=result.epoch() ||
                !runtime.id().equals(allocation.runtimeId()) || !runtime.vdId().equals(allocation.vdId()) ||
                !supervisor.id().equals(allocation.vdRuntimeId()) || !supervisor.vdId().equals(allocation.vdId()) ||
                allocation.generation()!=supervisor.generation() || !Objects.equals(allocation.sessionId(),supervisor.sessionId()) ||
                !Objects.equals(allocation.podUid(),supervisor.podUid()) || !Objects.equals(runtime.producerPodUid(),supervisor.podUid()) ||
                !Objects.equals(result.producerPodUid(),runtime.producerPodUid()) || !Objects.equals(runtime.nodeUid(),supervisor.nodeUid()) ||
                !Objects.equals(runtime.nodeName(),supervisor.nodeName()) || !runtime.namespace().equals(supervisor.namespace()) ||
                runtime.producerPodUid()==null || runtime.nodeUid()==null || runtime.nodeName()==null || supervisor.sessionId()==null ||
                supervisor.configurationDigest()==null || !supervisor.configurationDigest().matches("sha256:[a-f0-9]{64}") ||
                allocation.assignedAt()==null || result.createdAt()==null || result.createdAt().isBefore(allocation.assignedAt()) ||
                result.createdAt().isBefore(runtime.createdAt()) || result.manifestDigest()==null || !result.manifestDigest().matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid VD Result authority");
    }
    public String objectKey(){return "authority/vd-task-result/"+runtime.id()+".json";}
    public Map<String,Object> document(){
        var value=new TreeMap<String,Object>();value.put("apiVersion","edgeai.vd.task.result/v1");value.put("resultId",result.id().toString());
        value.put("runtimeId",runtime.id().toString());value.put("runId",runtime.runId().toString());value.put("taskId",runtime.taskId().toString());
        value.put("attemptId",runtime.attemptId().toString());value.put("epoch",runtime.epoch());value.put("namespace",runtime.namespace());
        value.put("vdId",allocation.vdId().toString());value.put("allocationId",allocation.id().toString());value.put("vdRuntimeId",supervisor.id().toString());
        value.put("generation",allocation.generation());value.put("sessionId",allocation.sessionId().toString());value.put("podName",supervisor.podName());
        value.put("podUid",runtime.producerPodUid().toString());value.put("nodeUid",runtime.nodeUid().toString());value.put("nodeName",runtime.nodeName());
        value.put("slot",allocation.slot());value.put("assignedSequence",allocation.assignedSequence());value.put("assignedAt",allocation.assignedAt().toString());
        value.put("configurationDigest",supervisor.configurationDigest());value.put("startKey","authority/vd-task-start/"+runtime.id()+".json");
        value.put("manifestDigest",result.manifestDigest());value.put("committedAt",result.createdAt().toString());
        value.put("outputs",result.outputs().stream().sorted(Comparator.comparing(TaskResult.Output::port)).map(output->{
            var a=output.artifact();var fields=new TreeMap<String,Object>();fields.put("port",output.port());fields.put("bucket",a.bucket());
            fields.put("objectKey",a.objectKey());fields.put("versionId",a.versionId());fields.put("bytes",a.bytes());
            fields.put("sha256",a.sha256());fields.put("mediaType",a.mediaType());return fields;
        }).toList());return Collections.unmodifiableMap(value);
    }
}
