package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.repository.DataRouteRepository;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.StreamBrokerGateway.Permission;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Durable generation states are the command journal. Network work never holds a DB transaction.
 * The scan keeps fencing stale actors while the bounded executor waits for the broker. */
public final class StreamAuthorityWorker implements AutoCloseable {
    private final DataRouteRepository repository;
    private final DataRouteService lifecycle;
    private final StreamBrokerGateway broker;
    private final String brokerDigest;
    private final int pageSize;
    private final ExecutorService executor;
    private final Semaphore slots;
    private final Set<UUID> running=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private UUID cursor,dispatchCursor;

    public StreamAuthorityWorker(DataRouteRepository repository,DataRouteService lifecycle,StreamBrokerGateway broker,
            String brokerDigest,int concurrency,int pageSize){
        this.repository=Objects.requireNonNull(repository);this.lifecycle=Objects.requireNonNull(lifecycle);this.broker=Objects.requireNonNull(broker);
        RouteGeneration.digest(brokerDigest);this.brokerDigest=brokerDigest;
        if(concurrency<1 || concurrency>16 || pageSize<1 || pageSize>256)throw new IllegalArgumentException("Invalid stream worker bounds");
        this.pageSize=pageSize;
        slots=new Semaphore(concurrency);
        executor=Executors.newFixedThreadPool(concurrency,Thread.ofPlatform().daemon(true).name("edgeai-stream-authority-",0).factory());
    }

    @Scheduled(fixedDelayString="${edgeai.stream.reconcile-ms:500}",scheduler="streamTaskScheduler")
    public synchronized void tick(){
        if(closed)return;
        try {
            var ids=repository.openGenerations(brokerDigest,cursor,pageSize);
            cursor=ids.isEmpty()?null:ids.getLast();
            for(var id:ids)try {
                lifecycle.reconcile(id);
            }catch(RuntimeException error){log("Stream authority reconciliation deferred",error);}
            var pending=repository.pendingGenerations(brokerDigest,dispatchCursor,pageSize);
            if(pending.isEmpty())dispatchCursor=null;
            for(var id:pending){
                if(!submit(id))break;
                // Keep the next unsubmitted candidate when all slots are full. A failing early
                // generation cannot repeatedly win the first slot and starve later generations.
                dispatchCursor=id;
            }
        }catch(RuntimeException error){log("Stream authority scan deferred",error);}
    }

    private boolean submit(UUID id){
        if(closed)return false;if(running.contains(id))return true;if(!slots.tryAcquire())return false;
        running.add(id);
        try{executor.execute(()->{try{apply(id);}catch(RuntimeException error){log("Stream broker command deferred",error);}finally{running.remove(id);slots.release();}});}
        catch(RejectedExecutionException stopped){running.remove(id);slots.release();return false;}
        return true;
    }
    private void apply(UUID id){
        if(closed)return;
        var current=lifecycle.reconcile(id);
        if(!brokerDigest.equals(current.brokerDigest()) || current.closedAt()!=null)return;
        if(current.state().equals("PREPARING"))try {
            var receipt=broker.grant(permission(current));
            verify(current,receipt);
            // Current actor, lease and fence are checked in a new transaction after the response.
            lifecycle.activate(receipt);
        }catch(StreamBrokerException failure){
            // UNAVAILABLE may be a lost response after a partial or complete grant. Leave the
            // intent for an idempotent retry; permanent conflicts must not remain grantable.
            if(failure.reason()!=StreamBrokerException.Reason.UNAVAILABLE)lifecycle.fence(id,"FAILED");
            log("Stream grant deferred",failure);
        }catch(ControlPlaneException failure){
            lifecycle.reconcile(id);
            if(failure.code().equals("STREAM_BROKER_MISMATCH"))lifecycle.fence(id,"FAILED");
            log("Stream activation rejected",failure);
        }
        if(closed)return;
        current=lifecycle.reconcile(id);
        if(current.state().equals("FENCED")){
            var receipt=broker.revoke(permission(current));
            verify(current,receipt);
            lifecycle.revoked(receipt);
        }
    }
    private Permission permission(RouteGeneration generation){return new Permission(repository.route(generation.routeId(),false).orElseThrow(),generation);}
    private static void verify(RouteGeneration generation,RouteGeneration.BrokerReceipt receipt){
        if(receipt==null || !generation.matches(receipt))throw new StreamBrokerException(StreamBrokerException.Reason.INVALID_RESPONSE);
    }
    private static void log(String message,RuntimeException error){
        // Broker payloads, credentials and exception messages are never logged.
        LoggerFactory.getLogger(StreamAuthorityWorker.class).warn("{} ({})",message,error.getClass().getSimpleName());
    }
    @Override public void close(){closed=true;executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
