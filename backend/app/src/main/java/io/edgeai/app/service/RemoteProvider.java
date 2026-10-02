package io.edgeai.app.service;
import io.edgeai.adapters.remote.ReferenceRemoteGateway;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.remote.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import static io.edgeai.app.service.WorkflowService.error;

/** One configured provider with a frozen origin/protocol/trust binding. Bearer rotation does not change identity. */
@Service
public final class RemoteProvider implements AutoCloseable {
    private final RemoteTarget target;
    private final RemoteGateway gateway;
    @Autowired
    public RemoteProvider(@Value("${edgeai.remote.enabled:false}") boolean enabled,@Value("${edgeai.remote.provider-key:reference}") String key,
            @Value("${edgeai.remote.url:}") String origin,@Value("${edgeai.remote.token-file:}") String tokenFile,
            @Value("${edgeai.remote.ca-file:}") String caFile,@Value("${edgeai.remote.source-mode:SYNTHETIC}") String sourceMode,
            @Value("${edgeai.remote.timeout-seconds:10}") int timeout) {
        if(!enabled){target=null;gateway=null;return;}
        if(tokenFile.isBlank() || timeout<1 || timeout>30)throw new IllegalArgumentException("Remote credential file and timeout 1..30 required");
        String trust="system";Path ca=caFile.isBlank()?null:Path.of(caFile);
        try {if(ca!=null)trust=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ca)));}
        catch(Exception e){throw new IllegalArgumentException("Cannot read Remote trust bundle");}
        target=new RemoteTarget(key,new JsonDocuments().digest("edgeai-remote-provider-v1",Map.of("origin",origin,"protocol","edgeai.remote.reference/v1","trust",trust,"sourceMode",sourceMode)),sourceMode);
        gateway=new ReferenceRemoteGateway(origin,Path.of(tokenFile),ca,Duration.ofSeconds(timeout),sourceMode);
    }
    public RemoteTarget select(String key) {
        if(target==null)throw error(503,"REMOTE_DISABLED","Remote 제공자가 설정되지 않았습니다.");
        if(!target.providerKey().equals(key))throw error(404,"REMOTE_PROVIDER_NOT_FOUND","설정된 Remote 제공자 key를 확인하세요.");
        return target;
    }
    public RemoteGateway gateway(RemoteTarget pinned) {
        if(target==null || !target.equals(pinned))throw error(503,"REMOTE_CONFIGURATION_CHANGED","이 실행에 고정된 제공자 설정을 복구해야 합니다.");
        return gateway;
    }
    @jakarta.annotation.PreDestroy @Override public void close(){if(gateway!=null)gateway.close();}
}
