package io.edgeai.app.service;

import io.edgeai.adapters.kubernetes.KubernetesVDPodCompiler;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.vd.*;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Kubernetes I/O is outside transactions. Durable command leases and retained tombstones recover ambiguity. */
public final class VDWorker {
    private final VDRuntimeRepository runtimes;
    private final ProfileRepository profiles;
    private final VDLifecycleService lifecycle;
    private final VDGateway gateway;
    private final VDTokenService tokens;
    private final RuntimeSettings settings;
    private final Clock clock;
    private final KubernetesVDPodCompiler compiler=new KubernetesVDPodCompiler();
    public VDWorker(VDRuntimeRepository runtimes,ProfileRepository profiles,VDLifecycleService lifecycle,VDGateway gateway,VDTokenService tokens,RuntimeSettings settings,Clock clock) {
        this.runtimes=runtimes;this.profiles=profiles;this.lifecycle=lifecycle;this.gateway=gateway;this.tokens=tokens;this.settings=settings;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${edgeai.vd.command-poll-ms:1000}")
    public void commands() {
        try{for(int i=0;i<10 && dispatchOne();i++);}
        catch(RuntimeException e){log("VD command polling failed",e);}
    }
    public boolean dispatchOne() {
        var leased=runtimes.leaseCommand(settings.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofSeconds(45));
        if(leased.isEmpty())return false;var cmd=leased.get();
        try {
            lifecycle.reconcile(cmd.runtimeId());var r=lifecycle.get(cmd.runtimeId());
            if(cmd.kind().equals("CREATE")) {
                if(r.desiredState().equals("RUNNING")) {
                    var launch=VDRuntimeDocuments.launch(r);
                    if(!launch.serviceAccount().equals(settings.serviceAccount()) || !launch.controlPlane().equals(settings.controlPlane()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT);
                    var config=VDRuntimeDocuments.read(r.configurationJson());
                    var profile=profiles.find(UUID.fromString((String)config.get("serviceProfileVersionId"))).orElseThrow();
                    UUID uid=gateway.ensurePod(r,compiler.compile(ServiceExecutionInput.parseSpec(profile.specJson()),launch),tokens.issue(r));
                    lifecycle.submitted(r.id(),uid);
                }
            } else {
                if(!r.desiredState().equals("STOPPED"))throw new IllegalStateException("VD deletion requires a stopped runtime");
                if(!gateway.stop(r)){defer(cmd,1);return true;}
                lifecycle.confirmStopped(r.id());
            }
            runtimes.finishCommand(cmd.id(),cmd.leaseOwner(),clock.instant());
        } catch(RuntimeGatewayException e) {
            if(Set.of(RuntimeGatewayException.Reason.RUNTIME_LOST,RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT).contains(e.reason()))lifecycle.fail(cmd.runtimeId(),e.reason().name());
            defer(cmd,Math.min(30,Math.max(1,cmd.attempts())));log("VD command deferred",e);
        } catch(RuntimeException e) {defer(cmd,5);log("VD command deferred",e);}
        return true;
    }
    private void defer(VDCommand c,int seconds){var now=clock.instant();runtimes.deferCommand(c.id(),c.leaseOwner(),now.plusSeconds(seconds),now);}
    @Scheduled(fixedDelayString="${edgeai.vd.reconcile-ms:1000}")
    public void reconcile() {
        try {
            // Read DB before the list: a later CREATE must not be mistaken for an absent older Pod.
            var active=runtimes.active(settings.namespace(),10000);
            // Leases and startup/drain deadlines still expire when the Kubernetes API is unavailable.
            for(var r:active)lifecycle.reconcile(r.id());
            var snapshot=gateway.listPods();
            for(var observation:snapshot.pods().values()) {
                var r=runtimes.runtime(observation.runtimeId()).orElse(null);
                if(r==null || !r.namespace().equals(settings.namespace()))continue;
                try{lifecycle.observed(observation);}catch(RuntimeException e){log("VD observation rejected",e);}
            }
            for(var r:active)if(!r.desiredState().equals("STOPPED") && r.podUid()!=null && !snapshot.pods().containsKey(r.id()))lifecycle.fail(r.id(),"RUNTIME_LOST");
            // All watch endings, including resourceVersion expiry, return to a fresh paginated list.
            gateway.watchPods(snapshot.resourceVersion());
        } catch(RuntimeException e){log("VD reconciliation failed; durable state retained",e);}
    }
    private static void log(String message,RuntimeException error){LoggerFactory.getLogger(VDWorker.class).warn("{} ({})",message,error.getClass().getSimpleName());}
}
