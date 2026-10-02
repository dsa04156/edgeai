package io.edgeai.app.config;
import io.edgeai.domain.runtime.*;
import java.net.URI;
import java.util.UUID;
public record RuntimeSettings(String namespace,String serviceAccount,URI controlPlane,int dispatchSeconds) {
    public RuntimeSettings {
        new RuntimeLaunch(new UUID(0,0),new UUID(0,0),new UUID(0,0),1,namespace,serviceAccount,"validation",controlPlane,null,null);
        if(dispatchSeconds<10 || dispatchSeconds>3600)throw new IllegalArgumentException("Dispatch timeout must be 10..3600 seconds");
    }
}
