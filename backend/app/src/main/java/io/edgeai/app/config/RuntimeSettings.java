package io.edgeai.app.config;
import io.edgeai.domain.runtime.*;
import java.net.URI;
import java.util.UUID;
public record RuntimeSettings(String namespace,String serviceAccount,URI controlPlane,int dispatchSeconds,String caConfigMap) {
    public RuntimeSettings(String namespace,String serviceAccount,URI controlPlane,int dispatchSeconds){this(namespace,serviceAccount,controlPlane,dispatchSeconds,"");}
    public RuntimeSettings {
        java.util.Objects.requireNonNull(caConfigMap);if(!caConfigMap.isEmpty())RuntimeNames.dns(caConfigMap,253);
        new RuntimeLaunch(new UUID(0,0),new UUID(0,0),new UUID(0,0),1,namespace,serviceAccount,"validation",controlPlane,null,null);
        if(dispatchSeconds<10 || dispatchSeconds>3600)throw new IllegalArgumentException("Dispatch timeout must be 10..3600 seconds");
    }
}
