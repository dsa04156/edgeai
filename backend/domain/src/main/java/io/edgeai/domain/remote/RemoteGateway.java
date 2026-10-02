package io.edgeai.domain.remote;
import java.nio.file.Path;
import java.util.Optional;
/** Normalized platform port. Actual 2세부 API mapping requires the external contract. */
public interface RemoteGateway extends AutoCloseable {
    RemoteStatus reserve(RemoteWork work);
    RemoteStatus uploadInput(RemoteIdentity identity,RemoteFile input,Path source);
    RemoteStatus start(RemoteIdentity identity);
    Optional<RemoteStatus> inspect(RemoteIdentity identity);
    RemoteStatus cancel(RemoteIdentity identity);
    void downloadOutput(RemoteIdentity identity,RemoteFile output,Path destination);
    @Override void close();
}
