package io.edgeai.domain.remote;
import io.edgeai.domain.runtime.RuntimeNames;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
public record RemoteFile(String port,long bytes,String sha256,String mediaType) {
    public RemoteFile {
        RuntimeNames.port(port);
        if(bytes<0 || bytes>ServiceExecutionSpec.MAX_FILE_BYTES || sha256==null || !sha256.matches("[a-f0-9]{64}") ||
            mediaType==null || mediaType.length()>128 || !mediaType.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*"))
            throw new IllegalArgumentException("Invalid remote artifact metadata");
    }
}
