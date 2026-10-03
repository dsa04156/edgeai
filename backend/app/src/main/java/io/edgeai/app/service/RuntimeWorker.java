package io.edgeai.app.service;

import io.edgeai.adapters.kubernetes.KubernetesJobCompiler;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.*;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Durable commands and periodic full reconciliation make API restarts/network ambiguity recoverable. */
public final class RuntimeWorker {
    private final RuntimeRepository runtimes;
    private final RuntimeLifecycleService lifecycle;
    private final RuntimeGateway gateway;
    private final RunnerTokenService tokens;
    private final RuntimeSettings settings;
    private final Clock clock;
    private final KubernetesJobCompiler compiler=new KubernetesJobCompiler();
    public RuntimeWorker(RuntimeRepository runtimes,RuntimeLifecycleService lifecycle,RuntimeGateway gateway,RunnerTokenService tokens,RuntimeSettings settings,Clock clock) {
        this.runtimes=runtimes;this.lifecycle=lifecycle;this.gateway=gateway;this.tokens=tokens;this.settings=settings;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${edgeai.runtime.command-poll-ms:1000}")
    public void commands() {
        try{for(int i=0;i<10 && dispatchOne();i++);}
        catch(RuntimeException e){log("Runtime command polling failed",e);}
    }
    public boolean dispatchOne() {
        var command=runtimes.leaseCommand(settings.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofSeconds(45));
        if(command.isEmpty())return false;var cmd=command.get();var r=runtimes.runtime(cmd.runtimeId()).orElseThrow();
        try {
            if(cmd.kind().equals("CREATE")) {
                var dispatch=lifecycle.dispatch(r.attemptId());r=dispatch.runtime();
                if(r.desiredState().equals("RUNNING")) {
                    if(clock.instant().isAfter(r.createdAt().plusSeconds(settings.dispatchSeconds())) && r.jobUid()==null)
                        lifecycle.observeFailure(r.attemptId(),"DISPATCH_TIMEOUT");
                    else {
                        var launch=new RuntimeLaunch(r.runId(),r.taskId(),r.attemptId(),r.epoch(),r.namespace(),settings.serviceAccount(),r.jobName()+"-claim",settings.controlPlane(),dispatch.nodeId(),dispatch.nodeName(),dispatch.excludedNodeNames(),settings.caConfigMap());
                        UUID uid=gateway.ensureJob(r,compiler.compile(dispatch.spec(),launch),tokens.issue(r));
                        lifecycle.submitted(r.attemptId(),uid);
                    }
                }
            } else {
                if(!gateway.stop(r)){runtimes.deferCommand(cmd.id(),cmd.leaseOwner(),clock.instant().plusSeconds(1),clock.instant());return true;}
                lifecycle.confirmStopped(r.attemptId());
            }
            runtimes.finishCommand(cmd.id(),cmd.leaseOwner(),clock.instant());
        } catch(RuntimeGatewayException error) {
            if(error.reason()==RuntimeGatewayException.Reason.RUNTIME_LOST || error.reason()==RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT)
                lifecycle.observeFailure(r.attemptId(),error.reason().name());
            runtimes.deferCommand(cmd.id(),cmd.leaseOwner(),clock.instant().plusSeconds(Math.min(30,Math.max(1,cmd.attempts()))),clock.instant());
            log("Runtime command deferred",error);
        } catch(RuntimeException error) {
            runtimes.deferCommand(cmd.id(),cmd.leaseOwner(),clock.instant().plusSeconds(5),clock.instant());log("Runtime command deferred",error);
        }
        return true;
    }
    @Scheduled(fixedDelayString="${edgeai.runtime.retry-poll-ms:1000}")
    public void retries() {
        try { for(var task:lifecycle.dueRetries(settings.namespace()))lifecycle.retryTask(task); }
        catch(RuntimeException error){log("Retry polling failed; durable state retained",error);}
    }
    @Scheduled(fixedDelayString="${edgeai.runtime.reconcile-ms:1000}")
    public void reconcile() {
        try {
            var active=runtimes.active(settings.namespace(),10000);
            var snapshot=gateway.listJobs();
            // Includes TERMINATED rows: a late CREATE may arrive after DELETE observed no Job.
            for(var observation:snapshot.jobs().values()) {
                var runtime=runtimes.byAttempt(observation.attemptId()).orElse(null);
                if(runtime==null || runtime.remote() || runtime.vd() || !runtime.namespace().equals(settings.namespace()))continue;
                if(!runtime.taskId().equals(observation.taskId()) || !runtime.runId().equals(observation.runId()) || runtime.epoch()!=observation.epoch() || !runtime.jobName().equals(observation.name()) ||
                        (runtime.jobUid()!=null&&!runtime.jobUid().equals(observation.jobUid()))) {
                    lifecycle.observeFailure(runtime.attemptId(),"OWNERSHIP_CONFLICT");continue;
                }
                if(runtime.jobUid()==null || runtime.observedState().equals("TERMINATED"))lifecycle.submitted(runtime.attemptId(),observation.jobUid());
                if(observation.state().equals("FAILED"))lifecycle.observeFailure(runtime.attemptId(),"JOB_FAILED");
                else if(observation.state().equals("COMPLETE"))lifecycle.observeFailure(runtime.attemptId(),"RESULT_MISSING");
            }
            // Rows read after the list could refer to Jobs created after its resourceVersion.
            for(var runtime:active) {
                if(!runtime.desiredState().equals("RUNNING"))continue;
                if(runtime.expiresAt()!=null && !clock.instant().isBefore(runtime.expiresAt()))lifecycle.observeFailure(runtime.attemptId(),"RUNTIME_TIMEOUT");
                else if(runtime.jobUid()!=null && !snapshot.jobs().containsKey(runtime.attemptId()))lifecycle.observeFailure(runtime.attemptId(),"RUNTIME_LOST");
                else if(runtime.jobUid()==null && clock.instant().isAfter(runtime.createdAt().plusSeconds(settings.dispatchSeconds())))lifecycle.observeFailure(runtime.attemptId(),"DISPATCH_TIMEOUT");
            }
            // A closed/expired watch (including HTTP/event 410) always returns to a fresh paginated list.
            gateway.watchJobs(snapshot.resourceVersion());
        } catch(RuntimeException error){log("Runtime reconciliation failed; durable state retained",error);}
    }
    private static void log(String message,RuntimeException error){LoggerFactory.getLogger(RuntimeWorker.class).warn("{} ({})",message,error.getClass().getSimpleName());}
}
