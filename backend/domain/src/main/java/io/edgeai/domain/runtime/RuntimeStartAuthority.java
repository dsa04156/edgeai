package io.edgeai.domain.runtime;

import java.time.Instant;
import java.util.*;

/** A committed Kubernetes claim, without credentials, parameters or signed storage URLs. */
public record RuntimeStartAuthority(RuntimeInstance runtime,String workDigest,UUID offloadId,Instant startDeadline,Instant admittedAt) {
    public RuntimeStartAuthority {
        Objects.requireNonNull(runtime);Objects.requireNonNull(admittedAt);
        if(runtime.remote() || runtime.vd() || runtime.jobUid()==null || runtime.producerPodUid()==null || runtime.nodeUid()==null ||
                runtime.nodeName()==null || runtime.expiresAt()==null || workDigest==null || !workDigest.matches("sha256:[a-f0-9]{64}") ||
                (offloadId==null)!=(startDeadline==null))throw new IllegalArgumentException("Invalid Kubernetes start authority");
    }
    public String objectKey(){return "authority/runtime-start/"+runtime.id()+".json";}
    public Map<String,Object> document(){
        var value=new TreeMap<String,Object>();
        value.put("apiVersion","edgeai.runtime.start/v1");value.put("runtimeId",runtime.id().toString());
        value.put("runId",runtime.runId().toString());value.put("taskId",runtime.taskId().toString());
        value.put("attemptId",runtime.attemptId().toString());value.put("epoch",runtime.epoch());
        value.put("namespace",runtime.namespace());value.put("jobName",runtime.jobName());value.put("jobUid",runtime.jobUid().toString());
        value.put("podUid",runtime.producerPodUid().toString());value.put("nodeUid",runtime.nodeUid().toString());value.put("nodeName",runtime.nodeName());
        value.put("workDigest",workDigest);value.put("expiresAt",runtime.expiresAt().toString());
        value.put("offloadId",offloadId==null?null:offloadId.toString());value.put("startDeadline",startDeadline==null?null:startDeadline.toString());
        value.put("admittedAt",admittedAt.toString());return Collections.unmodifiableMap(value);
    }
}
