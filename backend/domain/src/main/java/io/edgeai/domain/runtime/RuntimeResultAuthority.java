package io.edgeai.domain.runtime;

import io.edgeai.domain.storage.TaskResult;
import java.util.*;

/** A database-committed Result, never an inference from a Pod exit code or an uploaded file. */
public record RuntimeResultAuthority(RuntimeInstance runtime,TaskResult result) {
    public RuntimeResultAuthority {
        Objects.requireNonNull(runtime);Objects.requireNonNull(result);
        if(runtime.remote() || runtime.vd() || result.remoteAllocationId()!=null || result.vdRuntimeId()!=null ||
                runtime.jobUid()==null || runtime.producerPodUid()==null || runtime.nodeUid()==null || runtime.nodeName()==null ||
                !runtime.id().equals(result.runtimeId()) || !runtime.taskId().equals(result.taskId()) ||
                !runtime.attemptId().equals(result.attemptId()) || runtime.epoch()!=result.epoch() ||
                !runtime.producerPodUid().equals(result.producerPodUid()) || result.createdAt()==null ||
                result.manifestDigest()==null || !result.manifestDigest().matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid Kubernetes Result authority");
    }
    public String objectKey(){return "authority/runtime-result/"+runtime.id()+".json";}
    public Map<String,Object> document(){
        var value=new TreeMap<String,Object>();
        value.put("apiVersion","edgeai.runtime.result/v1");value.put("resultId",result.id().toString());
        value.put("runtimeId",runtime.id().toString());value.put("runId",runtime.runId().toString());
        value.put("taskId",runtime.taskId().toString());value.put("attemptId",runtime.attemptId().toString());value.put("epoch",runtime.epoch());
        value.put("namespace",runtime.namespace());value.put("jobName",runtime.jobName());value.put("jobUid",runtime.jobUid().toString());
        value.put("podUid",runtime.producerPodUid().toString());value.put("nodeUid",runtime.nodeUid().toString());value.put("nodeName",runtime.nodeName());
        value.put("startKey","authority/runtime-start/"+runtime.id()+".json");
        value.put("manifestDigest",result.manifestDigest());value.put("committedAt",result.createdAt().toString());
        value.put("outputs",result.outputs().stream().sorted(Comparator.comparing(TaskResult.Output::port)).map(output->{
            var a=output.artifact();var fields=new TreeMap<String,Object>();fields.put("port",output.port());fields.put("bucket",a.bucket());
            fields.put("objectKey",a.objectKey());fields.put("versionId",a.versionId());fields.put("bytes",a.bytes());
            fields.put("sha256",a.sha256());fields.put("mediaType",a.mediaType());return fields;
        }).toList());return Collections.unmodifiableMap(value);
    }
}
